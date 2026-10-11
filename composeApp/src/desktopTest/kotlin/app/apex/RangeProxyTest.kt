package app.apex

import app.apex.player.RangeProxy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import app.apex.player.FullAccess
import kotlinx.coroutines.CompletableDeferred
import java.net.Socket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A ponte que busca o vídeo do YouTube em pedaços para o VLC (que pede "do byte tal até o fim", coisa que o YouTube recusa). */
class RangeProxyTest {
    private val size = 10_000
    private val content = ByteArray(size) { (it * 31 % 251).toByte() }

    /** O "YouTube": só atende pedidos com começo e fim, como os endereços da via rápida; qualquer outro é 403. */
    private class FakeYouTube(val content: ByteArray, val maxSpan: Int = Int.MAX_VALUE) {
        val ranges = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val client = HttpClient(MockEngine { request ->
            val header = request.headers["Range"]
            val m = header?.let { Regex("""bytes=(\d+)-(\d+)""").find(it) }
            if (m == null) {
                respond("", HttpStatusCode.Forbidden)
            } else {
                val a = m.groupValues[1].toInt()
                val b = minOf(m.groupValues[2].toInt(), content.size - 1)
                ranges += a.toLong() to b.toLong()
                if (b - a + 1 > maxSpan) respond("", HttpStatusCode.Forbidden)
                else respond(content.copyOfRange(a, b + 1), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType, "video/mp4"))
            }
        })
    }

    private fun url(clen: Int = size) = "https://rr1.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&clen=$clen&c=IOS"

    /**
     * O YouTube com as duas caras: o endereço da via rápida (`fast`) só entrega até o byte [limit] do arquivo (nega o pedido inteiro, com 403, se o
     * fim dele passa disso) e o endereço completo do yt-dlp (`full`) entrega tudo; [fullStatus] deixa o completo falhar.
     */
    private class FakeTwoFaces(val content: ByteArray, val limit: Long, val fullStatus: HttpStatusCode = HttpStatusCode.PartialContent) {
        val calls = Collections.synchronizedList(mutableListOf<Triple<String, Long, Long>>())
        val client = HttpClient(MockEngine { request ->
            val m = request.headers["Range"]?.let { Regex("""bytes=(\d+)-(\d+)""").find(it) }
            val host = request.url.host.substringBefore('.')
            if (m == null) {
                respond("", HttpStatusCode.Forbidden)
            } else {
                val a = m.groupValues[1].toInt()
                val b = minOf(m.groupValues[2].toInt(), content.size - 1)
                calls += Triple(host, a.toLong(), b.toLong())
                when {
                    host == "fast" && b > limit -> respond("", HttpStatusCode.Forbidden)
                    host == "full" && fullStatus != HttpStatusCode.PartialContent -> respond("", fullStatus)
                    else -> respond(content.copyOfRange(a, b + 1), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType, "video/mp4"))
                }
            }
        })
        fun hosts() = calls.map { it.first }.distinct()
    }

    private fun fastUrl(clen: Int = size) = "https://fast.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&clen=$clen&c=IOS"
    private fun fullUrl(clen: Int = size, itag: Int = 137) = "https://full.googlevideo.com/videoplayback?expire=1&itag=$itag&clen=$clen&mime=video%2Fmp4"

    /** Um pedido HTTP cru ao servidorzinho: devolve (primeira linha da resposta, cabeçalhos, corpo). */
    private fun request(local: String, range: String?, method: String = "GET"): Triple<String, String, ByteArray> {
        val port = local.substringAfterLast(':').substringBefore('/').toInt()
        val path = "/" + local.substringAfterLast('/')
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val req = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (range?.let { "Range: $it\r\n" } ?: "") + "Connection: close\r\n\r\n"
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val all = s.getInputStream().readBytes()
            val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = String(all, 0, split, Charsets.ISO_8859_1)
            return Triple(head.lineSequence().first(), head, all.copyOfRange(split + 4, all.size))
        }
    }

    @Test
    fun o_pedido_sem_fim_do_vlc_vira_pedidos_com_fim_e_o_arquivo_chega_inteiro() {
        val upstream = FakeYouTube(content)
        val proxy = RangeProxy(upstream.client, fastChunk = 3_000)
        try {
            val local = assertNotNull(proxy.register(url()))
            assertTrue(local.startsWith("http://127.0.0.1:"), "só no próprio computador")
            val (status, head, body) = request(local, "bytes=0-")
            assertEquals("HTTP/1.1 206 Partial Content", status)
            assertTrue("Content-Range: bytes 0-9999/10000" in head, head)
            assertTrue("Content-Length: 10000" in head && "Content-Type: video/mp4" in head, head)
            assertContentEquals(content, body)
            assertEquals(listOf(0L to 2999L, 3000L to 5999L, 6000L to 8999L, 9000L to 9999L), upstream.ranges.toList(), "o YouTube só viu pedidos com começo e fim")
        } finally {
            proxy.close()
        }
    }

    @Test
    fun pular_para_o_meio_pede_so_dali_para_a_frente() {
        val upstream = FakeYouTube(content)
        val proxy = RangeProxy(upstream.client, fastChunk = 4_000)
        try {
            val local = assertNotNull(proxy.register(url()))
            val (status, head, body) = request(local, "bytes=7500-")
            assertEquals("HTTP/1.1 206 Partial Content", status)
            assertTrue("Content-Range: bytes 7500-9999/10000" in head, head)
            assertContentEquals(content.copyOfRange(7500, size), body)
            assertEquals(listOf(7500L to 9999L), upstream.ranges.toList())

            val (_, headMid, middle) = request(local, "bytes=100-199")
            assertTrue("Content-Range: bytes 100-199/10000" in headMid, headMid)
            assertContentEquals(content.copyOfRange(100, 200), middle)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun sem_cabecalho_range_entrega_o_arquivo_todo_e_head_so_os_cabecalhos() {
        val upstream = FakeYouTube(content)
        val proxy = RangeProxy(upstream.client, fastChunk = 6_000)
        try {
            val local = assertNotNull(proxy.register(url()))
            val (status, _, body) = request(local, null)
            assertEquals("HTTP/1.1 200 OK", status)
            assertContentEquals(content, body)
            val before = upstream.ranges.size
            val (headStatus, headHead, headBody) = request(local, null, "HEAD")
            assertEquals("HTTP/1.1 200 OK", headStatus)
            assertTrue("Content-Length: 10000" in headHead)
            assertEquals(0, headBody.size)
            assertEquals(before, upstream.ranges.size, "HEAD não baixa nada")
        } finally {
            proxy.close()
        }
    }

    @Test
    fun endereco_sem_tamanho_nao_passa_pela_ponte_e_rota_desconhecida_da_404() {
        val proxy = RangeProxy(FakeYouTube(content).client)
        try {
            assertNull(proxy.register("https://rr1.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4"))
            val local = assertNotNull(proxy.register(url()))
            val (status, _, _) = request(local.substringBeforeLast('/') + "/s999", "bytes=0-")
            assertEquals("HTTP/1.1 404 Not Found", status)
            proxy.clear()
            assertEquals("HTTP/1.1 404 Not Found", request(local, "bytes=0-").first, "uma reprodução nova esquece os endereços da anterior")
        } finally {
            proxy.close()
        }
    }

    @Test
    fun faixa_fora_do_arquivo_e_416_e_as_contas_de_faixa_estao_certas() {
        assertEquals(0L to 99L, RangeProxy.parseRange("bytes=0-99", 1000))
        assertEquals(500L to 999L, RangeProxy.parseRange("bytes=500-", 1000))
        assertEquals(900L to 999L, RangeProxy.parseRange("bytes=-100", 1000))
        assertEquals(0L to 999L, RangeProxy.parseRange("bytes=0-99999", 1000), "fim além do arquivo é cortado no fim")
        assertEquals(990L to 999L, RangeProxy.parseRange("bytes=990-5000", 1000))
        assertNull(RangeProxy.parseRange("bytes=1000-", 1000))
        assertEquals(0L to 999L, RangeProxy.parseRange(null, 1000))
        val proxy = RangeProxy(FakeYouTube(content).client)
        try {
            val local = assertNotNull(proxy.register(url()))
            assertEquals("HTTP/1.1 416 Range Not Satisfiable", request(local, "bytes=20000-").first)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun se_o_youtube_recusa_o_pedaco_a_ponte_desiste_sem_travar() {
        val proxy = RangeProxy(FakeYouTube(content, maxSpan = 1_000).client, fastChunk = 4_000)
        try {
            val local = assertNotNull(proxy.register(url()))
            val (status, _, body) = request(local, "bytes=0-")
            assertEquals("HTTP/1.1 206 Partial Content", status)
            assertTrue(body.size < size, "o que não veio fica faltando; a conexão fecha em vez de ficar pendurada")
        } finally {
            proxy.close()
        }
    }

    // ------------------------------------------------------------------ o começo liberado e o yt-dlp

    @Test
    fun a_via_rapida_serve_o_comeco_e_o_resto_vem_do_endereco_completo_quando_o_yt_dlp_chega() {
        val tube = FakeTwoFaces(content, limit = 5_000)
        val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_000)
        try {
            val access = CompletableDeferred<FullAccess?>()
            val local = assertNotNull(proxy.register(fastUrl(), full = access))
            Thread { Thread.sleep(400); access.complete(FullAccess(listOf(fullUrl()), "UA do yt-dlp")) }.start()
            val (status, _, body) = request(local, "bytes=0-")
            assertEquals("HTTP/1.1 206 Partial Content", status)
            assertContentEquals(content, body, "o vídeo chega inteiro, sem emenda")
            val fast = tube.calls.filter { it.first == "fast" }
            val full = tube.calls.filter { it.first == "full" }
            assertEquals(listOf(0L to 1999L, 2000L to 3999L), fast.filter { it.third <= 5_000 }.map { it.second to it.third }, "o que a via rápida entregou")
            assertTrue(full.isNotEmpty() && full.first().second == 4_000L, "o endereço completo continua de onde a via rápida parou: $full")
            assertTrue(full.all { it.third - it.second + 1 <= 4_000 }, "em pedaços do tamanho do completo")
        } finally {
            proxy.close()
        }
    }

    @Test
    fun com_o_endereco_completo_ja_pronto_a_via_rapida_nem_e_chamada() {
        val tube = FakeTwoFaces(content, limit = 5_000)
        val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_000)
        try {
            val access = CompletableDeferred<FullAccess?>().also { it.complete(FullAccess(listOf(fullUrl()), null)) }
            val local = assertNotNull(proxy.register(fastUrl(), full = access))
            val (_, _, body) = request(local, "bytes=0-")
            assertContentEquals(content, body)
            assertEquals(listOf("full"), tube.hosts())
        } finally {
            proxy.close()
        }
    }

    @Test
    fun sem_par_no_yt_dlp_a_ponte_entrega_so_o_comeco_e_fecha_sem_travar() {
        for (access in listOf(
            FullAccess(listOf(fullUrl(clen = size + 1)), null), // mesmo formato, outro tamanho: não é o mesmo arquivo
            FullAccess(listOf(fullUrl(itag = 140)), null), // outro formato
            null, // o yt-dlp falhou
        )) {
            val tube = FakeTwoFaces(content, limit = 5_000)
            val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_000)
            try {
                val local = assertNotNull(proxy.register(fastUrl(), full = CompletableDeferred<FullAccess?>().also { it.complete(access) }))
                val (_, _, body) = request(local, "bytes=0-")
                assertContentEquals(content.copyOfRange(0, 4_000), body, "só o que a via rápida liberou: $access")
                assertTrue("full" !in tube.hosts(), "não usa um endereço que não é o mesmo arquivo")
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun se_o_endereco_completo_falha_a_ponte_volta_para_a_via_rapida() {
        val tube = FakeTwoFaces(content, limit = 99_999, fullStatus = HttpStatusCode.InternalServerError)
        val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_500)
        try {
            val access = CompletableDeferred<FullAccess?>().also { it.complete(FullAccess(listOf(fullUrl()), null)) }
            val local = assertNotNull(proxy.register(fastUrl(), full = access))
            val (_, _, body) = request(local, "bytes=0-")
            assertContentEquals(content, body)
            assertEquals(1, tube.calls.count { it.first == "full" }, "tentou o completo uma vez e largou")
        } finally {
            proxy.close()
        }
    }

    @Test
    fun pular_para_depois_do_comeco_espera_o_yt_dlp_e_entrega_dali() {
        val tube = FakeTwoFaces(content, limit = 3_000)
        val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_000)
        try {
            val access = CompletableDeferred<FullAccess?>()
            val local = assertNotNull(proxy.register(fastUrl(), full = access))
            Thread { Thread.sleep(300); access.complete(FullAccess(listOf(fullUrl()), null)) }.start()
            val (_, head, body) = request(local, "bytes=7000-")
            assertTrue("Content-Range: bytes 7000-9999/10000" in head, head)
            assertContentEquals(content.copyOfRange(7000, size), body)
            assertEquals(listOf("fast", "full"), tube.hosts(), "a via rápida nega o pedido do meio; o resto vem do completo")
        } finally {
            proxy.close()
        }
    }

    @Test
    fun o_endereco_completo_casa_por_formato_e_tamanho() {
        val fast = fastUrl()
        val a = fullUrl(itag = 140)
        val b = fullUrl(itag = 137, clen = size + 5)
        val c = fullUrl()
        assertEquals(c, FullAccess(listOf(a, b, c), null).match(fast))
        assertNull(FullAccess(listOf(a, b), null).match(fast))
        assertNull(FullAccess(emptyList(), null).match(fast))
        assertEquals(a, FullAccess(listOf(a), null).match("https://x/videoplayback?itag=140&mime=audio%2Fmp4"), "sem tamanho no endereço, vale só o formato")
        assertNull(FullAccess(listOf(a), null).match("https://x/videoplayback?mime=audio%2Fmp4"), "sem formato, não dá para casar")
    }

    @Test
    fun a_espiada_do_vlc_no_fim_do_arquivo_nao_segura_a_abertura_esperando_o_yt_dlp() {
        val tube = FakeTwoFaces(content, limit = 3_000)
        val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_000)
        try {
            // O yt-dlp nunca chega neste teste: a resposta tem de vir mesmo assim, e na hora.
            val local = assertNotNull(proxy.register(fastUrl(), full = CompletableDeferred<FullAccess?>()))
            val started = System.nanoTime()
            val (status, head, body) = request(local, "bytes=${size - 16}-")
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("HTTP/1.1 206 Partial Content", status)
            assertTrue("Content-Range: bytes ${size - 16}-${size - 1}/$size" in head, head)
            assertEquals(16, body.size, "o tamanho prometido é entregue")
            assertTrue(tookMs < 3_000, "não esperou o yt-dlp: $tookMs ms")
            // Já um pulo de verdade (para o meio) espera o yt-dlp, como antes.
            val access = CompletableDeferred<FullAccess?>()
            val local2 = assertNotNull(proxy.register(fastUrl(), full = access))
            Thread { Thread.sleep(300); access.complete(FullAccess(listOf(fullUrl()), null)) }.start()
            assertContentEquals(content.copyOfRange(5_000, 6_000), request(local2, "bytes=5000-5999").third)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun com_o_endereco_completo_ja_pronto_a_espiada_no_fim_traz_os_bytes_de_verdade() {
        val tube = FakeTwoFaces(content, limit = 3_000)
        val proxy = RangeProxy(tube.client, chunk = 4_000, fastChunk = 2_000)
        try {
            val access = CompletableDeferred<FullAccess?>().also { it.complete(FullAccess(listOf(fullUrl()), null)) }
            val local = assertNotNull(proxy.register(fastUrl(), full = access))
            assertContentEquals(content.copyOfRange(size - 16, size), request(local, "bytes=${size - 16}-").third)
        } finally {
            proxy.close()
        }
    }
}
