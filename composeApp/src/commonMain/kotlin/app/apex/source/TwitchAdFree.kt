package app.apex.source

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Url

/**
 * Servidores que pedem à Twitch a lista da live de fora (da Europa e da Ásia), onde ela vem sem anúncio: os que o Xtra usa por padrão. Só a
 * lista passa por eles; o vídeo continua vindo direto da Twitch, na mesma qualidade e com o mesmo atraso. `$channel` é trocado pelo canal.
 */
val TWITCH_AD_FREE_SERVERS: List<String> = listOf(
    "eu.luminous.dev/live/\$channel",
    "eu2.luminous.dev/live/\$channel",
    "eu3.luminous.dev/live/\$channel",
    "as.luminous.dev/live/\$channel",
    "lb-eu.cdn-perfprod.com/live/\$channel",
    "lb-eu2.cdn-perfprod.com/live/\$channel",
    "lb-eu3.cdn-perfprod.com/live/\$channel",
    "lb-eu4.cdn-perfprod.com/live/\$channel",
    "lb-eu5.cdn-perfprod.com/live/\$channel",
)

/**
 * A lista dos servidores publicada no repositório do Apex, com a assinatura ao lado (`.sig`, feita com a chave das atualizações): quando um
 * servidor morre ou aparece outro, troca-se este arquivo e os apps pegam a lista nova sozinhos, sem versão nova.
 */
const val AD_FREE_LIST_URL = "https://raw.githubusercontent.com/ailtoncja/apex/main/config/twitch-ad-free.json"

/** A lista publicada: [version] só sobe (uma lista mais velha, mesmo assinada, não substitui a mais nova). */
class AdFreeList(val version: Int, val servers: List<String>)

/** A lista que vale quando a pessoa não escreveu a dela nos Ajustes: a publicada no repositório, senão a que veio no app. */
fun defaultAdFreeServers(settings: app.apex.model.AppSettings): List<String> = settings.twitchAdFreePublished.ifEmpty { TWITCH_AD_FREE_SERVERS }

/** Os servidores a usar: os que a pessoa escreveu nos Ajustes ou, sem eles, [defaultAdFreeServers]. */
fun adFreeServersFor(settings: app.apex.model.AppSettings): List<String> = settings.twitchAdFreeServers.ifEmpty { defaultAdFreeServers(settings) }

/** Teto de servidores numa lista publicada. */
private const val AD_FREE_LIST_MAX = 40

/** Lê a lista publicada (`{"version": 2, "servers": ["host/live/$channel", …]}`); `null` se não é uma lista válida. */
internal fun parseAdFreeList(text: String): AdFreeList? {
    val root = parseJson(text) ?: return null
    val version = root["version"].int() ?: return null
    val servers = root["servers"].list().map { it.str() ?: return null }
    if (version < 1 || servers.isEmpty() || servers.size > AD_FREE_LIST_MAX) return null
    if (servers.any { adFreeMasterUrl(it, "canal") == null }) return null
    return AdFreeList(version, servers)
}

/**
 * Baixa a lista publicada e a assinatura dela, e só devolve a lista se [verify] confirmar que foi assinada pela chave do Apex: ninguém no caminho
 * consegue pôr servidores dele. `null` se não veio, não bate ou não é válida.
 */
suspend fun fetchAdFreeList(http: HttpClient, verify: (ByteArray, String) -> Boolean, url: String = AD_FREE_LIST_URL): AdFreeList? = runCatching {
    val list = http.get(url)
    val sig = http.get("$url.sig")
    if (list.status.value != 200 || sig.status.value != 200) return null
    val bytes = list.bodyAsBytes()
    if (bytes.size > 64 * 1024 || !verify(bytes, sig.bodyAsBytes().decodeToString().trim())) return null
    parseAdFreeList(bytes.decodeToString())
}.getOrNull()

private val TWITCH_LOGIN_CHARS = Regex("[A-Za-z0-9_]{1,25}")

/**
 * Um conjunto de emotes da Twitch (`{ emotes { id token } }`). Ficam de fora os "carinhas" antigos (`:)`, `<3`), que a Twitch guarda como
 * expressões e não como palavras.
 */
internal fun parseTwitchEmoteSet(set: kotlinx.serialization.json.JsonElement?): List<app.apex.chat.Emote> = set["emotes"].list().mapNotNull { e ->
    val id = e["id"].str() ?: return@mapNotNull null
    val token = e["token"].str()?.takeIf { TWITCH_EMOTE_TOKEN.matches(it) } ?: return@mapNotNull null
    app.apex.chat.Emote(token, app.apex.chat.twitchEmoteUrl(id), 1f, app.apex.chat.EmoteProvider.Twitch)
}

private val TWITCH_EMOTE_TOKEN = Regex("[A-Za-z0-9_]{2,64}")

/**
 * O endereço da lista mestra de [login] por [server] (`host/caminho` com `$channel`, com ou sem `https://`), pedindo o mesmo que o Apex pede à
 * Twitch: a qualidade máxima, o "só áudio" e os trechos adiantados (a baixa latência). Só H.264, que o VLC toca em qualquer PC. `null` se o
 * servidor ou o canal não servem.
 */
internal fun adFreeMasterUrl(server: String, login: String): String? {
    if (!TWITCH_LOGIN_CHARS.matches(login)) return null
    val base = server.trim().removePrefix("https://").removePrefix("http://")
    if (base.isEmpty() || "\$channel" !in base || base.any { it.isWhitespace() }) return null
    val url = "https://" + base.replace("\$channel", login.lowercase())
    val separator = if ('?' in url) '&' else '?'
    return url + separator + "allow_source=true&allow_audio_only=true&fast_bread=true&include_unavailable=true&playlist_include_framerate=true" +
        "&supported_codecs=h264"
}

/**
 * A lista que um desses servidores devolveu serve? Tem de ser uma lista mestra, e cada endereço dentro dela (as qualidades e qualquer `URI="…"`)
 * tem de ser da Twitch por https (`*.ttvnw.net`): um servidor de fora não pode mandar o app (nem o VLC) buscar outra coisa.
 */
internal fun trustedTwitchMaster(text: String): Boolean {
    val lines = text.lines().map { it.trim() }
    if (lines.firstOrNull() != "#EXTM3U" || lines.none { it.startsWith("#EXT-X-STREAM-INF") }) return false
    val uris = lines.filter { it.isNotEmpty() && !it.startsWith("#") } +
        lines.flatMap { line -> Regex("""URI="([^"]*)"""").findAll(line).map { it.groupValues[1] }.toList() }
    return uris.isNotEmpty() && uris.all { isTwitchVideoUrl(it) }
}

private fun isTwitchVideoUrl(text: String): Boolean = runCatching {
    val url = Url(text)
    url.protocol.name == "https" && (url.host == "ttvnw.net" || url.host.endsWith(".ttvnw.net"))
}.getOrDefault(false)
