package app.apex.source

import kotlinx.serialization.json.buildJsonObject
import app.apex.model.ReplayMessage
import app.apex.model.CLIP_PREFIX
import app.apex.model.Channel
import app.apex.model.ChannelSort
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.util.currentTimeMillis
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class VideoPage(val items: List<Media>, val continuation: String?)

data class RemotePlaylist(val id: String, val title: String, val thumbnailUrl: String?, val countText: String?)

data class PlaylistPage(val items: List<RemotePlaylist>, val continuation: String?)

/** O cabeçalho de uma playlist do YouTube: dono, contagem, visualizações, "atualizado há…" e descrição. */
data class PlaylistInfo(
    val title: String?,
    val owner: Channel?,
    /** Por exemplo: "30 vídeos", "2.341 visualizações", "Atualizado há 3 dias". */
    val stats: List<String>,
    val description: String?,
)

/** A primeira página de uma playlist traz também o cabeçalho ([info]); as seguintes só os vídeos. */
class PlaylistContents(val info: PlaylistInfo?, val page: VideoPage)

data class PlaylistOption(val id: String, val title: String, val contains: Boolean)

sealed interface LiveChatStart {
    data class Ready(val token: String) : LiveChatStart
    data class Unavailable(val message: String) : LiveChatStart
}

data class SearchPage(val videos: List<Media>, val channels: List<Channel>, val continuation: String?, val playlists: List<RemotePlaylist> = emptyList())

enum class SearchSort(val label: String, val code: Int) {
    Relevance("Relevância", 0), UploadDate("Data de envio", 2), Views("Visualizações", 3), Rating("Avaliação", 1)
}

enum class SearchDate(val label: String, val code: Int) {
    Any("Qualquer data", 0), Hour("Última hora", 1), Today("Hoje", 2), Week("Esta semana", 3), Month("Este mês", 4), Year("Este ano", 5)
}

enum class SearchDuration(val label: String, val code: Int) {
    Any("Qualquer duração", 0), Short("Menos de 4 min", 1), Medium("4 a 20 min", 3), Long("Mais de 20 min", 2)
}

enum class SearchType(val label: String, val code: Int) { Any("Tudo", 0), Video("Vídeo", 1), Channel("Canal", 2), Playlist("Playlist", 3) }

/** Os "Recursos" do filtro de pesquisa do YouTube. [tag] é o campo do filtro codificado no parâmetro da busca. */
enum class SearchFeature(val label: String, val tag: List<Int>) {
    HD("HD", listOf(0x20)),
    Subtitles("Legendas/CC", listOf(0x28)),
    CreativeCommons("Creative Commons", listOf(0x30)),
    FourK("4K", listOf(0x70)),
    Vr360("360°", listOf(0x78)),
    HDR("HDR", listOf(0xC8, 0x01)),
}

data class SearchFilters(
    val sort: SearchSort = SearchSort.Relevance,
    val date: SearchDate = SearchDate.Any,
    val duration: SearchDuration = SearchDuration.Any,
    val liveOnly: Boolean = false,
    val channelsOnly: Boolean = false,
    val type: SearchType = SearchType.Video,
    val features: Set<SearchFeature> = emptySet(),
) {
    /** Quantos filtros estão ligados (para o botão "Filtros"). */
    val activeCount: Int
        get() = listOf(sort != SearchSort.Relevance, date != SearchDate.Any, duration != SearchDuration.Any, liveOnly, type != SearchType.Any).count { it } + features.size
}

/** Parâmetro do `browse` da busca dentro de um canal (o mesmo para todos; o texto vai no campo `query`). */
private const val CHANNEL_SEARCH_TAB = "EgZzZWFyY2jyBgQKAloA"

/** Parâmetro do `browse` que abre a aba "Playlists" de um canal (o mesmo para todos os canais). */
private const val PLAYLISTS_TAB = "EglwbGF5bGlzdHPyBgQKAkIA"

/** As abas do canal: o prefixo da lista de envios do YouTube (`UULF…` = vídeos, `UULV…` = transmissões) e o parâmetro que abre a aba. */
enum class ChannelTab(val prefix: String, val params: String) {
    Videos("UULF", "EgZ2aWRlb3PyBgQKAjoA"),
    Lives("UULV", "EgdzdHJlYW1z8gYECgJ6AA=="),
}

/** Quem consegue publicar comentários num vídeo (separado para os testes trocarem por um falso). */
interface CommentPoster {
    suspend fun commentParams(videoId: String): String?
    suspend fun postComment(params: String, text: String): PostResult
}

/** O que aconteceu ao publicar: o YouTube confirmou, recusou, ou respondeu de um jeito que não dá para saber (pode ter publicado). */
enum class PostResult { Posted, Rejected, Unclear }

/** A conta salva deixou de valer na plataforma (a pessoa precisa entrar de novo). */
class SessionExpiredException(message: String) : Exception(message)

class YouTubeSource(val tube: InnerTube) : CommentPoster {
    private val gate = Semaphore(6)

    // ---------- busca ----------

    @OptIn(ExperimentalEncodingApi::class)
    internal fun searchParams(f: SearchFilters): String? {
        val filters = mutableListOf<Int>()
        if (f.date.code > 0) filters += listOf(0x08, f.date.code)
        val type = if (f.channelsOnly) SearchType.Channel.code else f.type.code
        if (type > 0) filters += listOf(0x10, type)
        if (f.duration.code > 0) filters += listOf(0x18, f.duration.code)
        if (f.liveOnly) filters += listOf(0x40, 1)
        f.features.forEach { filters += f.tagFor(it) + 1 }
        val bytes = mutableListOf<Int>()
        if (f.sort.code > 0) bytes += listOf(0x08, f.sort.code)
        bytes += listOf(0x12, filters.size) + filters
        return Base64.encode(ByteArray(bytes.size) { bytes[it].toByte() })
    }

