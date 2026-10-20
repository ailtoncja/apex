package app.apex.source

import app.apex.model.Channel
import app.apex.model.HistoryEntry
import app.apex.model.LocalPlaylist
import app.apex.model.Media
import app.apex.model.Platform

/*
 * Conferências do que vem "de fora" (sincronização, arquivos do disco, respostas das plataformas) antes de virar endereço, argumento do
 * yt-dlp ou linha do chat. Um item que não passa é descartado; uma imagem que não passa só some.
 */

/** Os hosts que um endereço de vídeo, live ou canal de cada plataforma pode ter. */
private val PLATFORM_HOSTS: Map<Platform, Set<String>> = mapOf(
    Platform.YouTube to setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"),
    Platform.Twitch to setOf("twitch.tv", "www.twitch.tv", "m.twitch.tv", "clips.twitch.tv"),
    Platform.Kick to setOf("kick.com", "www.kick.com"),
)

/** Os hosts da Kick que podem receber o token de sessão da conta. */
private val KICK_AUTH_HOSTS = setOf("kick.com", "www.kick.com", "web.kick.com")

private val YT_CHANNEL_ID = Regex("""^UC[A-Za-z0-9_-]{22}$""")
private val TWITCH_CHAT_LOGIN = Regex("""^[a-z0-9_]{3,25}$""")

/** Tem espaço, quebra de linha ou outro caractere de controle. */
internal fun String.hasUnsafeChars(): Boolean = any { it.isWhitespace() || it.isISOControl() }

/**
 * O host (em minúsculas) de um endereço `https://`, ou `null` se não é https, tem espaço/controle/barra invertida, usuário e senha
 * (`https://kick.com@invasor.com`) ou porta.
 */
fun httpsHost(url: String): String? {
    if (!url.startsWith("https://") || url.hasUnsafeChars() || '\\' in url) return null
    val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority || ':' in authority) return null
    return authority.lowercase()
}

/** Endereço https de um dos sites da [platform] (ver [PLATFORM_HOSTS]). */
fun isPlatformUrl(url: String, platform: Platform): Boolean = httpsHost(url)?.let { it in PLATFORM_HOSTS.getValue(platform) } == true

/** Imagem só por https (miniatura, avatar). */
fun isHttpsUrl(url: String?): Boolean = url != null && httpsHost(url) != null

/** Id que pode ir para um endereço ou para a linha de comando: não vazio, sem espaço nem controle e sem começar com "-". */
fun isSafeId(id: String): Boolean = id.isNotEmpty() && !id.startsWith("-") && !id.hasUnsafeChars()

/**
 * O endereço que pode ir para o yt-dlp (sempre depois de "--"): https de um site do YouTube, sem espaço nem controle. Por começar com
 * `https://`, nunca vira uma opção (como `--exec`). `null` se não serve.
 */
fun ytDlpUrl(url: String): String? = url.trim().takeIf { isPlatformUrl(it, Platform.YouTube) }

/** O id de canal do YouTube (`UC` + 22 caracteres). */
fun isYouTubeChannelId(id: String): Boolean = YT_CHANNEL_ID.matches(id)

/** O login de um canal da Twitch como o chat (IRC) aceita, já em minúsculas; `null` se não serve (evita comandos IRC escondidos no nome). */
fun twitchChatLogin(login: String): String? = login.trim().lowercase().takeIf { TWITCH_CHAT_LOGIN.matches(it) }

/** Tira CR e LF: no IRC cada linha é um comando, e uma quebra no texto viraria um comando a mais. */
fun stripLineBreaks(text: String): String = text.replace("\r", " ").replace("\n", " ")

/** O token de sessão da Kick só vai para os próprios servidores dela (nunca para a CDN ou outro host que a resposta apontar). */
fun isKickAuthUrl(url: String): Boolean = httpsHost(url)?.let { it in KICK_AUTH_HOSTS } == true

// ---------- saneamento dos dados do usuário ----------

/** O canal sem o que não presta (imagem que não é https, endereço de outro site), ou `null` se o próprio id não serve. */
fun Channel.sanitized(): Channel? {
    if (!isSafeId(id)) return null
    val okAvatar = avatarUrl?.takeIf { isHttpsUrl(it) }
    val okUrl = url.takeIf { it.isEmpty() || isPlatformUrl(it, platform) } ?: ""
    return if (okAvatar == avatarUrl && okUrl == url) this else copy(avatarUrl = okAvatar, url = okUrl)
}

/** A mídia, se o endereço é https de um site da plataforma e o id serve; miniatura e avatar que não são https somem. */
fun Media.sanitized(): Media? {
    if (!isSafeId(id) || !isPlatformUrl(url.trim(), platform)) return null
    val okThumb = thumbnailUrl?.takeIf { isHttpsUrl(it) }
    val okChannel = channel?.sanitized()
    return if (okThumb == thumbnailUrl && okChannel == channel) this else copy(thumbnailUrl = okThumb, channel = okChannel)
}

fun HistoryEntry.sanitized(): HistoryEntry? = media.sanitized()?.let { if (it === media) this else copy(media = it) }

fun LocalPlaylist.sanitized(): LocalPlaylist? = if (!isSafeId(id)) null else copy(items = items.sanitizedMedia())

fun List<Media>.sanitizedMedia(): List<Media> = mapNotNull { it.sanitized() }

fun List<Channel>.sanitizedChannels(): List<Channel> = mapNotNull { it.sanitized() }
