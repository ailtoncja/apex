package app.apex.source

import app.apex.util.sha1Hex
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.apex.util.currentTimeMillis

/** Cliente da API interna do YouTube (a mesma que o site usa). Funciona sem login; com cookies, age como a conta. */
class InnerTube(private val http: HttpClient = Http.client) {
    var language: String = "pt"
    var region: String = "BR"

    /** Cookies da conta Google (cabeçalho `Cookie`), quando logado. */
    var cookieHeader: String? = null

    val loggedIn: Boolean get() = cookieHeader?.contains("SAPISID") == true

    private fun cookie(name: String): String? =
        cookieHeader?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun authHeaders(): Map<String, String> {
        val sapisid = cookie("__Secure-3PAPISID") ?: cookie("SAPISID") ?: return emptyMap()
        val ts = currentTimeMillis() / 1000
        val hash = sha1Hex("$ts $sapisid $ORIGIN")
        return mapOf(
            "Authorization" to "SAPISIDHASH ${ts}_$hash",
            "Cookie" to cookieHeader.orEmpty(),
            "X-Goog-AuthUser" to "0",
            "X-Origin" to ORIGIN,
        )
    }

    private fun context(): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", "WEB")
            put("clientVersion", CLIENT_VERSION)
            put("hl", language)
            put("gl", region)
        })
    }

    suspend fun call(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonElement? {
        val payload = buildJsonObject {
            put("context", context())
            body()
        }.toString()
        val headers = buildMap {
            put("User-Agent", Http.UA)
            put("Origin", ORIGIN)
            put("X-Youtube-Client-Name", "1")
            put("X-Youtube-Client-Version", CLIENT_VERSION)
            putAll(authHeaders())
        }
        return parseJson(http.postJson("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false", payload, headers))
    }

    companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val CLIENT_VERSION = "2.20260930.01.00"
    }
}

/** Percorre o JSON em ordem e coleta os blocos cujo nome está em [keys] (sem entrar dentro deles). */
fun JsonElement.collect(vararg keys: String): List<Pair<String, JsonElement>> {
    val wanted = keys.toSet()
    val out = mutableListOf<Pair<String, JsonElement>>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k in wanted) out += k to v else walk(v)
            }
            is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
            else -> {}
        }
    }
    walk(this)
    return out
}

/** Primeiro token de continuação encontrado (para carregar mais resultados). */
fun JsonElement.continuationToken(): String? =
    collect("continuationItemRenderer").firstNotNullOfOrNull {
        it.second.path("continuationEndpoint", "continuationCommand", "token").str()
    }
