package app.apex.source

import app.apex.model.CLIP_PREFIX
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.VOD_PREFIX

/** O que um link colado na busca aponta. */
sealed interface LinkTarget {
    /** Dá para abrir já: vídeo, VOD, clipe ou live (os detalhes completos vêm quando o player resolve o endereço). */
    data class Play(val media: Media) : LinkTarget

    /** Clipe do YouTube: é preciso ler a página dele para saber de qual vídeo e qual trecho. */
    data class YouTubeClip(val clipId: String) : LinkTarget

    /** Várias lives de uma vez (um link do multitwitch.tv ou do multikick.com): abrem no Multi. */
    data class Multi(val medias: List<Media>) : LinkTarget

    /** Um canal do YouTube (`youtube.com/@handle`, `/@handle/live`, `/channel/UC…`): [ref] é o @handle, o id ou o nome, para resolver depois. */
    data class YouTubeChannel(val ref: String) : LinkTarget
}

private val LINK = Regex("""^(?:https?://)?([a-z0-9.-]+)(/[^?#]*)?(?:\?([^#]*))?""", RegexOption.IGNORE_CASE)
private val YT_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
private val TWITCH_LOGIN = Regex("""^[A-Za-z0-9_]{3,25}$""")
private val KICK_SLUG = Regex("""^[A-Za-z0-9_-]{2,40}$""")

/** Páginas da Twitch que parecem um login de canal mas não são. */
private val TWITCH_RESERVED = setOf(
    "directory", "videos", "downloads", "jobs", "turbo", "p", "store", "drops", "wallet", "settings", "subscriptions", "inventory",
    "friends", "messages", "search", "login", "signup", "prime", "partner", "bits", "popout", "embed", "clips", "u", "team", "about",
)

/** Reconhece links de vídeo, live, VOD e clipe do YouTube, da Twitch e da Kick; qualquer outro texto devolve `null` (vira busca). */
fun parseLink(text: String): LinkTarget? {
    val raw = text.trim()
    if (raw.isEmpty() || raw.any { it.isWhitespace() } || '.' !in raw) return null
    val m = LINK.find(raw) ?: return null
    val host = m.groupValues[1].lowercase().removePrefix("www.").removePrefix("m.")
    val segments = m.groupValues[2].split('/').filter { it.isNotEmpty() }
    val query = m.groupValues[3]
    fun param(name: String): String? =
        query.split('&').firstNotNullOfOrNull { kv -> if (kv.substringBefore('=') == name) kv.substringAfter('=', "").takeIf { it.isNotEmpty() } else null }

    fun youtube(id: String?): LinkTarget? = id?.takeIf { YT_ID.matches(it) }?.let {
        LinkTarget.Play(Media(Platform.YouTube, it, "Carregando…", url = "https://www.youtube.com/watch?v=$it"))
    }

    return when (host) {
        "youtu.be" -> youtube(segments.firstOrNull())
        "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> when (val first = segments.firstOrNull()) {
            "watch" -> youtube(param("v"))
            "live", "embed", "v" -> youtube(segments.getOrNull(1))
            "clip" -> segments.getOrNull(1)?.takeIf { it.length >= 10 }?.let { LinkTarget.YouTubeClip(it) }
            // A página de um canal (com ou sem /live no fim): quem resolve o canal e acha a live é quem abre.
            "channel" -> segments.getOrNull(1)?.takeIf { it.startsWith("UC") && it.length == 24 }?.let { LinkTarget.YouTubeChannel(it) }
            "c", "user" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { LinkTarget.YouTubeChannel(it) }
            null -> null
            else -> if (first.startsWith("@") && first.length > 1) LinkTarget.YouTubeChannel(first) else null
        }
        "twitch.tv" -> when {
            segments.size == 2 && segments[0] == "videos" -> twitchVod(segments[1])
            segments.size == 2 && segments[0] == "clip" -> twitchClip(segments[1])
            segments.size >= 3 && segments[1] == "clip" -> twitchClip(segments[2])
            segments.size == 1 && param("clip") == null && segments[0].lowercase() !in TWITCH_RESERVED && TWITCH_LOGIN.matches(segments[0]) ->
                segments[0].lowercase().let {
                    LinkTarget.Play(Media(Platform.Twitch, it, it, isLive = true, url = "https://www.twitch.tv/$it"))
                }
            else -> null
        }
        "clips.twitch.tv" -> segments.firstOrNull()?.let { twitchClip(it) }
        // multitwitch.tv/a/b/c e multikick.com/a/b: cada trecho do caminho é um canal.
        "multitwitch.tv", "multistre.am" -> segments.mapNotNull { liveFromName(it, Platform.Twitch) }.distinctBy { it.key }.takeIf { it.isNotEmpty() }?.let { LinkTarget.Multi(it) }
        "multikick.com" -> segments.mapNotNull { liveFromName(it, Platform.Kick) }.distinctBy { it.key }.takeIf { it.isNotEmpty() }?.let { LinkTarget.Multi(it) }
        "kick.com" -> when {
            segments.size >= 3 && segments[1] == "videos" -> kickVod(segments[0], segments[2])
            segments.size >= 3 && segments[1] == "clips" -> kickClip(segments[0], segments[2])
            segments.size == 1 && param("clip") != null -> kickClip(segments[0], param("clip")!!)
            segments.size == 1 && segments[0] !in setOf("categories", "search", "browse", "following", "dashboard", "video", "terms", "privacy") &&
                KICK_SLUG.matches(segments[0]) ->
                segments[0].lowercase().let { LinkTarget.Play(Media(Platform.Kick, it, it, isLive = true, url = "https://kick.com/$it")) }
            else -> null
        }
        else -> null
    }
}

