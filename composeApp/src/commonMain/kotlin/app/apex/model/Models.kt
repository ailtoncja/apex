package app.apex.model

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val label: String) {
    YouTube("YouTube"),
    Twitch("Twitch"),
    Kick("Kick"),
}

/** O jeito de apoiar um canal pagando: inscrição paga (Twitch) ou membro do canal (YouTube). */
@Serializable
enum class SupportKind(val label: String) { Sub("Sub"), Member("Membro") }

@Serializable
data class Channel(
    val platform: Platform,
    /** YouTube: id do canal (UC…). Twitch: login. Kick: slug. */
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val handle: String? = null,
    val followers: Long? = null,
    val verified: Boolean = false,
    val url: String = "",
    /** Preenchido quando a pessoa paga por este canal (sub ou membro). */
    val support: SupportKind? = null,
) {
    val key: String get() = "${platform.name}:$id"
}

@Serializable
data class Media(
    val platform: Platform,
    /** YouTube: id do vídeo. Twitch/Kick: login/slug do canal ao vivo. */
    val id: String,
    val title: String,
    val channel: Channel? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    /** Visualizações; nas lives, quantos estão assistindo. */
    val viewCount: Long? = null,
    val publishedAt: Long? = null,
    val isLive: Boolean = false,
    val category: String? = null,
    val url: String,
) {
    val key: String get() = "${platform.name}:$id"
}

data class ChannelDetails(
    val channel: Channel,
    val bannerUrl: String? = null,
    val description: String? = null,
)

data class Comment(
    val id: String,
    val author: String,
    val authorAvatar: String?,
    val text: String,
    val likes: Long,
    val pinned: Boolean,
    val timeText: String,
    val isReply: Boolean = false,
)

data class Chapter(val title: String, val startSec: Long)

data class SubtitleTrack(val lang: String, val name: String, val url: String, val auto: Boolean)

/** Uma opção de reprodução. [videoUrl] sozinho quando já traz áudio; com [audioUrl] quando vêm separados. */
data class Quality(
    val label: String,
    val height: Int,
    val videoUrl: String,
    val audioUrl: String? = null,
    val fps: Int? = null,
    val audioOnly: Boolean = false,
)

data class Resolved(
    val media: Media,
    val description: String?,
    val likes: Long?,
    val subscribers: Long?,
    val uploadDate: String?,
    val chapters: List<Chapter>,
    val subtitles: List<SubtitleTrack>,
    val qualities: List<Quality>,
    val userAgent: String?,
    val tags: List<String> = emptyList(),
    val isLive: Boolean = false,
)

data class LiveCategory(val id: String, val name: String, val viewers: Long?, val imageUrl: String?)

data class ChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val color: Long? = null,
    val badges: List<String> = emptyList(),
)

@Serializable
data class HistoryEntry(
    val media: Media,
    val watchedAt: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val items: List<Media> = emptyList(),
)

@Serializable
data class AppSettings(
    val hideShorts: Boolean = true,
    val autoplayNext: Boolean = true,
    val defaultQuality: Int = 0,
    val defaultRate: Float = 1f,
    val volume: Int = 100,
    val rememberPosition: Boolean = true,
    val showChat: Boolean = true,
    val hideMature: Boolean = true,
    /** Endereço do servidor de contas do Apex; vazio = o padrão do app. */
    val serverUrl: String = "",
    val sidebarCollapsed: Boolean = false,
    val subtitleLang: String = "pt",
    val subtitlesOnByDefault: Boolean = false,
    val preferHardwareDecode: Boolean = true,
    /** Baixar as atualizações do Apex sozinho (só vale instalar quando a pessoa clicar em reiniciar). */
    val autoUpdate: Boolean = true,
    val kickLanguage: String = "pt",
    val twitchLanguage: String = "PT",
    val blockedChannels: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)
