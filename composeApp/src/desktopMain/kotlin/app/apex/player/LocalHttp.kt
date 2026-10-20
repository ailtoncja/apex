package app.apex.player

import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Peças comuns dos servidorzinhos locais que entregam vídeo ao VLC ([RangeProxy] e [LiveEdgeProxy]). Eles só escutam no próprio computador,
 * mas uma página aberta no navegador também está "no próprio computador": por isso cada endereço tem um trecho impossível de adivinhar, o
 * cabeçalho `Host` tem de ser o do próprio servidor (uma página que troca o IP do domínio dela para 127.0.0.1 manda o domínio dela no `Host`) e
 * pedidos e conexões têm teto.
 */
internal object LocalHttp {
    /** Teto de cada linha do pedido (a primeira e cada cabeçalho). */
    const val MAX_LINE = 8 * 1024

    /** Teto de linhas de cabeçalho por pedido. */
    const val MAX_HEADERS = 64

    private val random = SecureRandom()

    /** 128 bits aleatórios em Base64 para endereço (só letras, números, `-` e `_`). */
    fun newToken(): String = ByteArray(16).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** O pedido como chegou: método, caminho (sem a consulta) e cabeçalhos (nomes em minúsculas). */
    class Request(val method: String, val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** Lê a primeira linha e os cabeçalhos; `null` se o pedido é malformado ou passa dos tetos. */
    fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers)
    }

    /** Uma linha terminada em CRLF (ou só LF), em ISO-8859-1; `null` no fim da conexão ou se passa de [MAX_LINE]. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (sb.length >= MAX_LINE) return null
            sb.append(b.toChar())
        }
        if (sb.endsWith('\r')) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /** O `Host` do pedido é o do próprio servidor: `127.0.0.1:porta` ou `localhost:porta` (o VLC manda o primeiro). */
    fun isLocalHost(host: String?, port: Int): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == "127.0.0.1:$port" || h == "localhost:$port"
    }

    /** Resposta sem corpo, fechando a conexão. */
    fun respondEmpty(socket: Socket, status: String) {
        runCatching {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        }
    }

    /** Atende as conexões de [server] com no máximo [maxConnections] ao mesmo tempo; as que passam disso são fechadas na hora. */
    fun acceptLoop(server: ServerSocket, name: String, maxConnections: Int, isClosed: () -> Boolean, serve: (Socket) -> Unit) {
        val n = AtomicInteger()
        val pool = ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "$name-${n.incrementAndGet()}").also { it.isDaemon = true }
        }
        try {
            while (!isClosed()) {
                val socket = try { server.accept() } catch (_: IOException) { return }
                try {
                    pool.execute { serve(socket) }
                } catch (_: RejectedExecutionException) {
                    runCatching { socket.close() }
                }
            }
        } finally {
            pool.shutdown()
        }
    }
}
