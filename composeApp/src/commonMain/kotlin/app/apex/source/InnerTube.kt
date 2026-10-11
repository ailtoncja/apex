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
        set(value) {
            field = value?.let(CookieHygiene::cleanHeader)
        }

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

    /**
     * Chama a API como outro "aparelho" (ver [InnerClient]): o `player` de cada um entrega formatos diferentes. Com [auth] manda a conta
     * (cookies e assinatura), que só os clientes de navegador aceitam.
     */
    suspend fun callAs(client: InnerClient, endpoint: String, auth: Boolean = false, body: JsonObjectBuilder.() -> Unit): JsonElement? {
        val payload = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", language)
                    put("gl", region)
                    client.extra.forEach { (k, v) -> put(k, v) }
                })
            })
            body()
        }.toString()
        val headers = buildMap {
            put("User-Agent", client.userAgent)
            put("Origin", ORIGIN)
            put("X-Youtube-Client-Name", client.id)
            put("X-Youtube-Client-Version", client.version)
            if (auth) putAll(authHeaders())
        }
        return parseJson(http.postJson("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false", payload, headers))
    }

    companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val CLIENT_VERSION = "2.20260930.01.00"
    }
}

/** O "aparelho" que o app finge ser ao falar com o YouTube: nome, versão, número e `User-Agent` dele, e campos a mais do contexto. */
class InnerClient(
    val name: String, val version: String, val id: String, val userAgent: String,
    val extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
) {
    companion object {
        private fun str(v: String) = kotlinx.serialization.json.JsonPrimitive(v)
        private fun num(v: Int) = kotlinx.serialization.json.JsonPrimitive(v)

        /** O app do iPhone: o `player` dele entrega os endereços diretos dos vídeos, sem o código JavaScript do site e sem login. */
        val Ios = InnerClient(
            "IOS", "20.10.4", "5", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to str("Apple"), "deviceModel" to str("iPhone16,2"), "osName" to str("iPhone"), "osVersion" to str("18.3.2.22D82")),
        )
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

/**
 * Primeiro token de continuação encontrado (para carregar mais resultados). O YouTube tem dois formatos: o antigo
 * (`continuationItemRenderer`) e o novo (`continuationItemViewModel`, com o comando um nível mais fundo). Sem o novo, listas
 * como os vídeos de um canal paravam na primeira página (100 vídeos) por mais que o canal tivesse.
 */
fun JsonElement.continuationToken(): String? =
    collect("continuationItemRenderer", "continuationItemViewModel").firstNotNullOfOrNull { (kind, node) ->
        if (kind == "continuationItemViewModel") node.path("continuationCommand", "innertubeCommand", "continuationCommand", "token").str()
        else node.path("continuationEndpoint", "continuationCommand", "token").str()
    }
