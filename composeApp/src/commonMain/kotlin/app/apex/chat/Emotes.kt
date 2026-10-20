package app.apex.chat

import androidx.compose.runtime.mutableStateMapOf
import app.apex.model.ChatEmote
import app.apex.source.Http
import app.apex.source.get
import app.apex.source.bool
import app.apex.source.int
import app.apex.source.list
import app.apex.source.parseJson
import app.apex.source.str
import app.apex.util.Timing
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/** De onde vem um emote: os da própria plataforma e os das extensões que a comunidade da Twitch usa. */
enum class EmoteProvider(val label: String) { Twitch("Twitch"), SevenTv("7TV"), Bttv("BetterTTV"), Ffz("FrankerFaceZ"), Kick("Kick") }

/** Um emote das extensões (7TV, BetterTTV, FrankerFaceZ): a palavra [name] no chat vira a imagem [url] (largura/altura [ratio]). */
data class Emote(val name: String, val url: String, val ratio: Float = 1f, val provider: EmoteProvider, val animated: Boolean = false)

/**
 * A proporção de cada imagem de emote, aprendida quando ela carrega (a BetterTTV não informa o tamanho, e emotes largos são comuns): o texto
 * da mensagem reserva o espaço certo assim que se sabe.
 */
object EmoteRatios {
    val known = mutableStateMapOf<String, Float>()
}

