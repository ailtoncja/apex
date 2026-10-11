package app.apex

import app.apex.player.EDGE_CACHE_MS
import app.apex.player.EDGE_KEEP_WITH_PREFETCH
import app.apex.player.LiveEdgeProxy
import app.apex.player.PlaySource
import app.apex.player.isoMillisPrecise
import app.apex.player.parseLivePlaylist
import app.apex.player.trimLivePlaylist
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Menos atraso nas lives: a lista curta com os trechos adiantados que o VLC enxerga em vez da lista inteira. */
class LiveEdgeTest {
    private val base = "https://playlist.example/v1/playlist/abc.m3u8"

    /** Uma lista como a da Twitch: 6 trechos completos de 2 s, arquivo de início (fMP4), hora do servidor e 2 trechos adiantados. */
    private fun twitch(sequence: Long = 100, prefetch: Boolean = true, discontinuityAt: Int? = null, ended: Boolean = false) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:6")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$sequence")
        appendLine("""#EXT-X-DATERANGE:ID="playlist-creation-1",CLASS="timestamp",START-DATE="2026-10-08T17:25:28.912Z",X-SERVER-TIME="1791480328.912"""")
        appendLine("""#EXT-X-MAP:URI="https://cdn.example/init"""")
        repeat(6) { i ->
            if (i == discontinuityAt) appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-10-08T17:25:%02d.591Z".format(14 + i * 2))
            appendLine("#EXTINF:2.000,live")
            appendLine("https://cdn.example/seg${sequence + i}")
        }
        if (prefetch) {
            appendLine("#EXT-X-TWITCH-PREFETCH:https://cdn.example/seg${sequence + 6}")
            appendLine("#EXT-X-TWITCH-PREFETCH:https://cdn.example/seg${sequence + 7}")
        }
        if (ended) appendLine("#EXT-X-ENDLIST")
    }

    // ------------------------------------------------------------------ ler a lista

    @Test
    fun le_os_trechos_os_adiantados_e_a_hora_do_servidor() {
        val live = parseLivePlaylist(twitch(), base)!!
        assertEquals(8, live.segments.size)
        assertEquals(listOf(false, false, false, false, false, false, true, true), live.segments.map { it.prefetch })
        assertEquals(100, live.mediaSequence)
        assertEquals(1791480328912, live.serverTimeMs)
        assertEquals("https://cdn.example/init", live.segments[0].map)
        // o primeiro adiantado começa onde o último completo termina (a lista não diz a hora dele)
        val last = live.segments[5]
        assertEquals(last.startMs!! + 2000, live.segments[6].startMs)
        assertEquals(live.segments[6].startMs!! + 2000, live.segments[7].startMs)
        assertEquals(2.0, live.segments[6].duration)
    }

    @Test
    fun a_lista_mestra_e_a_lista_vazia_nao_servem() {
        assertNull(parseLivePlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1x1\nhttps://x/v.m3u8\n", base))
        assertNull(parseLivePlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6\n", base))
        assertNull(parseLivePlaylist("isto nao e uma lista", base))
    }

    @Test
    fun enderecos_relativos_viram_absolutos() {
        val live = parseLivePlaylist("#EXTM3U\n#EXTINF:2.0,\nseg1.ts\n#EXTINF:2.0,\n/raiz/seg2.ts\n", "https://host.example/a/b/lista.m3u8?token=1")!!
        assertEquals(listOf("https://host.example/a/b/seg1.ts", "https://host.example/raiz/seg2.ts"), live.segments.map { it.uri })
    }

    @Test
    fun a_hora_tem_os_milissegundos() {
        assertEquals(1791480325591, isoMillisPrecise("2026-10-08T17:25:25.591Z"))
        assertEquals(1791480325000, isoMillisPrecise("2026-10-08T17:25:25Z"))
        assertNull(isoMillisPrecise("ontem"))
    }

    // ------------------------------------------------------------------ cortar a lista

    @Test
    fun com_adiantados_fica_o_ultimo_completo_e_os_adiantados_com_o_tempo_certo() {
        val out = trimLivePlaylist(parseLivePlaylist(twitch(), base)!!, keep = 2, keepWithPrefetch = 1)
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals("#EXTM3U", lines.first())
        assertTrue("#EXT-X-TARGETDURATION:2" in lines, "o tempo verdadeiro dos trechos, não os 6 s que a Twitch declara")
        assertTrue("#EXT-X-MEDIA-SEQUENCE:105" in lines, "o número do último completo (100 + 5)")
        assertEquals(listOf("https://cdn.example/seg105", "https://cdn.example/seg106", "https://cdn.example/seg107"), lines.filter { it.startsWith("https://cdn.example/seg") })
        assertEquals(1, lines.count { it.startsWith("#EXT-X-MAP") }, "o arquivo de início uma vez só")
        assertEquals(3, lines.count { it.startsWith("#EXTINF:2.000") })
        assertFalse("#EXT-X-ENDLIST" in lines)
    }

    @Test
    fun sem_adiantados_ficam_os_ultimos_n() {
        val out = trimLivePlaylist(parseLivePlaylist(twitch(prefetch = false), base)!!, keep = 2, keepWithPrefetch = 1)
        assertEquals(listOf("https://cdn.example/seg104", "https://cdn.example/seg105"), out.lines().filter { it.startsWith("https://cdn.example/seg") })
        assertTrue("#EXT-X-MEDIA-SEQUENCE:104" in out.lines())
    }

    @Test
    fun descontinuidade_entre_dois_trechos_mantidos_continua_e_a_do_primeiro_nao_vai() {
        // a marca vem antes do trecho 105
        val live = parseLivePlaylist(twitch(discontinuityAt = 5), base)!!
        // 105 é o primeiro mantido: sem trecho anterior não há o que separar
        assertEquals(0, trimLivePlaylist(live, keep = 2, keepWithPrefetch = 1).lines().count { it == "#EXT-X-DISCONTINUITY" })
        // mantendo também o 104, a marca separa os dois
        val out = trimLivePlaylist(live, keep = 3, keepWithPrefetch = 2).lines()
        assertEquals(1, out.count { it == "#EXT-X-DISCONTINUITY" })
        assertTrue(out.indexOf("#EXT-X-DISCONTINUITY") > out.indexOf("https://cdn.example/seg104"))
        assertTrue(out.indexOf("#EXT-X-DISCONTINUITY") < out.indexOf("https://cdn.example/seg105"))
    }

    @Test
    fun live_encerrada_mantem_o_fim_da_lista() {
        val out = trimLivePlaylist(parseLivePlaylist(twitch(prefetch = false, ended = true), base)!!, keep = 2)
        assertTrue("#EXT-X-ENDLIST" in out.lines())
    }

    // ------------------------------------------------------------------ o servidorzinho local

    private fun client(playlists: () -> String) = HttpClient(MockEngine { respond(playlists(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl")) })

    private fun fetch(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try { c.responseCode to (if (c.responseCode < 400) c.inputStream.readBytes().decodeToString() else "") } finally { c.disconnect() }
    }

    @Test
    fun serve_a_lista_curta_so_para_o_proprio_computador_e_acompanha_a_original() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var upstream = twitch(sequence = 100)
        val proxy = LiveEdgeProxy(scope, client { upstream }, keep = 2, keepWithPrefetch = EDGE_KEEP_WITH_PREFETCH)
        try {
            assertTrue(proxy.start("https://playlist.example/v1/live.m3u8", emptyMap()))
            assertTrue(proxy.url.startsWith("http://127.0.0.1:"), "só no próprio computador")
            val (code, body) = fetch(proxy.url)
            assertEquals(200, code)
            assertTrue("#EXT-X-MEDIA-SEQUENCE:105" in body.lines())

            // a plataforma gera trechos novos: a lista local acompanha
            upstream = twitch(sequence = 103)
            val end = System.currentTimeMillis() + 5_000
            while ("#EXT-X-MEDIA-SEQUENCE:108" !in fetch(proxy.url).second.lines() && System.currentTimeMillis() < end) Thread.sleep(100)
            assertTrue("#EXT-X-MEDIA-SEQUENCE:108" in fetch(proxy.url).second.lines())

            assertEquals(404, fetch(proxy.url.replace("live.m3u8", "outra")).first)

            // a estimativa: o corte tira 2 trechos (4 s) do atraso em relação a começar 3 trechos antes do fim
            val e = proxy.estimate
            assertNotNull(e)
            assertEquals(4000, e.trimmedStartServerMs - e.directStartServerMs)
        } finally {
            proxy.close()
            scope.cancel()
        }
    }

    @Test
    fun lista_sem_adiantados_ou_mestra_ou_encerrada_fica_com_o_vlc_direto() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        for (text in listOf(twitch(prefetch = false), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1x1\nhttps://x/v.m3u8\n", twitch(ended = true))) {
            val proxy = LiveEdgeProxy(scope, client { text }, keep = 2, keepWithPrefetch = 1)
            assertFalse(proxy.start("https://playlist.example/v1/live.m3u8", emptyMap()), "não deve cortar: ${text.take(40)}")
            proxy.close()
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------ as opções do VLC

    @Test
    fun pela_lista_curta_o_vlc_usa_o_buffer_pequeno_e_sem_o_atraso_de_inicio() {
        val source = PlaySource("http://127.0.0.1:1/live.m3u8", live = true, lowLatency = true)
        val edge = vlcOptions(source, viaEdge = true)
        assertTrue(":network-caching=$EDGE_CACHE_MS" in edge)
        assertTrue(edge.none { it.startsWith(":adaptive-livedelay") })
        // direto continua como antes
        assertTrue(":adaptive-livedelay=3000" in vlcOptions(source))
    }
}