    private fun SearchFilters.tagFor(feature: SearchFeature): List<Int> = feature.tag

    suspend fun search(query: String, filters: SearchFilters = SearchFilters(), continuation: String? = null): SearchPage {
        val root = tube.call("search") {
            if (continuation != null) put("continuation", continuation)
            else {
                put("query", query)
                searchParams(filters)?.let { put("params", it) }
            }
        } ?: return SearchPage(emptyList(), emptyList(), null)
        val videos = parseVideos(root)
        val channels = root.collect("channelRenderer").mapNotNull { parseChannelRenderer(it.second) }
        return SearchPage(videos, channels, root.continuationToken(), parsePlaylists(root))
    }

    suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val text = Http.client.getText(
                "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&hl=${tube.language}&gl=${tube.region}&q=${query.encodeURLParameter()}",
                mapOf("User-Agent" to Http.UA),
            )
            // Resposta no formato `window.google.ac.h([...])`.
            val json = parseJson(text.substringAfter('(').substringBeforeLast(')'))
            json[1].list().mapNotNull { it[0].str() }
        }.getOrDefault(emptyList())
    }

    // ---------- canais ----------

    /**
     * Os vídeos (ou as transmissões) de um canal. Do mais novo para o mais velho vem da lista de envios do canal (100 por página); "Mais vistos"
     * e "Mais antigos" são as ordens que o próprio YouTube oferece na aba do canal (30 por página), pedidas pelo token de cada uma.
     */
    suspend fun channelVideos(
        channelId: String, tab: ChannelTab = ChannelTab.Videos, continuation: String? = null, sort: ChannelSort = ChannelSort.Recent,
    ): VideoPage {
        var token = continuation
        if (token == null && sort != ChannelSort.Recent) {
            token = channelSortToken(channelId, tab, sort)
                ?: error("O YouTube não deixou ordenar por \"${sort.label.lowercase()}\" agora. Tente de novo mais tarde.")
        }
        val root = tube.call("browse") {
            if (token != null) put("continuation", token)
            else put("browseId", "VL" + tab.prefix + channelId.removePrefix("UC"))
        } ?: return VideoPage(emptyList(), null)
        return VideoPage(parseVideos(root), root.continuationToken())
    }

    /** O token que abre a lista do canal na ordem [sort] (o YouTube o dá na própria aba, junto das ordens "Mais recentes", "Em alta" e "Mais antigos"). */
    private suspend fun channelSortToken(channelId: String, tab: ChannelTab, sort: ChannelSort): String? {
        val root = tube.call("browse") {
            put("browseId", channelId)
            put("params", tab.params)
        } ?: return null
        return sortTokens(root).getOrNull(sort.ordinal)
    }

    /**
     * Procura [query] nos vídeos do canal inteiro (a busca do próprio YouTube dentro do canal, que acha vídeos de qualquer época, não só os
     * já carregados na aba). As páginas seguintes vêm pela [continuation].
     */
    suspend fun channelSearch(channelId: String, query: String, continuation: String? = null): VideoPage {
        val root = tube.call("browse") {
            if (continuation != null) put("continuation", continuation)
            else {
                put("browseId", channelId)
                put("params", CHANNEL_SEARCH_TAB)
                put("query", query)
            }
        } ?: return VideoPage(emptyList(), null)
        return VideoPage(parseVideos(root), root.continuationToken())
    }

    /** A live no ar neste momento no canal, se houver. */
    suspend fun channelLive(channelId: String): Media? =
        channelVideos(channelId, ChannelTab.Lives).items.firstOrNull { it.isLive }

    /** O id (UC…) do canal com este @handle; `null` se não existe. */
    suspend fun channelIdByHandle(handle: String): String? {
        val root = tube.call("navigation/resolve_url") { put("url", "https://www.youtube.com/@${handle.trim().removePrefix("@")}") } ?: return null
        return root.path("endpoint", "browseEndpoint", "browseId").str()?.takeIf { isChannelId(it) }
    }

    /**
     * O id do canal a partir do que a pessoa escreveu: o próprio id (UC…), um @handle, ou um nome (aí vale o primeiro canal que a busca acha).
     * `null` se não há canal assim.
     */
    suspend fun findChannelId(ref: String): String? {
        val r = ref.trim()
        if (isChannelId(r)) return r
        val handle = r.removePrefix("@")
        if (HANDLE.matches(handle)) channelIdByHandle(handle)?.let { return it }
        if (r.startsWith("@")) return null
        return search(r, SearchFilters(channelsOnly = true)).channels.firstOrNull()?.id
    }

    private fun isChannelId(s: String) = s.length == 24 && s.startsWith("UC")

    // ---------- relacionados ----------

    suspend fun related(videoId: String): List<Media> {
        val root = tube.call("next") { put("videoId", videoId) } ?: return emptyList()
        return parseVideos(root).filter { it.id != videoId }
    }

    // ---------- feed de inscrições ----------

    suspend fun feed(channels: List<Channel>, perChannel: Int = 5, nowMs: Long = currentTimeMillis()): List<Media> = coroutineScope {
        channels.map { ch ->
            async {
                gate.withPermit {
                    runCatching { channelVideos(ch.id).items.take(perChannel) }.getOrDefault(emptyList())
                        .map { m -> m.copy(channel = (m.channel ?: ch).copy(avatarUrl = m.channel?.avatarUrl ?: ch.avatarUrl)) }
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.publishedAt ?: 0L }
    }

    // ---------- conta (precisa de login) ----------

    /** Recomendados da página inicial; só devolve algo se houver conta logada. */
    suspend fun accountHome(continuation: String? = null): VideoPage {
        val root = tube.call("browse") {
            if (continuation != null) put("continuation", continuation) else put("browseId", "FEwhat_to_watch")
        } ?: return VideoPage(emptyList(), null)
        return VideoPage(parseVideos(root), root.continuationToken())
    }

    /**
     * Clipes que a conta criou (a página "Seus clipes" do YouTube, `FEclips`). O YouTube não tem lista pública de clipes por canal:
     * só os da própria conta, ou qualquer clipe aberto pelo link ([clip]).
     */
    suspend fun myClips(): List<Media> {
        val root = tube.call("browse") { put("browseId", "FEclips") } ?: return emptyList()
        val now = currentTimeMillis()
        return root.collect("gridVideoRenderer").mapNotNull { clipItemToMedia(it.second, now) }.distinctBy { it.id }
    }

    /** Um clipe aberto pelo link (`youtube.com/clip/…`): a página dele diz de qual vídeo é e qual trecho mostra. */
    suspend fun clip(clipId: String): Media? {
        val html = Http.client.getText("https://www.youtube.com/clip/${clipId.encodeURLParameter()}", mapOf("User-Agent" to Http.UA, "Accept-Language" to "pt-BR,pt;q=0.9"))
        return clipFromPage(clipId, html)
    }

    internal fun clipFromPage(clipId: String, html: String): Media? {
        val marker = "var ytInitialData = "
        val from = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val to = html.indexOf(";</script>", from).takeIf { it > from } ?: return null
        val data = parseJson(html.substring(from, to)) ?: return null
        val videoId = data.path("currentVideoEndpoint", "watchEndpoint", "videoId").str() ?: return null
        val block = Regex(""""clipConfig":\{([^}]*)\}""").find(html)?.groupValues?.get(1) ?: return null
        fun field(name: String) = Regex(""""$name":"(\d+)"""").find(block)?.groupValues?.get(1)?.toLongOrNull()
        val start = field("startTimeMs")
        val end = field("endTimeMs")
        if (start == null || end == null || end <= start) return null
        val attribution = data.collect("clipAttributionRenderer").firstOrNull()?.second
        val owner = data.collect("videoOwnerRenderer").firstOrNull()?.second
        val ownerName = owner["title"]["runs"][0]["text"].str()
        val ownerId = owner.path("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").str()
        val title = attribution["title"]["runs"][0]["text"].str()
            ?: data.collect("videoPrimaryInfoRenderer").firstOrNull()?.second["title"]["runs"][0]["text"].str() ?: "Clipe"
        // "8 visualizações · há 4 anos": as visualizações e a idade são do clipe, não do vídeo de onde ele saiu.
        val created = attribution["createdText"]["simpleText"].str()?.split('·')?.map { it.trim() }.orEmpty()
        val views = created.firstOrNull()?.let { parseCountText(it) }
        val published = created.getOrNull(1)?.let { parsePublished(it, currentTimeMillis()) }
        return Media(
            platform = Platform.YouTube, id = CLIP_PREFIX + clipId, title = title,
            channel = ownerId?.let {
                Channel(
                    Platform.YouTube, it, ownerName.orEmpty(), owner["thumbnail"]["thumbnails"].list().lastOrNull()?.get("url").str().httpsUrl(),
                    url = "https://www.youtube.com/channel/$it",
                )
            },
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            durationSec = (end - start) / 1000, viewCount = views, publishedAt = published,
            url = "https://www.youtube.com/watch?v=$videoId", clipStartMs = start, clipEndMs = end,
        )
    }

    internal fun clipItemToMedia(v: JsonElement, now: Long): Media? {
        val config = v.path("navigationEndpoint", "watchEndpoint", "watchEndpointClipConfig", "clipConfig") ?: return null
        val postId = config["postId"].str() ?: return null
        val videoId = v["videoId"].str() ?: return null
        val start = config["startTimeMs"].str()?.toLongOrNull() ?: return null
        val end = config["endTimeMs"].str()?.toLongOrNull()?.takeIf { it > start } ?: return null
        val title = v["title"]["runs"][0]["text"].str() ?: v["title"]["simpleText"].str() ?: "Clipe"
        // "de Fulano": o nome é o do canal do vídeo de onde o clipe saiu (a lista não traz o endereço do canal).
        val byline = v["longBylineText"]["runs"].list().mapNotNull { it["text"].str() }.filter { it.isNotBlank() && it.trim() != "de" }.lastOrNull()
        val created = v["publishedTimeText"]["runs"].list().lastOrNull()["text"].str() ?: v["publishedTimeText"]["simpleText"].str()
        val views = v["viewCountText"]["simpleText"].str() ?: v["viewCountText"]["runs"].list().joinToString("") { it["text"].str().orEmpty() }.ifBlank { null }
        return Media(
            platform = Platform.YouTube, id = CLIP_PREFIX + postId, title = title,
            channel = byline?.let { Channel(Platform.YouTube, "", it) },
            thumbnailUrl = pickThumb(v["thumbnail"]["thumbnails"].list().mapNotNull { t -> t["url"].str()?.let { it to (t["width"].int() ?: 0) } }, videoId),
            durationSec = (end - start) / 1000, viewCount = parseCountText(views), publishedAt = parsePublished(created, now),
            url = "https://www.youtube.com/watch?v=$videoId", clipStartMs = start, clipEndMs = end,
        )
    }

    /** Vídeos de uma lista da conta: histórico (`FEhistory`), assistir depois (`VLWL`), curtidos (`VLLL`). */
    suspend fun accountList(browseId: String, continuation: String? = null): VideoPage {
        val root = tube.call("browse") {
            if (continuation != null) put("continuation", continuation) else put("browseId", browseId)
        } ?: return VideoPage(emptyList(), null)
        val items = parseVideos(root) + root.collect("playlistVideoRenderer").mapNotNull { videoRendererToMedia(it.second, currentTimeMillis()) }
        return VideoPage(items.distinctBy { it.id }, root.continuationToken())
    }

    /** Canais em que a conta está inscrita. */
    suspend fun subscribedChannels(): List<Channel> {
        val root = tube.call("browse") { put("browseId", "FEchannels") } ?: return emptyList()
        // Sessão vencida: o YouTube devolve só o cabeçalho, sem conteúdo. Melhor avisar do que importar "0 canais" calado.
        if (root["contents"] == null) throw SessionExpiredException("Sua sessão do YouTube expirou. Saia da conta e entre de novo.")
        val first = root.collect("channelRenderer", "gridChannelRenderer").mapNotNull { parseChannelRenderer(it.second) }
        val extra = mutableListOf<Channel>()
        var token = root.continuationToken()
        var guard = 0
        while (token != null && guard++ < 10) {
            val next = tube.call("browse") { put("continuation", token!!) } ?: break
            extra += next.collect("channelRenderer", "gridChannelRenderer").mapNotNull { parseChannelRenderer(it.second) }
            token = next.continuationToken()
        }
        return (first + extra).distinctBy { it.id }
    }

    /**
     * Canais dos quais a conta é membro agora (a página "Compras e assinaturas" do YouTube). O YouTube não informa o id do canal nessa
     * página, só o nome e a foto, então o canal é achado entre as inscrições ([known]); membro de um canal em que não está inscrito não aparece.
     */
    suspend fun activeMemberships(known: List<Channel>): List<Channel> {
        val root = tube.call("browse") { put("browseId", "FEmemberships_and_purchases") } ?: return emptyList()
        if (root["contents"] == null) throw SessionExpiredException("Sua sessão do YouTube expirou. Saia da conta e entre de novo.")
        return parseActiveMemberships(root, known)
    }

    /** Playlists que a conta criou ou salvou. */
    suspend fun accountPlaylists(): List<RemotePlaylist> {
        val root = tube.call("browse") { put("browseId", "FEplaylist_aggregation") } ?: return emptyList()
        return parsePlaylists(root).filter { it.id != "WL" && it.id != "LL" }
    }

    /** Playlists públicas de um canal (a aba "Playlists" dele). */
    suspend fun channelPlaylists(channelId: String, continuation: String? = null): PlaylistPage {
        val root = tube.call("browse") {
            if (continuation != null) put("continuation", continuation)
            else {
                put("browseId", channelId)
                put("params", PLAYLISTS_TAB)
            }
        } ?: return PlaylistPage(emptyList(), null)
        return PlaylistPage(parsePlaylists(root), root.continuationToken())
    }

    /** Todos os vídeos de uma playlist (percorre as páginas, até [limit]). */
    suspend fun playlistAll(id: String, limit: Int = 1000): List<Media> {
        val out = mutableListOf<Media>()
        var token: String? = null
        var pages = 0
        do {
            val page = playlistVideos(id, token)
            out += page.items
            token = page.continuation
        } while (token != null && out.size < limit && ++pages < 60)
        return out.distinctBy { it.id }.take(limit)
    }

    internal fun parsePlaylists(root: JsonElement): List<RemotePlaylist> {
        val out = mutableListOf<RemotePlaylist>()
        for ((key, node) in root.collect("lockupViewModel", "gridPlaylistRenderer", "playlistRenderer")) {
            if (key == "lockupViewModel") {
                if (node["contentType"].str() != "LOCKUP_CONTENT_TYPE_PLAYLIST") continue
                val id = node["contentId"].str() ?: continue
                val title = node["metadata"]["lockupMetadataViewModel"]["title"]["content"].str() ?: continue
                val thumb = node["contentImage"]?.collect("image")?.firstNotNullOfOrNull { it.second["sources"][0]["url"].str() }
                val count = node["contentImage"]?.collect("thumbnailOverlayBadgeViewModel", "thumbnailBadgeViewModel")
                    ?.firstNotNullOfOrNull { it.second["text"].str() ?: it.second.collect("text").firstNotNullOfOrNull { t -> t.second.str() } }
                out += RemotePlaylist(id, title, thumb, count)
            } else {
                val id = node["playlistId"].str() ?: continue
                val title = node["title"]["simpleText"].str() ?: node["title"]["runs"][0]["text"].str() ?: continue
                out += RemotePlaylist(id, title, node["thumbnail"]["thumbnails"].list().lastOrNull()?.get("url").str(), node["videoCountText"]["runs"][0]["text"].str())
            }
        }
        return out.distinctBy { it.id }
    }

    suspend fun playlistVideos(id: String, continuation: String? = null): VideoPage = accountList("VL$id", continuation)

    /** Como [playlistVideos], mas na primeira página devolve também o cabeçalho da playlist. */
    suspend fun playlist(id: String, continuation: String? = null): PlaylistContents {
        val root = tube.call("browse") {
            if (continuation != null) put("continuation", continuation) else put("browseId", "VL$id")
        } ?: return PlaylistContents(null, VideoPage(emptyList(), null))
        val items = parseVideos(root) + root.collect("playlistVideoRenderer").mapNotNull { videoRendererToMedia(it.second, currentTimeMillis()) }
        return PlaylistContents(if (continuation == null) parsePlaylistInfo(root) else null, VideoPage(items.distinctBy { it.id }, root.continuationToken()))
    }

    internal fun parsePlaylistInfo(root: JsonElement): PlaylistInfo? {
        val header = root.path("header", "pageHeaderRenderer", "content", "pageHeaderViewModel")
        val sidebar = root.collect("playlistSidebarPrimaryInfoRenderer").firstOrNull()?.second
        val title = header.path("title", "dynamicTextViewModel", "text", "content").str() ?: sidebar.path("title", "runs", 0, "text").str()
        fun runs(e: JsonElement?) = e["runs"].list().joinToString("") { it["text"].str().orEmpty() }.trim().ifBlank { e["simpleText"].str() }
        val stats = sidebar["stats"].list().mapNotNull { runs(it) }.ifEmpty {
            header.path("metadata", "contentMetadataViewModel", "metadataRows", 1, "metadataParts").list()
                .mapNotNull { it["text"]["content"].str() }.filter { it != "Playlist" }
        }
        val ownerRenderer = root.collect("videoOwnerRenderer").firstOrNull()?.second
        val ownerName = ownerRenderer.path("title", "runs", 0, "text").str()
            ?: header.path("metadata", "contentMetadataViewModel", "metadataRows", 0, "metadataParts", 0, "avatarStack", "avatarStackViewModel", "text", "content").str()?.removePrefix("por ")
        val ownerId = ownerRenderer.path("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").str()
            ?: ownerRenderer?.collect("browseEndpoint")?.firstNotNullOfOrNull { it.second["browseId"].str() }
        val description = header.path("description", "descriptionPreviewViewModel", "description", "content").str()
            ?: sidebar.path("description", "simpleText").str()
        if (title == null && ownerName == null && stats.isEmpty()) return null
        return PlaylistInfo(
            title, ownerName?.let { Channel(Platform.YouTube, ownerId.orEmpty(), it, url = ownerId?.let { id -> "https://www.youtube.com/channel/$id" }.orEmpty()) },
            stats, description?.takeIf { it.isNotBlank() },
        )
    }

    /** Em quais playlists da conta o vídeo já está (para o diálogo "Salvar em…"). */
    suspend fun playlistOptions(videoId: String): List<PlaylistOption> {
        val root = tube.call("playlist/get_add_to_playlist") { put("videoId", videoId); put("excludeWatchLater", false) } ?: return emptyList()
        return root.collect("playlistAddToOptionRenderer").mapNotNull { (_, n) ->
            val id = n["playlistId"].str() ?: return@mapNotNull null
            val title = n["title"]["simpleText"].str() ?: return@mapNotNull null
            PlaylistOption(id, title, n["containsSelectedVideos"].str() == "ALL")
        }
    }

    suspend fun editPlaylist(playlistId: String, videoId: String, add: Boolean): Boolean {
        if (!tube.loggedIn) return false
        val action = kotlinx.serialization.json.buildJsonObject {
            if (add) { put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", videoId) }
            else { put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID"); put("removedVideoId", videoId) }
        }
        return tube.call("browse/edit_playlist") {
            put("playlistId", playlistId)
            put("actions", kotlinx.serialization.json.buildJsonArray { add(action) })
        } != null
    }

    suspend fun createPlaylist(title: String, videoId: String?): String? {
        if (!tube.loggedIn) return null
        val root = tube.call("playlist/create") {
            put("title", title)
            put("privacyStatus", "PRIVATE")
            if (videoId != null) put("videoIds", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(videoId)) })
        }
        return root["playlistId"].str()
    }

    /** Nome e foto da conta Google logada. */
    suspend fun accountInfo(): Pair<String, String?>? {
        val root = tube.call("account/account_menu") { } ?: return null
        val header = root.collect("activeAccountHeaderRenderer").firstOrNull()?.second ?: return null
        val name = header["accountName"]["simpleText"].str() ?: return null
        val photo = header["accountPhoto"]["thumbnails"].list().lastOrNull()?.get("url").str().httpsUrl()
        return name to photo
    }

    suspend fun rate(videoId: String, value: Int): Boolean {
        if (!tube.loggedIn) return false
        val endpoint = when (value) { 1 -> "like/like"; -1 -> "like/dislike"; else -> "like/removelike" }
        return tube.call(endpoint) { put("target", kotlinx.serialization.json.buildJsonObject { put("videoId", videoId) }) } != null
    }

    suspend fun setSubscribed(channelId: String, subscribe: Boolean): Boolean {
        if (!tube.loggedIn) return false
        val endpoint = if (subscribe) "subscription/subscribe" else "subscription/unsubscribe"
        return tube.call(endpoint) {
            put("channelIds", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(channelId)) })
            if (subscribe) put("params", "EgIIAhgA")
        } != null
    }

    // ---------- comentar ----------

    /**
     * O que o YouTube exige para aceitar um comentário novo neste vídeo (`createCommentParams`), ou `null` se a conta não está logada ou o vídeo
     * tem os comentários desligados. O código vem do cabeçalho da seção de comentários: a página do vídeo só traz a continuação dela.
     */
    override suspend fun commentParams(videoId: String): String? {
        if (!tube.loggedIn) return null
        val watch = tube.call("next") { put("videoId", videoId) } ?: return null
        val token = commentsContinuation(watch) ?: return null
        val header = tube.call("next") { put("continuation", token) } ?: return null
        return createCommentParams(header)
    }

    internal fun commentsContinuation(watchRoot: JsonElement): String? =
        watchRoot.collect("itemSectionRenderer").map { it.second }
            .firstOrNull { it["sectionIdentifier"].str() == "comment-item-section" }
            ?.continuationToken()

    internal fun createCommentParams(headerRoot: JsonElement): String? =
        headerRoot.collect("createCommentEndpoint").firstNotNullOfOrNull { it.second["createCommentParams"].str() }

    /** Publica um comentário (de primeiro nível) como a conta logada. */
    override suspend fun postComment(params: String, text: String): PostResult {
        if (!tube.loggedIn || text.isBlank()) return PostResult.Rejected
        val root = tube.call("comment/create_comment") {
            put("commentText", text)
            put("createCommentParams", params)
        } ?: return PostResult.Rejected
        return classifyPostResponse(root)
    }

    /**
     * A criação bem-sucedida traz a ação `createCommentAction` (com o comentário novo) ou o status de sucesso; erro e status de falha são recusa.
     * Qualquer outra forma de resposta fica como [PostResult.Unclear]: pode ter publicado, e repetir o envio duplicaria o comentário.
     */
    internal fun classifyPostResponse(root: JsonElement): PostResult {
        if (root["error"] != null) return PostResult.Rejected
        val status = root["actionResult"]["status"].str()
        return when {
            root.collect("createCommentAction").isNotEmpty() || status == "STATUS_SUCCEEDED" -> PostResult.Posted
            status != null -> PostResult.Rejected
            else -> PostResult.Unclear
        }
    }

    /** Ponto de partida do chat de uma live do YouTube, ou o motivo de não haver chat. */
    suspend fun liveChat(videoId: String): LiveChatStart {
        val root = tube.call("next") { put("videoId", videoId) } ?: return LiveChatStart.Unavailable("Chat indisponível.")
        val token = root.collect("liveChatRenderer").firstNotNullOfOrNull {
            it.second.path("continuations", 0, "reloadContinuationData", "continuation").str()
        }
        if (token != null) return LiveChatStart.Ready(token)
        val reason = root.collect("conversationBarRenderer").firstNotNullOfOrNull {
            it.second.path("availabilityMessage", "messageRenderer", "text", "runs", 0, "text").str()
        }
        return LiveChatStart.Unavailable(reason ?: "Esta live não tem chat.")
    }

    /** O que deu o envio de uma mensagem no chat de uma live: [result] e, quando o YouTube a devolveu, a própria [message]. */
    class ChatSend(val result: PostResult, val message: app.apex.model.ChatMessage?, val reason: String?)

    /**
     * O código que o YouTube exige para escrever no chat desta live (`sendLiveChatMessageEndpoint`). Vem no painel de envio da primeira
     * resposta do chat e só existe com a conta logada e o chat aberto para ela.
     */
    internal fun chatSendParams(liveChatContinuation: JsonElement): String? =
        liveChatContinuation.collect("sendLiveChatMessageEndpoint").firstNotNullOfOrNull { it.second["params"].str() }

    /** Quando o chat limita quem escreve (só inscritos, só membros…), o YouTube troca o campo por um aviso: devolve o texto dele. */
    internal fun chatRestriction(liveChatContinuation: JsonElement): String? =
        liveChatContinuation.collect("liveChatRestrictedParticipationRenderer").firstNotNullOfOrNull { (_, n) ->
            n["message"]["runs"].list().joinToString("") { it["text"].str().orEmpty() }.trim().takeIf { it.isNotEmpty() }
        }

    /** Escreve [text] no chat da live com o código [params]. A mensagem volta junto na resposta (o chat só a mostraria alguns segundos depois). */
    suspend fun sendChatMessage(params: String, text: String): ChatSend {
        if (!tube.loggedIn || text.isBlank()) return ChatSend(PostResult.Rejected, null, "Entre na conta do YouTube para escrever no chat.")
        val root = tube.call("live_chat/send_message") {
            put("params", params)
            put("clientMessageId", "apex-" + currentTimeMillis() + "-" + (0..99_999).random())
            put("richMessage", kotlinx.serialization.json.buildJsonObject {
                put("textSegments", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("text", text) })
                })
            })
        } ?: return ChatSend(PostResult.Rejected, null, "Sem resposta do YouTube.")
        return classifyChatSend(root)
    }

    internal fun classifyChatSend(root: JsonElement): ChatSend {
        if (root["error"] != null) return ChatSend(PostResult.Rejected, null, root["error"]["message"].str() ?: "O YouTube recusou a mensagem.")
        val item = root.collect("addChatItemAction").firstNotNullOfOrNull { it.second["item"]["liveChatTextMessageRenderer"] }
        if (item != null) return ChatSend(PostResult.Posted, app.apex.chat.parseYoutubeChatItem(item), null)
        return ChatSend(PostResult.Unclear, null, "O YouTube não confirmou a mensagem (modo lento, chat só para inscritos ou mensagem bloqueada).")
    }

    /** O que o YouTube devolveu do chat gravado: as mensagens e o código da página seguinte (`null` = acabou). */
    class ChatReplayPage(val messages: List<ReplayMessage>, val next: String?)

    /** O código para ler o chat gravado de uma live que já acabou; `null` se este vídeo não tem repetição do chat. */
    suspend fun chatReplaySeed(videoId: String): String? {
        val root = tube.call("next") { put("videoId", videoId) } ?: return null
        val renderer = root.collect("liveChatRenderer").firstOrNull()?.second ?: return null
        if (renderer["isReplay"].str() != "true") return null
        return renderer.path("continuations", 0, "reloadContinuationData", "continuation").str()
    }

    /** Uma página do chat gravado. Com [fromMs] pula para esse ponto do vídeo (use o código inicial); sem ele segue do código da página anterior. */
    suspend fun chatReplay(continuation: String, fromMs: Long? = null): ChatReplayPage {
        val resp = tube.call("live_chat/get_live_chat_replay") {
            put("continuation", continuation)
            if (fromMs != null) put("currentPlayerState", buildJsonObject { put("playerOffsetMs", fromMs.toString()) })
        } ?: error("O YouTube não devolveu o chat gravado.")
        return parseChatReplay(resp)
    }

    internal fun parseChatReplay(resp: JsonElement): ChatReplayPage {
        val live = resp.path("continuationContents", "liveChatContinuation") ?: return ChatReplayPage(emptyList(), null)
        val messages = live["actions"].list().mapNotNull { action ->
            val replay = action["replayChatItemAction"] ?: return@mapNotNull null
            val offset = replay["videoOffsetTimeMsec"].str()?.toLongOrNull() ?: return@mapNotNull null
            val item = replay["actions"].list().firstNotNullOfOrNull { it.path("addChatItemAction", "item", "liveChatTextMessageRenderer") } ?: return@mapNotNull null
            app.apex.chat.parseYoutubeChatItem(item)?.let { ReplayMessage(offset, it) }
        }
        return ChatReplayPage(messages.sortedBy { it.offsetMs }, live["continuations"][0]["liveChatReplayContinuationData"]["continuation"].str())
    }

    // ---------- leitura ----------

    /**
     * Os tokens das ordens da aba de um canal, na ordem do YouTube (mais recentes, mais vistos, mais antigos). Na aba de vídeos elas ficam num menu
     * (`listItemViewModel`); na de transmissões, em botões soltos (`chipViewModel`). Cada um traz um `continuationCommand` com o token.
     */
    internal fun sortTokens(root: JsonElement): List<String> {
        fun firstToken(node: JsonElement): String? = node.collect("continuationCommand").firstNotNullOfOrNull { it.second["token"].str() }
        val fromMenu = root.collect("listItemViewModel").mapNotNull { firstToken(it.second) }
        if (fromMenu.size >= 2) return fromMenu
        return root.collect("chipViewModel").mapNotNull { firstToken(it.second) }
    }

    private fun parseVideos(root: JsonElement): List<Media> {
        val now = currentTimeMillis()
        val out = mutableListOf<Media>()
        for ((key, node) in root.collect("videoRenderer", "lockupViewModel", "compactVideoRenderer", "gridVideoRenderer")) {
            val media = if (key == "lockupViewModel") lockupToMedia(node, now) else videoRendererToMedia(node, now)
            if (media != null) out += media
        }
        return out.distinctBy { it.id }
    }

    private fun parseChannelRenderer(c: JsonElement): Channel? {
        val id = c["channelId"].str() ?: return null
        val name = c["title"]["simpleText"].str() ?: return null
        val avatar = c["thumbnail"]["thumbnails"].list().lastOrNull()?.get("url").str().httpsUrl()
        val sub = c["subscriberCountText"]["simpleText"].str()
        val handle = c["subscriberCountText"]["simpleText"].str()?.takeIf { it.startsWith("@") }
            ?: c["navigationEndpoint"]["browseEndpoint"]["canonicalBaseUrl"].str()?.removePrefix("/")?.takeIf { it.startsWith("@") }
        val followers = sub?.takeIf { !it.startsWith("@") }?.let { parseCountText(it) }
        val verified = c["ownerBadges"].list().any { it.toString().contains("VERIFIED") }
        return Channel(Platform.YouTube, id, name, avatar, handle, followers, verified, "https://www.youtube.com/channel/$id")
    }

    private fun videoRendererToMedia(v: JsonElement, now: Long): Media? {
        val id = v["videoId"].str() ?: return null
        val title = v["title"]["runs"][0]["text"].str() ?: v["title"]["simpleText"].str() ?: return null
        val owner = v["ownerText"]["runs"][0] ?: v["longBylineText"]["runs"][0] ?: v["shortBylineText"]["runs"][0]
        val channelId = owner.path("navigationEndpoint", "browseEndpoint", "browseId").str()
        val channelName = owner["text"].str()
        val avatar = v.path(
            "channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer", "thumbnail", "thumbnails", 0, "url",
        ).str().httpsUrl()
        val handle = owner.path("navigationEndpoint", "browseEndpoint", "canonicalBaseUrl").str()?.removePrefix("/")?.takeIf { it.startsWith("@") }
        val live = v["badges"].list().any { it.toString().contains("LIVE_NOW") } ||
            v["thumbnailOverlays"].list().any { it.path("thumbnailOverlayTimeStatusRenderer", "style").str() == "LIVE" }
        val viewsText = v["viewCountText"]["simpleText"].str()
            ?: v["viewCountText"]["runs"].list().joinToString("") { it["text"].str().orEmpty() }.ifBlank { null }
        val duration = v["lengthText"]["simpleText"].str()?.let { parseClock(it) }
        val published = v["publishedTimeText"]["simpleText"].str()
        val thumb = pickThumb(v["thumbnail"]["thumbnails"].list().mapNotNull { t -> t["url"].str()?.let { it to (t["width"].int() ?: 0) } }, id)
        val verified = v["ownerBadges"].list().any { it.toString().contains("VERIFIED") }
        return Media(
            platform = Platform.YouTube, id = id, title = title,
            channel = channelId?.let { Channel(Platform.YouTube, it, channelName.orEmpty(), avatar, handle, verified = verified, url = "https://www.youtube.com/channel/$it") },
            thumbnailUrl = thumb, durationSec = duration, viewCount = parseCountText(viewsText),
            publishedAt = parsePublished(published, now), isLive = live,
            url = "https://www.youtube.com/watch?v=$id",
        )
    }

    private fun lockupToMedia(l: JsonElement, now: Long): Media? {
        if (l["contentType"].str() != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l["contentId"].str() ?: return null
        val meta = l["metadata"]["lockupMetadataViewModel"] ?: return null
        val title = meta["title"]["content"].str() ?: return null
        val rows = meta["metadata"]["contentMetadataViewModel"]["metadataRows"].list()
            .map { row -> row["metadataParts"].list().mapNotNull { it["text"]["content"].str() } }
        // Dentro da página de um canal o cartão não repete o nome do canal: a primeira linha já é "visualizações • há tantos dias".
        val channelName = rows.getOrNull(0)?.firstOrNull()?.takeIf { !looksLikeAge(it) && !looksLikeViews(it) }
        val rest = (if (channelName != null) rows.drop(1) else rows).flatten()
        val published = rest.firstOrNull { looksLikeAge(it) }
        val views = rest.firstOrNull { it != published && it.any(Char::isDigit) }
        val avatar = meta["image"]["decoratedAvatarViewModel"]["avatar"]["avatarViewModel"]["image"]["sources"][0]["url"].str().httpsUrl()
        val channelId = meta.collect("browseEndpoint").firstNotNullOfOrNull { it.second["browseId"].str() }
        val badges = l["contentImage"]?.collect("thumbnailBadgeViewModel").orEmpty().mapNotNull { it.second["text"].str() }
        val live = badges.any { it.equals("AO VIVO", true) || it.equals("LIVE", true) } ||
            views?.contains("assistindo", true) == true || views?.contains("watching", true) == true
        val duration = badges.firstOrNull { it.firstOrNull()?.isDigit() == true }?.let { parseClock(it) }
        val thumb = pickThumb(
            l["contentImage"]["thumbnailViewModel"]["image"]["sources"].list()
                .mapNotNull { s -> s["url"].str()?.let { it to (s["width"].int() ?: 0) } },
            id,
        )
        return Media(
            platform = Platform.YouTube, id = id, title = title,
            channel = channelId?.let { Channel(Platform.YouTube, it, channelName.orEmpty(), avatar, url = "https://www.youtube.com/channel/$it") },
            thumbnailUrl = thumb, durationSec = duration, viewCount = parseCountText(views),
            publishedAt = parsePublished(published, now), isLive = live,
            url = "https://www.youtube.com/watch?v=$id",
        )
    }

    /** A capa maior que o YouTube oferece (até ~800 px): as pequenas, de 320 px, ficam borradas nos cartões grandes. */
    private fun pickThumb(sources: List<Pair<String, Int>>, id: String): String =
        sources.filter { it.second in 1..800 }.maxByOrNull { it.second }?.first
            ?: sources.firstOrNull { it.second >= 320 }?.first
            ?: sources.lastOrNull()?.first
            ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

private fun looksLikeViews(s: String): Boolean {
    val t = s.lowercase()
    return t.contains("visualiza") || t.contains(" view") || t.contains("assistindo") || t.contains("watching")
}

private fun looksLikeAge(s: String): Boolean {
    val t = s.lowercase()
    return t.startsWith("há ") || t.contains(" ago") || t.contains("transmitido") || t.contains("estreou") || t.contains("streamed")
}

fun parseClock(text: String): Long? {
    val parts = text.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
    return parts.fold(0L) { acc, p -> acc * 60 + p }
}

/** "870 mil visualizações", "1,4 mi", "1.204.596 visualizações", "12 mil assistindo", "1.2M views" → número. */
fun parseCountText(text: String?): Long? {
    if (text == null) return null
    val t = text.replace(' ', ' ').lowercase()
    val m = Regex("""(\d[\d.,]*)\s*(mil milhões|milhões|milhão|mil|mi|bi|k|m|b)?(?![a-zà-ú])""").find(t) ?: return null
    val number = m.groupValues[1]
    val suffix = m.groupValues[2]
    val multiplier = when (suffix) {
        "mil", "k" -> 1_000.0
        "mi", "m", "milhões", "milhão" -> 1_000_000.0
        "bi", "b", "mil milhões" -> 1_000_000_000.0
        else -> 0.0
    }
    return if (multiplier > 0) {
        (number.replace(',', '.').toDoubleOrNull()?.times(multiplier))?.toLong()
    } else {
        number.replace(".", "").replace(",", "").toLongOrNull()
    }
}

/** "há 3 dias", "há 6 h", "3 weeks ago" → instante aproximado em ms. */
fun parsePublished(text: String?, nowMs: Long): Long? {
    if (text == null) return null
    val t = text.lowercase()
    val m = Regex("""(\d+)\s*(segundos?|seg|minutos?|min|horas?|h|dias?|d|semanas?|sem|m[eê]s(?:es)?|anos?|seconds?|minutes?|hours?|days?|weeks?|months?|years?)\b""").find(t)
        ?: return null
    val n = m.groupValues[1].toLongOrNull() ?: return null
    val unit = m.groupValues[2]
    val seconds = when {
        unit.startsWith("seg") || unit.startsWith("second") -> 1L
        unit.startsWith("min") -> 60L
        unit.startsWith("hora") || unit == "h" || unit.startsWith("hour") -> 3600L
        unit.startsWith("dia") || unit == "d" || unit.startsWith("day") -> 86_400L
        unit.startsWith("sem") || unit.startsWith("week") -> 7 * 86_400L
        unit.startsWith("m") && (unit.contains("s") || unit.startsWith("month")) -> 30 * 86_400L
        unit.startsWith("ano") || unit.startsWith("year") -> 365 * 86_400L
        else -> return null
    }
    return nowMs - n * seconds * 1000
}

/**
 * Lê a página de assinaturas do YouTube: os cartões vêm em ordem, com um título de seção ("Assinaturas", depois "Assinaturas inativas").
 * Só valem os canais da parte ativa. O YouTube Premium também aparece lá, mas com a logo do Premium, não a foto de um canal.
 */
internal fun parseActiveMemberships(root: JsonElement, known: List<Channel>): List<Channel> {
    fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    fun avatarBase(url: String?) = url?.substringBefore('=')?.takeIf { it.isNotBlank() }
    val byName = known.groupBy { norm(it.name) }
    val byAvatar = known.mapNotNull { c -> avatarBase(c.avatarUrl)?.let { it to c } }.toMap()

    var inactive = false
    val out = LinkedHashMap<String, Channel>()
    for ((_, card) in root.collect("cardItemRenderer")) {
        val heading = card["headingRenderer"] ?: continue
        val withImage = heading["cardItemTextWithImageRenderer"]
        val firstText = (withImage ?: heading).collect("text").firstNotNullOfOrNull { it.second["runs"][0]["text"].str() }.orEmpty()
        if (withImage == null) {
            // Título de seção: tudo depois de "Assinaturas inativas" (ou "Inactive memberships") não vale.
            val title = firstText.lowercase()
            if (title.contains("inativ") || title.contains("inactive") || title.contains("expired")) inactive = true
            continue
        }
        if (inactive) continue
        val image = withImage.collect("url").firstNotNullOfOrNull { it.second.str() } ?: continue
        if (!image.contains("ggpht.com") && !image.contains("googleusercontent.com")) continue
        val channel = byAvatar[avatarBase(image)] ?: byName[norm(firstText)]?.firstOrNull() ?: continue
        out.putIfAbsent(channel.key, channel)
    }
    return out.values.toList()
}

/** Um @handle do YouTube (sem o @): letras, números, ponto, traço e sublinhado, de 3 a 30 caracteres. */
private val HANDLE = Regex("""^[A-Za-z0-9._-]{3,30}$""")
