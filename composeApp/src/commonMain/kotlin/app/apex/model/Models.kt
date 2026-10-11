package app.apex.model

import kotlinx.coroutines.Deferred
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

/** Prefixo do id dos VODs (transmissões passadas) da Twitch e da Kick, para não se confundirem com o login/slug de um canal ao vivo. */
const val VOD_PREFIX = "vod-"

/** Prefixo do id dos clipes (os três sites): um clipe nunca se confunde com o vídeo, a live ou o VOD de onde saiu. */
const val CLIP_PREFIX = "clip-"

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
    /** Só nos clipes do YouTube: o trecho do vídeo original (em ms) que o clipe mostra. Na Twitch e na Kick o clipe já é um arquivo próprio. */
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
) {
    val key: String get() = "${platform.name}:$id"

    /** VOD da Twitch ou da Kick (o YouTube não precisa: para ele ao vivo e gravado têm o mesmo tipo de id). */
    val isVod: Boolean get() = platform != Platform.YouTube && id.startsWith(VOD_PREFIX)

    val isClip: Boolean get() = id.startsWith(CLIP_PREFIX)

    /** Id do vídeo do YouTube de verdade: num clipe é o do vídeo original (que fica no endereço), não o do clipe. */
    val videoId: String
        get() = if (platform == Platform.YouTube && isClip) url.substringAfter("v=", "").substringBefore('&').ifEmpty { id } else id
}

/** Como ordenar os clipes de um canal. */
enum class ClipSort(val label: String) { Popular("Mais vistos"), Recent("Mais recentes") }

/** Como a lista de vídeos (ou transmissões) de um canal é ordenada, como os filtros "Mais recentes / Populares / Mais antigos" do YouTube. */
enum class ChannelSort(val label: String) { Recent("Mais recentes"), Popular("Mais vistos"), Oldest("Mais antigos") }

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
    /** `false`: veio da abertura rápida (sem legendas nem curtidas); a sessão completa esses dados em segundo plano com o yt-dlp. */
    val complete: Boolean = true,
    /**
     * Os endereços da abertura rápida só entregam o começo de cada arquivo (cerca de um minuto) e só aceitam pedidos de um trecho com começo e
     * fim: o player busca em pedaços por uma ponte local e, passado o começo, no endereço completo do yt-dlp (ver [PlaySource.bridgeRanges]).
     */
    val rangeBridge: Boolean = false,
    /** Os endereços de todos os arquivos do vídeo que o yt-dlp achou (os de [qualities] são só alguns deles). */
    val fileUrls: List<String> = emptyList(),
    /** Na abertura rápida: o resultado do yt-dlp para este mesmo vídeo, que já está a caminho (traz [fileUrls], legendas e curtidas). */
    val pendingFull: Deferred<Result<Resolved>>? = null,
)

data class LiveCategory(val id: String, val name: String, val viewers: Long?, val imageUrl: String?)

/** Uma mensagem do chat gravado, com o ponto do vídeo (em ms) em que ela apareceu. */
data class ReplayMessage(val offsetMs: Long, val message: ChatMessage)

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
    /** Deixa o volume passar de 100% (até 200%, amplificando o som; pode distorcer). Desligado, o volume vai até 100%. */
    val volumeBoost: Boolean = true,
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
    /** Filtros da tela inicial (nomes de HomeSource e de Platform); vazio = tudo. Ficam só neste aparelho. */
    val homeSources: List<String> = emptyList(),
    val homePlatforms: List<String> = emptyList(),
    /** Avisar quando um canal que a pessoa segue entra ao vivo. */
    val liveAlerts: Boolean = true,
    /** Nas lives, começar mais perto do "ao vivo" (menos atraso). Desligar dá mais folga se a internet oscila. */
    val lowLatencyLive: Boolean = true,
    /** O tema das cores (id de [app.apex.theme.Themes]): escuro, claro ou um dos dois Subaru. */
    val theme: String = "dark",
)
