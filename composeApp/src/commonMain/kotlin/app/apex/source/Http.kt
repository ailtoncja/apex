package app.apex.source

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

object Http {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    val client: HttpClient by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 25_000
                connectTimeoutMillis = 12_000
            }
            install(WebSockets)
            expectSuccess = false
            // Seguir redirecionamentos não vaza login: o HttpRedirect do Ktor (3.x, conferido no 3.6.0) tira o cabeçalho Authorization
            // quando o redirecionamento vai para outro host/porta ("Removing Authorization header for cross-authority redirect") e não
            // segue de https para http. Atenção: os outros cabeçalhos (como Cookie) seguem junto; por isso a Kick só põe o token em pedidos
            // para os hosts dela (ver KickSource.headersFor).
            followRedirects = true
        }
    }
}

suspend fun HttpClient.getText(url: String, headers: Map<String, String> = emptyMap()): String =
    get(url) { headers.forEach { (k, v) -> header(k, v) } }.bodyAsText()

suspend fun HttpClient.postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
    post(url) {
        headers.forEach { (k, v) -> header(k, v) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }.bodyAsText()