/** A imagem de um emote da Twitch (o tamanho 2x, nítido em telas com escala; animado quando o emote é). */
fun twitchEmoteUrl(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"

fun kickEmoteUrl(id: String) = "https://files.kick.com/emotes/$id/fullsize"

/**
 * Os emotes da Twitch numa mensagem do chat ao vivo, pela etiqueta `emotes` do IRC (`25:0-4,12-16/1902:6-10`). As posições contam
 * caracteres Unicode (um emoji é um só), e o texto do app conta em UTF-16: a conversão é feita aqui.
 */
fun parseTwitchEmotesTag(tag: String?, text: String): List<ChatEmote> {
    if (tag.isNullOrBlank()) return emptyList()
    // Posição de cada caractere Unicode no texto UTF-16.
    val offsets = IntArray(text.codePointCount(0, text.length) + 1)
    var cp = 0
    var i = 0
    while (i < text.length) {
        offsets[cp++] = i
        i += Character.charCount(text.codePointAt(i))
    }
    offsets[cp] = text.length
    return tag.split('/').flatMap { part ->
        val id = part.substringBefore(':', "").takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
        part.substringAfter(':', "").split(',').mapNotNull { range ->
            val a = range.substringBefore('-').toIntOrNull() ?: return@mapNotNull null
            val b = range.substringAfter('-', "").toIntOrNull() ?: return@mapNotNull null
            if (a < 0 || b < a || b + 1 > cp) return@mapNotNull null
            val start = offsets[a]
            val end = offsets[b + 1]
            ChatEmote(start, end, text.substring(start, end), twitchEmoteUrl(id))
        }
    }.sortedBy { it.start }
}

/** Os emotes de uma mensagem que veio em pedaços (histórico e chat gravado da Twitch): cada pedaço com emote vira um. */
fun emotesFromFragments(fragments: List<Pair<String, String?>>): Pair<String, List<ChatEmote>> {
    val text = StringBuilder()
    val emotes = mutableListOf<ChatEmote>()
    for ((piece, emoteId) in fragments) {
        val start = text.length
        text.append(piece)
        if (emoteId != null && piece.isNotBlank()) emotes += ChatEmote(start, text.length, piece, twitchEmoteUrl(emoteId))
    }
    return text.toString() to emotes
}

/** As figurinhas da Kick vêm no texto como `[emote:1234:nome]`: o texto fica com o nome, e o nome vira a imagem. */
fun parseKickEmotes(raw: String): Pair<String, List<ChatEmote>> {
    val text = StringBuilder()
    val emotes = mutableListOf<ChatEmote>()
    var last = 0
    for (m in KICK_EMOTE.findAll(raw)) {
        text.append(raw, last, m.range.first)
        val start = text.length
        text.append(m.groupValues[2])
        emotes += ChatEmote(start, text.length, m.groupValues[2], kickEmoteUrl(m.groupValues[1]))
        last = m.range.last + 1
    }
    text.append(raw, last, raw.length)
    return text.toString() to emotes
}

private val KICK_EMOTE = Regex("""\[emote:(\d+):([^\]]+)]""")

/**
 * Junta aos emotes que a plataforma marcou ([native]) os das extensões: cada palavra do texto (separada por espaço) que é o nome de um
 * emote de [known] e não cai em cima de um emote já marcado. É o que as extensões fazem no navegador: a palavra exata, maiúsculas contam.
 */
fun withExtensionEmotes(text: String, native: List<ChatEmote>, known: Map<String, Emote>): List<ChatEmote> {
    if (known.isEmpty()) return native
    val out = native.toMutableList()
    var i = 0
    while (i < text.length) {
        while (i < text.length && text[i].isWhitespace()) i++
        val start = i
        while (i < text.length && !text[i].isWhitespace()) i++
        if (start == i) break
        val emote = known[text.substring(start, i)] ?: continue
        if (native.none { start < it.end && i > it.start }) out += ChatEmote(start, i, emote.name, emote.url, emote.ratio)
    }
    return out.sortedBy { it.start }
}

// ------------------------------------------------------------------------------------------------ as listas das extensões

/** 7TV: um conjunto de emotes (o global, ou o `emote_set` de um canal). A imagem 2x em WebP, que anima quando o emote é animado. */
internal fun parse7tvEmotes(set: JsonElement?): List<Emote> = set["emotes"].list().mapNotNull { e ->
    val name = e["name"].str() ?: return@mapNotNull null
    val host = e["data"]["host"]
    val base = host["url"].str() ?: return@mapNotNull null
    val file = host["files"].list().firstOrNull { it["name"].str() == "2x.webp" } ?: host["files"].list().firstOrNull()
    val w = file["width"].int() ?: 0
    val h = file["height"].int() ?: 0
    Emote(
        name, (if (base.startsWith("//")) "https:$base" else base) + "/2x.webp",
        if (w > 0 && h > 0) w.toFloat() / h else 1f, EmoteProvider.SevenTv, e["data"]["animated"].bool() == true,
    )
}

/** BetterTTV: a lista global, ou `channelEmotes` + `sharedEmotes` de um canal. */
internal fun parseBttvEmotes(list: List<JsonElement>): List<Emote> = list.mapNotNull { e ->
    val id = e["id"].str() ?: return@mapNotNull null
    val code = e["code"].str() ?: return@mapNotNull null
    Emote(code, "https://cdn.betterttv.net/emote/$id/2x", 1f, EmoteProvider.Bttv, e["animated"].bool() == true)
}

/** FrankerFaceZ, pela cópia que a BetterTTV mantém (o mesmo formato para os globais e os do canal). */
internal fun parseFfzEmotes(list: List<JsonElement>): List<Emote> = list.mapNotNull { e ->
    val code = e["code"].str() ?: return@mapNotNull null
    val url = e["images"]["2x"].str() ?: e["images"]["1x"].str() ?: return@mapNotNull null
    Emote(code, url, 1f, EmoteProvider.Ffz, e["animated"].bool() == true)
}

/** Os emotes das extensões de um canal, já na ordem em que valem: os do canal antes dos globais, e 7TV, depois BetterTTV, depois FrankerFaceZ. */
class ChannelEmotes(val channel: List<Emote>, val global: List<Emote>) {
    /** O nome de cada emote para a imagem que vale (o primeiro da ordem acima ganha quando dois têm o mesmo nome). */
    val byName: Map<String, Emote> = buildMap { (global.reversed() + channel.reversed()).forEach { put(it.name, it) } }

    companion object {
        val Empty = ChannelEmotes(emptyList(), emptyList())
    }
}

/**
 * Busca os emotes da 7TV, da BetterTTV e da FrankerFaceZ (as extensões que a comunidade da Twitch usa no navegador) para o app mostrar no
 * chat como imagem. Os globais são buscados uma vez; os de cada canal, uma vez por canal enquanto o app está aberto. Uma lista que falha
 * (o canal não tem 7TV, sem internet…) só fica de fora.
 */
class ExtensionEmotes(private val http: HttpClient = Http.client) {
    private val lock = Mutex()
    private var global: CompletableDeferred<List<Emote>>? = null
    private val channels = HashMap<String, CompletableDeferred<ChannelEmotes>>()

    private suspend fun json(url: String): JsonElement? = runCatching { parseJson(http.get(url).bodyAsText()) }.getOrNull()

    private suspend fun globals(): List<Emote> {
        val (deferred, owner) = lock.withLock {
            global?.let { it to false } ?: (CompletableDeferred<List<Emote>>().also { global = it } to true)
        }
        if (!owner) return deferred.await()
        val result = coroutineScope {
            val seven = async { parse7tvEmotes(json("https://7tv.io/v3/emote-sets/global")) }
            val bttv = async { parseBttvEmotes(json("https://api.betterttv.net/3/cached/emotes/global").list()) }
            val ffz = async { parseFfzEmotes(json("https://api.betterttv.net/3/cached/frankerfacez/emotes/global").list()) }
            seven.await() + bttv.await() + ffz.await()
        }
        Timing.mark("emotes: ${result.size} globais das extensões")
        deferred.complete(result)
        // Uma falha geral (sem internet) não fica guardada para sempre: a próxima abertura tenta de novo.
        if (result.isEmpty()) lock.withLock { if (global === deferred) global = null }
        return result
    }

    /** Os emotes das extensões para o canal da Twitch de id [twitchUserId] (o número, não o nome). */
    suspend fun forTwitchChannel(twitchUserId: String): ChannelEmotes {
        val existing = lock.withLock { channels[twitchUserId] }
        if (existing != null) return existing.await()
        val mine = CompletableDeferred<ChannelEmotes>()
        val winner = lock.withLock { channels.getOrPut(twitchUserId) { mine } }
        if (winner !== mine) return winner.await()
        val result = coroutineScope {
            val glob = async { globals() }
            val seven = async { parse7tvEmotes(json("https://7tv.io/v3/users/twitch/$twitchUserId")?.get("emote_set")) }
            val bttv = async {
                val user = json("https://api.betterttv.net/3/cached/users/twitch/$twitchUserId")
                parseBttvEmotes(user["channelEmotes"].list() + user["sharedEmotes"].list())
            }
            val ffz = async { parseFfzEmotes(json("https://api.betterttv.net/3/cached/frankerfacez/users/twitch/$twitchUserId").list()) }
            ChannelEmotes(seven.await() + bttv.await() + ffz.await(), glob.await())
        }
        Timing.mark("emotes: canal $twitchUserId com ${result.channel.size} das extensões")
        mine.complete(result)
        return result
    }
}