private fun twitchVod(id: String): LinkTarget? = id.takeIf { it.all(Char::isDigit) }?.let {
    LinkTarget.Play(Media(Platform.Twitch, VOD_PREFIX + it, "Carregando…", url = "https://www.twitch.tv/videos/$it"))
}

private fun twitchClip(slug: String): LinkTarget? = slug.takeIf { Regex("""^[A-Za-z0-9_-]{8,}$""").matches(it) }?.let {
    LinkTarget.Play(Media(Platform.Twitch, CLIP_PREFIX + it, "Carregando…", url = "https://clips.twitch.tv/$it"))
}

private fun kickVod(channel: String, uuid: String): LinkTarget? =
    uuid.takeIf { Regex("""^[0-9a-fA-F-]{32,40}$""").matches(it) }?.lowercase()?.let {
        LinkTarget.Play(Media(Platform.Kick, VOD_PREFIX + it, "Carregando…", url = "https://kick.com/$channel/videos/$it"))
    }

private fun kickClip(channel: String, id: String): LinkTarget? = id.takeIf { Regex("""^clip_[A-Za-z0-9]+$""").matches(it) }?.let {
    LinkTarget.Play(Media(Platform.Kick, CLIP_PREFIX + it, "Carregando…", url = "https://kick.com/$channel/clips/$it"))
}

/** A live de um canal da Twitch ou da Kick a partir do nome dele (como a pessoa digita no Multi); `null` se o nome não serve. */
fun liveFromName(name: String, platform: Platform): Media? {
    val n = name.trim().removePrefix("@").lowercase()
    return when (platform) {
        Platform.Twitch -> n.takeIf { TWITCH_LOGIN.matches(it) && it !in TWITCH_RESERVED }?.let { Media(Platform.Twitch, it, it, isLive = true, url = "https://www.twitch.tv/$it") }
        Platform.Kick -> n.takeIf { KICK_SLUG.matches(it) }?.let { Media(Platform.Kick, it, it, isLive = true, url = "https://kick.com/$it") }
        Platform.YouTube -> null
    }
}
