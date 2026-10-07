package app.apex.browser

import app.apex.Cookie
import app.apex.source.get
import app.apex.source.list
import app.apex.source.parseJson
import app.apex.source.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Um navegador Chromium (Chrome, Edge, Brave…) aberto num perfil só do Apex, com uma porta local de controle.
 * O Apex pede os cookies pelo protocolo de depuração do próprio navegador, então não precisa decifrar nada.
 */
class ChromiumSession private constructor(
    private val process: Process,
    private val cdp: CdpConnection,
) : AutoCloseable {
    val isAlive: Boolean get() = process.isAlive

    suspend fun cookies(): List<Cookie> {
        val result = cdp.call("Storage.getCookies")
        return result["cookies"].list().mapNotNull { c ->
            val domain = c["domain"].str() ?: return@mapNotNull null
            val name = c["name"].str() ?: return@mapNotNull null
            val value = c["value"].str() ?: ""
            val path = c["path"].str() ?: "/"
            val secure = (c["secure"] as? JsonPrimitive)?.content == "true"
            val httpOnly = (c["httpOnly"] as? JsonPrimitive)?.content == "true"
            val expires = (c["expires"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }?.toLong() ?: 0L
            val line = "${if (httpOnly) "#HttpOnly_" else ""}$domain\t${if (domain.startsWith(".")) "TRUE" else "FALSE"}\t$path\t${if (secure) "TRUE" else "FALSE"}\t$expires\t$name\t$value"
            Cookie(domain, name, value, line)
        }
    }

    /** Só para testes: grava cookies no navegador. */
    suspend fun setCookies(cookies: List<Map<String, String>>) {
        val array = JsonArray(
            cookies.map { c ->
                JsonObject(c.mapValues { (_, v) -> if (v == "true" || v == "false") JsonPrimitive(v.toBoolean()) else JsonPrimitive(v) })
            },
        )
        cdp.call("Storage.setCookies", buildJsonObject { put("cookies", array) })
    }

    override fun close() {
        runCatching { cdp.sendNoReply("Browser.close") }
        cdp.close()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /** Abre o navegador em [startUrl] com o perfil de [profileDir] e espera a porta de controle responder. */
        suspend fun open(exe: File, profileDir: File, startUrl: String, headless: Boolean = false): ChromiumSession = withContext(Dispatchers.IO) {
            profileDir.mkdirs()
            val port = ServerSocket(0).use { it.localPort }
            val args = buildList {
                add(exe.absolutePath)
                add("--user-data-dir=${profileDir.absolutePath}")
                add("--remote-debugging-port=$port")
                add("--remote-allow-origins=*")
                add("--no-first-run")
                add("--no-default-browser-check")
                if (headless) add("--headless=new")
                add(startUrl)
            }
            val process = ProcessBuilder(args).redirectErrorStream(true).also { it.redirectOutput(ProcessBuilder.Redirect.DISCARD) }.start()
            try {
                val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                var wsUrl: String? = null
                repeat(60) {
                    if (wsUrl == null) {
                        wsUrl = runCatching {
                            val body = http.send(
                                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/version")).timeout(Duration.ofSeconds(1)).build(),
                                HttpResponse.BodyHandlers.ofString(),
                            ).body()
                            parseJson(body)["webSocketDebuggerUrl"].str()
                        }.getOrNull()
                        if (wsUrl == null) delay(500)
                    }
                }
                val url = wsUrl ?: error("O navegador não respondeu na porta de controle.")
                ChromiumSession(process, CdpConnection(url))
            } catch (e: Throwable) {
                process.destroyForcibly()
                throw e
            }
        }
    }
}

/** Cliente mínimo do protocolo de depuração (Chrome DevTools Protocol) por WebSocket. */
class CdpConnection(url: String) : AutoCloseable {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val buffer = StringBuilder()

    private val socket: WebSocket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
        URI(url),
        object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    val json = parseJson(text)
                    val id = (json["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                    if (id != null) pending.remove(id)?.complete(json)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                failAll()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                failAll()
            }
        },
    ).get(10, TimeUnit.SECONDS)

    private fun failAll() {
        pending.values.forEach { it.complete(null) }
        pending.clear()
    }

    /** Chama um método e devolve o campo `result` da resposta. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<JsonElement?>()
        pending[id] = reply
        socket.sendText(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString(), true)
        val response = withTimeout(15_000) { reply.await() } ?: error("O navegador fechou a conexão.")
        response["error"]?.let { error("Erro do navegador: ${it["message"].str()}") }
        return response["result"] ?: JsonObject(emptyMap())
    }

    fun sendNoReply(method: String) {
        socket.sendText(buildJsonObject { put("id", ids.getAndIncrement()); put("method", method) }.toString(), true)
    }

    override fun close() {
        runCatching { socket.abort() }
        failAll()
    }
}
