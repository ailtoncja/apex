package app.apex

import app.apex.player.LiveTsRelay
import app.apex.player.PlaySource
import app.apex.player.RELAY_CACHE_MS
import app.apex.player.ServerClock
import app.apex.player.parseHttpDate
import app.apex.player.parseLivePlaylist
import app.apex.player.relayStart
import app.apex.player.vlcOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live perto do "ao vivo" como na Twitch: o fluxo contínuo, os anúncios no meio do caminho e a hora do servidor para medir o atraso. */
class LiveTsRelayTest {
    private val base = "https://playlist.example/v1/playlist/abc.m3u8"

    /**
     * Uma lista MPEG-TS como a da Twitch: [complete] trechos de 2 s (`L` = live, `A` = anúncio, `|` = troca de vídeo antes do próximo) e, se
     * houver, os adiantados (`P`, também com `|`).
     */
    private fun playlist(sequence: Long, pattern: String) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$sequence")
        var n = sequence
        var pendingCut = false
        var second = 0
        for (c in pattern) {
            when (c) {
                '|' -> pendingCut = true
                'L', 'A' -> {
                    if (pendingCut) appendLine("#EXT-X-DISCONTINUITY")
                    pendingCut = false
                    appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-10-08T17:25:%02d.000Z".format(second))
                    appendLine(if (c == 'L') "#EXTINF:2.000,live" else "#EXTINF:2.000,Amazon|1")
                    appendLine("https://cdn.example/seg$n")
                    n++; second += 2
                }
                'P' -> {
                    if (pendingCut) appendLine("#EXT-X-DISCONTINUITY")
                    pendingCut = false
                    appendLine("#EXT-X-TWITCH-PREFETCH:https://cdn.example/seg$n")
                    n++
                }
            }
        }
    }

    // ------------------------------------------------------------------ anúncios na lista

    @Test
    fun a_troca_de_video_antes_de_um_adiantado_nao_se_perde() {
        // Começo de um anúncio: o segundo adiantado já é dele.
        val live = parseLivePlaylist(playlist(100, "LLLLP|P"), base)!!
        // (é nela que o fluxo para e a live é reaberta, ver na_troca_para_o_anuncio_o_fluxo_para_e_devolve_a_live_ao_player)
        assertEquals(listOf(false, false, false, false, false, true), live.segments.map { it.discontinuity })
    }

    @Test
    fun logo_depois_do_anuncio_ainda_nao_e_hora_de_abrir_perto_do_ao_vivo() {
        // O anúncio acabou agora: o último completo ainda é dele, e a hora do adiantado (que sai do completo anterior) viria errada.
        val justAfter = parseLivePlaylist(playlist(0, "AAAA|PP"), base)!!
        assertNull(relayStart(justAfter, 0))
        // Um trecho depois, o completo já é da live.
        val next = parseLivePlaylist(playlist(0, "AAA|LPP"), base)!!
        assertEquals(4, relayStart(next, 0), "começa no primeiro adiantado")
        assertEquals(3, relayStart(next, 1), "ou no completo antes dele, que já é da live")
        // Sem adiantados (no anúncio, ou o canal sem baixa latência), nunca.
        assertNull(relayStart(parseLivePlaylist(playlist(0, "LLLL"), base)!!, 0))
    }

    // ------------------------------------------------------------------ a hora do servidor

    @Test
    fun a_hora_do_servidor_fica_mais_precisa_a_cada_resposta() {
        val clock = ServerClock()
        assertNull(clock.skewMs)
        // O servidor disse 10 s numa resposta pedida aos 20 s e recebida aos 20,1 s (relógio do PC): o desvio está entre 9,001 e 10,1 s.
        clock.note(10_000, 20_000, 20_100)
        assertEquals((9_001L + 10_100L) / 2, clock.skewMs)
        // Outra: 11 s, pedida aos 20,95 e recebida aos 21 s: entre 8,951 e 10 s. Juntas: entre 9,001 e 10 s.
        clock.note(11_000, 20_950, 21_000)
        assertEquals((9_001L + 10_000L) / 2, clock.skewMs)
        // O relógio do PC foi acertado (faixa nova sem nada em comum com a antiga): vale a nova.
        clock.note(11_000, 13_000, 13_050)
        assertEquals((13_000L - 11_000 - 999 + 13_050 - 11_000) / 2, clock.skewMs)
    }

    @Test
    fun le_a_hora_do_cabecalho_date() {
        assertEquals(1792634901000, parseHttpDate("Thu, 22 Oct 2026 02:08:21 GMT"))
        assertNull(parseHttpDate("ontem"))
    }

    // ------------------------------------------------------------------ o fluxo contínuo

    private fun client(playlists: () -> String) = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            if (url.startsWith("https://cdn.example/seg") || url == "https://cdn.example/init") {
                respond("[" + url.substringAfterLast("/").removePrefix("seg") + "]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "video/mp2t"))
            } else {
                respond(
                    playlists(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/vnd.apple.mpegurl"), HttpHeaders.Date to listOf("Thu, 08 Oct 2026 17:25:30 GMT")),
                )
            }
        },
    )

    /** Abre o fluxo como o VLC e lê até ter [marks] trechos (`[número]`); devolve a primeira linha da resposta e os números na ordem. */
    private fun read(url: String, marks: Int, host: String? = null): Pair<String, List<String>> {
        val port = url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        val path = "/" + url.substringAfter("$port/")
        return Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:$port"}\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val input = s.getInputStream()
            val text = StringBuilder()
            val found = mutableListOf<String>()
            while (found.size < marks || "\r\n" !in text) {
                val b = input.read()
                if (b < 0) break
                text.append(b.toChar())
                if (b == ']'.code) found += text.substring(text.lastIndexOf("[") + 1, text.length - 1)
            }
            text.lineSequence().first().trim() to found
        }
    }

    @Test
    fun repassa_os_trechos_em_ordem_a_partir_do_mais_novo_e_acompanha_a_lista() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var upstream = playlist(100, "LLLLPP")
        val relay = LiveTsRelay(scope, client { upstream }, startBack = 0)
        try {
            assertTrue(relay.start("https://playlist.example/v1/live.m3u8", emptyMap()))
            assertTrue(relay.url.startsWith("http://127.0.0.1:"), "só no próprio computador")
            // a hora do servidor veio do cabeçalho Date, e o primeiro trecho mostrado é o adiantado 104 (o último completo, 103, começa aos 6 s)
            assertNotNull(relay.clock.skewMs)
            assertEquals(parseLivePlaylist(upstream, base)!!.segments[4].startMs, relay.startServerMs)

            Thread { Thread.sleep(700); upstream = playlist(102, "LLLLPP") }.start()
            val (status, order) = read(relay.url, 4)
            assertEquals("HTTP/1.1 200 OK", status)
            // 104 e 105 (os adiantados), depois os que a lista nova trouxe: 106 e 107
            assertEquals(listOf("104", "105", "106", "107"), order)
        } finally {
            relay.close()
            scope.cancel()
        }
    }

    /** Abre o fluxo, espera [upstream] trocar para [next] e devolve os trechos que chegaram e se o fluxo devolveu a live ao player. */
    private fun untilBreak(first: String, next: String): Pair<List<String>, Boolean> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var upstream = first
        val broke = java.util.concurrent.CountDownLatch(1)
        val relay = LiveTsRelay(scope, client { upstream }, startBack = 0) { broke.countDown() }
        try {
            assertTrue(relay.start("https://playlist.example/v1/live.m3u8", emptyMap()))
            Thread { Thread.sleep(700); upstream = next }.start()
            val got = read(relay.url, 2).second
            got to broke.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            relay.close()
            scope.cancel()
        }
    }

    @Test
    fun na_troca_para_o_anuncio_o_fluxo_para_e_devolve_a_live_ao_player() {
        // O anúncio entra marcado (o 106 já é dele): o fluxo entrega os dois adiantados da live e para antes dele, sem repassar o salto ao VLC.
        assertEquals(listOf("104", "105") to true, untilBreak(playlist(100, "LLLLPP"), playlist(102, "LLLL|AA")))
        // A Twitch troca a numeração (a lista do anúncio começa do 0): o 106 não existe mais, e o fluxo também para.
        assertEquals(listOf("104", "105") to true, untilBreak(playlist(100, "LLLLPP"), playlist(0, "AAAA")))
    }

    @Test
    fun recusa_o_que_nao_e_do_proprio_servidor_e_lista_sem_adiantados() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = LiveTsRelay(scope, client { playlist(100, "LLLLPP") }, startBack = 0)
        try {
            assertTrue(relay.start("https://playlist.example/v1/live.m3u8", emptyMap()))
            val port = relay.url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
            assertEquals("HTTP/1.1 403 Forbidden", read(relay.url, 0, host = "evil.example:$port").first)
            assertEquals("HTTP/1.1 404 Not Found", read("http://127.0.0.1:$port/live.ts", 0).first)
        } finally {
            relay.close()
        }

        val noAhead = LiveTsRelay(scope, client { playlist(100, "LLLL") })
        assertFalse(noAhead.start("https://playlist.example/v1/live.m3u8", emptyMap()))
        assertTrue(noAhead.noPrefetch, "sem adiantados (anúncio do começo): vale esperar")
        noAhead.close()
        scope.cancel()
    }

    @Test
    fun no_fmp4_o_arquivo_de_inicio_vai_antes_dos_fragmentos() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // a Twitch manda alguns canais em fMP4: a lista traz o arquivo de início (EXT-X-MAP)
        val fmp4 = playlist(100, "LLLLPP").replace("#EXT-X-MEDIA-SEQUENCE:100", "#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-MAP:URI=\"https://cdn.example/init\"")
        val relay = LiveTsRelay(scope, client { fmp4 }, startBack = 0)
        try {
            assertTrue(relay.start("https://playlist.example/v1/live.m3u8", emptyMap()))
            assertTrue(relay.fmp4)
            assertEquals(listOf("init", "104", "105"), read(relay.url, 3).second)
        } finally {
            relay.close()
            scope.cancel()
        }
    }

    @Test
    fun pelo_fluxo_o_vlc_ja_sabe_o_formato_e_usa_o_buffer_pequeno() {
        val source = PlaySource("http://127.0.0.1:1/x/live", live = true, lowLatency = true)
        val ts = vlcOptions(source, viaRelay = true)
        assertTrue(":network-caching=$RELAY_CACHE_MS" in ts)
        assertTrue(":demux=ts" in ts)
        assertTrue(ts.none { it.startsWith(":adaptive-livedelay") })
        assertTrue(":demux=mp4" in vlcOptions(source, viaRelay = true, relayMp4 = true))
        // sem o fluxo (anúncio, modo seguro), o VLC lê a lista da plataforma com o atraso mínimo dele
        assertTrue(":adaptive-livedelay=3000" in vlcOptions(source))
    }
}
