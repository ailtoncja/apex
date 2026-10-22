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

/**
 * [items] (o resultado de uma busca no canal) na ordem [sort]: pela data de publicação ou pelas visualizações. Quem não informa a data (ou as
 * visualizações) vai para o fim, na ordem em que veio.
 */
fun sortedBy(items: List<Media>, sort: ChannelSort): List<Media> = when (sort) {
    ChannelSort.Recent -> items.sortedWith(compareBy<Media> { it.publishedAt == null }.thenByDescending { it.publishedAt ?: 0 })
    ChannelSort.Oldest -> items.sortedWith(compareBy<Media> { it.publishedAt == null }.thenBy { it.publishedAt ?: 0 })
    ChannelSort.Popular -> items.sortedWith(compareBy<Media> { it.viewCount == null }.thenByDescending { it.viewCount ?: 0 })
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
    /** `false`: veio da abertura rápida (sem legendas nem curtidas); a sessão completa esses dados em segundo plano com o yt-dlp. */
    val complete: Boolean = true,
    /**
     * Os endereços da abertura rápida só entregam o começo de cada arquivo (cerca de um minuto) e só aceitam pedidos de um trecho com começo e
     * fim: o player busca em pedaços por uma ponte local e, passado o começo, no endereço completo do yt-dlp (ver [PlaySource.bridgeRanges]).
     */
    val rangeBridge: Boolean = false,
    /** Live da Twitch aberta por um servidor sem anúncio ([app.apex.source.TWITCH_AD_FREE_SERVERS]): qual. `null` = direto da plataforma. */
    val adFreeServer: String? = null,
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
    /** Os emotes que a plataforma marcou no texto (os das extensões entram na hora de mostrar, ver [app.apex.chat.withExtensionEmotes]). */
    val emotes: List<ChatEmote> = emptyList(),
    /** De qual chat veio (no chat unificado aparece a cor da plataforma ao lado); `null` = não se sabe. */
    val platform: Platform? = null,
)

/** Um emote no texto de uma mensagem: [start] até [end] (sem incluir) do texto vira a imagem [url], com largura/altura [ratio]. */
data class ChatEmote(val start: Int, val end: Int, val name: String, val url: String, val ratio: Float = 1f)

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
    /** O volume da música de fundo (o segundo player, só de som), de 0 a 100; separado do volume do vídeo. */
    val backgroundVolume: Int = 50,
    /** Mostrar no chat os emotes da 7TV, da BetterTTV e da FrankerFaceZ (as extensões que a comunidade da Twitch usa). */
    val chatExtensionEmotes: Boolean = true,
    /**
     * Poupar o PC durante jogos: com o Apex minimizado ou atrás de um programa em tela cheia, a imagem do vídeo é desligada (o som continua);
     * com um jogo em tela cheia em outro monitor, o vídeo vai a até 30 quadros por segundo.
     */
    val saveWhileGaming: Boolean = true,
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
    /** Na Twitch, pedir a lista da live pelos servidores sem anúncio (como o Xtra); se nenhum responder, vai direto. */
    val twitchAdFree: Boolean = true,
    /** Esses servidores (`host/caminho` com `$channel`), na ordem; vazio = a lista padrão ([app.apex.source.TWITCH_AD_FREE_SERVERS]). */
    val twitchAdFreeServers: List<String> = emptyList(),
    /** A lista publicada no repositório (assinada; ver [app.apex.source.fetchAdFreeList]) e a versão dela; vazia = ainda não veio. */
    val twitchAdFreePublished: List<String> = emptyList(),
    val twitchAdFreePublishedVersion: Int = 0,
    /**
     * Os canais do mesmo streamer em plataformas diferentes, cujos chats o app junta (ver [app.apex.chat.LinkedChat]): cada grupo é uma lista
     * de chaves `Plataforma:id` (`Twitch:login`, `Kick:slug`, `YouTube:UC…`).
     */
    val chatLinks: List<List<String>> = emptyList(),
    /** Os pares (`chave|chave`) que o app achou pelo nome e a pessoa disse que não são o mesmo streamer: não são sugeridos de novo. */
    val chatLinkRejected: List<String> = emptyList(),
    /** O tema das cores (id de [app.apex.theme.Themes]): escuro, claro ou um dos dois Subaru. */
    val theme: String = "dark",
    /**
     * Canais (chaves `Plataforma:id`) cujo seguir ou deixar de seguir vale só no Apex, porque a plataforma não aceitou ou não permite (a
     * Twitch não deixa outros apps mudarem isso). A sincronização com a conta não mexe neles até os dois lados concordarem de novo.
     */
    val localFollows: List<String> = emptyList(),
)
