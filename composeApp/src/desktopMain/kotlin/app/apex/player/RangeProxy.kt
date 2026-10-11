package app.apex.player

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import app.apex.util.Timing
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * A ponte entre o VLC e os endereços de vídeo do YouTube pedidos pela via rápida. Esses endereços têm duas manias: só aceitam pedidos de um
 * trecho com começo e fim (`Range: bytes=0-262143`; o VLC pede "do byte tal até o fim" e recebe 403) e, sem a prova de que o pedido vem do
 * aplicativo oficial, só entregam o começo de cada arquivo, cerca de um minuto de vídeo e de áudio. Aqui o VLC fala com um servidorzinho
 * local, que busca o arquivo em pedaços pequenos ([FAST_CHUNK]) pelo endereço da via rápida e, quando ele para de entregar ou quando o yt-dlp
 * já trouxe o endereço completo do mesmo arquivo, segue por este, em pedaços de [CHUNK], como o yt-dlp faz ao baixar. Só atende o próprio
 * computador.
 *
 * Pular para outro ponto do vídeo é só um pedido novo do VLC com o byte inicial dele; o pedaço de antes é descartado.
 */
internal class RangeProxy(
    private val http: HttpClient,
    private val chunk: Int = CHUNK,
    private val fastChunk: Int = FAST_CHUNK,
) {
    private class Route(
        val url: String,
        val length: Long,
        val mime: String,
        val headers: Map<String, String>,
        /** Os endereços completos do yt-dlp, quando chegarem (`null`: não há de onde continuar depois do começo). */
        val full: Deferred<FullAccess?>?,
    ) {
        /** O endereço completo do mesmo arquivo, depois que o yt-dlp respondeu e tem um par para ele. */
        @Volatile var fullUrl: String? = null
        @Volatile var fullHeaders: Map<String, String> = emptyMap()
        @Volatile var fullDecided = false

        /** O endereço completo falhou: daqui em diante só a via rápida (o que ela entrega, dentro do primeiro minuto). */
        @Volatile var fullBroken = false
    }

    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val routes = ConcurrentHashMap<String, Route>()
    private val counter = AtomicInteger()
    @Volatile private var fullLogged = 0
    @Volatile private var closed = false

    init {
        Thread { accept() }.also { it.isDaemon = true; it.name = "apex-range-proxy" }.start()
    }

    /**
     * O endereço local que serve o mesmo conteúdo de [upstream] (com os [headers] nos pedidos a ele), ou `null` se o endereço não diz o tamanho
     * do arquivo (`clen`), que o VLC precisa saber para pular dentro do vídeo. [full] traz os endereços completos que continuam o arquivo
     * depois do começo que [upstream] entrega.
     */
    fun register(upstream: String, headers: Map<String, String> = emptyMap(), full: Deferred<FullAccess?>? = null): String? {
        val length = Regex("""[?&]clen=(\d+)""").find(upstream)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val mime = Regex("""[?&]mime=([^&]+)""").find(upstream)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { '/' in it } ?: "application/octet-stream"
        val path = "/s${counter.incrementAndGet()}"
        routes[path] = Route(upstream, length, mime, headers, full)
        return "http://127.0.0.1:${server.localPort}$path"
    }

    /** Esquece os endereços (uma reprodução nova começa do zero); o que o VLC ainda tinha aberto de antes falha e ele desiste. */
    fun clear() = routes.clear()

    fun close() {
        closed = true
        routes.clear()
        runCatching { server.close() }
    }

    private fun accept() {
        while (!closed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            Thread { serve(socket) }.also { it.isDaemon = true; it.name = "apex-range-conn" }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                val reader = s.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val out = s.getOutputStream()
                val method = request.substringBefore(' ')
                val route = routes[request.substringAfter(' ').substringBefore(' ').substringBefore('?')]
                if (route == null || (method != "GET" && method != "HEAD")) {
                    out.write("HTTP/1.1 ${if (route == null) "404 Not Found" else "405 Method Not Allowed"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@runCatching
                }
                val span = parseRange(range, route.length)
                if (span == null) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${route.length}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@runCatching
                }
                val (start, end) = span
                val partial = range != null
                val head = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${route.mime}\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
                    if (partial) append("Content-Range: bytes $start-$end/${route.length}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                out.flush()
                if (method == "GET") {
                    Timing.mark("ponte: ${route.mime.substringBefore('/')} pedido $start-$end")
                    stream(route, start, end, out)
                }
            }
        }
    }

    /** O VLC fechou a conexão (pulou para outro ponto do vídeo ou parou): não há mais para quem entregar. */
    private class ClientGone : Exception()

    /** O YouTube negou o pedido (403): na via rápida é o sinal de que o começo liberado do arquivo acabou. */
    private class Denied : IOException("o YouTube negou o pedido (403)")

    /** O endereço completo do arquivo desta rota, se o yt-dlp já respondeu e tem um par para ele; não espera por nada. */
    private fun fullFor(route: Route): String? {
        if (route.fullBroken) return null
        if (!route.fullDecided) {
            val deferred = route.full ?: return null
            if (!deferred.isCompleted) return null
            val access = runCatching { runBlocking { deferred.await() } }.getOrNull()
            route.fullUrl = access?.match(route.url)
            route.fullHeaders = access?.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()
            route.fullDecided = true
        }
        return route.fullUrl
    }

    /** Espera o yt-dlp (até [FULL_WAIT_MS]) e diz se agora há um endereço completo para continuar este arquivo. */
    private fun awaitFull(route: Route): Boolean {
        val deferred = route.full ?: return false
        runCatching { runBlocking { withTimeoutOrNull(FULL_WAIT_MS) { deferred.await() } } }
        return fullFor(route) != null
    }

    /** Onde a entrega parou: um pedaço que falha no meio deixa o que já foi entregue valendo. */
    private class Cursor(var pos: Long)

    /**
     * Busca [start]..[end] do vídeo em pedaços e entrega ao VLC; para quando ele fecha a conexão, quando o arquivo acaba ou quando nenhum dos
     * endereços consegue mais entregar.
     */
    private fun stream(route: Route, start: Long, end: Long, out: OutputStream) {
        val cursor = Cursor(start)
        var failures = 0
        while (cursor.pos <= end && !closed && failures < 2) {
            val full = fullFor(route)
            val url = full ?: route.url
            val headers = if (full != null) route.fullHeaders else route.headers
            val to = min(cursor.pos + (if (full != null) chunk else fastChunk) - 1, end)
            try {
                val t0 = System.currentTimeMillis()
                val from = cursor.pos
                fetch(url, headers, cursor, to, out)
                if (full != null && Timing.enabled && fullLogged++ < 6) Timing.mark("ponte: completo ${route.mime.substringBefore('/')} $from-$to em ${System.currentTimeMillis() - t0} ms")
                failures = 0
            } catch (e: ClientGone) {
                return
            } catch (e: Exception) {
                if (e !is Denied) Timing.mark("ponte: ${if (full != null) "endereço completo" else "via rápida"} falhou em ${cursor.pos}: ${e.message}")
                when {
                    // O endereço completo falhou: volta para o da via rápida (que serve dentro do começo liberado).
                    full != null -> route.fullBroken = true
                    // A via rápida só entrega o começo do arquivo: o resto espera o endereço completo do yt-dlp.
                    e is Denied -> {
                        // Antes de tocar, o VLC espia os últimos bytes do arquivo (o índice `mfra` dos MP4 fragmentados, que os arquivos do
                        // YouTube não têm). A via rápida nega esses bytes e esperar o yt-dlp seguraria a abertura do vídeo por uns 4 s: a
                        // resposta é na hora, com zeros (o VLC vê que não há índice e segue). Se o endereço completo já chegou, vale ele.
                        if (route.length - cursor.pos <= TAIL_PROBE_BYTES && fullFor(route) == null) {
                            Timing.mark("ponte: espiada no fim do arquivo respondida na hora")
                            zeros(out, end - cursor.pos + 1)
                            return
                        }
                        val t0 = System.currentTimeMillis()
                        val ok = awaitFull(route)
                        Timing.mark("ponte: via rápida negou em ${cursor.pos}; esperou o yt-dlp ${System.currentTimeMillis() - t0} ms (${if (ok) "tem endereço completo" else "sem endereço completo"})")
                        if (!ok) failures = 2
                    }
                    // Falha de rede: tenta de novo do ponto em que parou (o que já foi entregue não se repete).
                    else -> failures++
                }
            }
        }
        runCatching { out.flush() }
    }

    private fun zeros(out: OutputStream, count: Long) {
        val block = ByteArray(minOf(count, 8192L).toInt())
        var left = count
        try {
            while (left > 0) {
                val n = minOf(left, block.size.toLong()).toInt()
                out.write(block, 0, n)
                left -= n
            }
            out.flush()
        } catch (_: IOException) {
        }
    }

    private fun fetch(url: String, headers: Map<String, String>, cursor: Cursor, to: Long, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        runBlocking {
            http.prepareGet(url) {
                headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=${cursor.pos}-$to")
                timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 30_000 }
            }.execute { response ->
                val code = response.status.value
                if (code == 403) throw Denied()
                if (code != 206 && code != 200) throw IOException("o YouTube respondeu $code")
                val channel = response.bodyAsChannel()
                while (cursor.pos <= to) {
                    val n = channel.readAvailable(buffer, 0, min(buffer.size.toLong(), to - cursor.pos + 1).toInt())
                    if (n < 0) break
                    if (n == 0) continue
                    try { out.write(buffer, 0, n) } catch (e: IOException) { throw ClientGone() }
                    cursor.pos += n
                }
            }
        }
    }

    companion object {
        /** Quanto cada pedido ao endereço completo busca (o yt-dlp usa pedaços de 10 MB; aqui 4 MB deixam pular no vídeo sem desperdício). */
        const val CHUNK = 4 * 1024 * 1024

        /**
         * Quanto cada pedido à via rápida busca. O YouTube nega o pedido inteiro se o fim dele passa do começo liberado do arquivo (cerca de 1 MB
         * no áudio), então pedaços pequenos aproveitam tudo o que dá antes de o yt-dlp assumir.
         */
        const val FAST_CHUNK = 256 * 1024

        /** Quanto esperar o yt-dlp quando a via rápida já não entrega (ele costuma levar uns 4 s). */
        const val FULL_WAIT_MS = 60_000L

        /** Um pedido que começa nos últimos bytes do arquivo (o VLC lê 16) é a espiada pelo índice do fim, não um pulo de verdade. */
        const val TAIL_PROBE_BYTES = 64L

        /** `bytes=a-b`, `bytes=a-` ou `bytes=-n` num arquivo de [length] bytes → (a, b) já dentro do arquivo; `null` se não dá. Sem cabeçalho, o arquivo todo. */
        internal fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
            if (header == null) return 0L to length - 1
            val m = Regex("""bytes=(\d*)-(\d*)""").find(header) ?: return 0L to length - 1
            val a = m.groupValues[1].toLongOrNull()
            val b = m.groupValues[2].toLongOrNull()
            return when {
                a == null && b != null -> (length - min(b, length)).coerceAtLeast(0) to length - 1
                a != null && a >= length -> null
                a != null -> a to (b?.let { min(it, length - 1) } ?: (length - 1))
                else -> 0L to length - 1
            }?.takeIf { it.first <= it.second }
        }
    }
}
