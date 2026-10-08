package app.apex.source

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import app.apex.model.ChatMessage
import app.apex.model.ReplayMessage
import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.CLIP_PREFIX
import app.apex.model.ClipSort
import app.apex.model.Resolved
import app.apex.model.VOD_PREFIX
import app.apex.util.parseIsoMillis
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.random.Random

/** Um canal e, se estiver no ar, a live em andamento. */
data class ChannelHit(val channel: Channel, val live: Media?)

class TwitchSource(private val http: HttpClient = Http.client) {
    /** Token de login (cookie `auth-token`), quando o usuário entrou na conta. */
    var authToken: String? = null

    private fun headers(): Map<String, String> = buildMap {
        put("Client-ID", CLIENT_ID)
        authToken?.let { put("Authorization", "OAuth $it") }
    }

    private suspend fun gql(query: String, variables: String? = null): JsonElement? {
        val body = buildJsonObject {
            put("query", query)
            if (variables != null) parseJson(variables)?.let { put("variables", it) }
        }.toString()
        return parseJson(http.postJson(GQL_URL, body, headers()))["data"]
    }

    private fun lit(s: String) = JsonPrimitive(s).toString()

    private val streamFields = """
        id title viewersCount createdAt
        previewImageURL(width: 640, height: 360)
        game { displayName }
        broadcaster { login displayName profileImageURL(width: 70) }
    """.trimIndent()

    private fun streamToMedia(node: JsonElement?): Media? {
        val b = node["broadcaster"] ?: return null
        val login = b["login"].str() ?: return null
        val name = b["displayName"].str() ?: login
        return Media(
            platform = Platform.Twitch,
            id = login,
            title = node["title"].str().orEmpty().ifBlank { name },
            channel = Channel(Platform.Twitch, login, name, b["profileImageURL"].str(), url = "https://www.twitch.tv/$login"),
            thumbnailUrl = node["previewImageURL"].str(),
            viewCount = node["viewersCount"].long(),
            isLive = true,
            category = node["game"]["displayName"].str(),
            url = "https://www.twitch.tv/$login",
        )
    }

    /**
     * As lives mais vistas. A consulta de "streams" da Twitch só aceita até 30 por vez (e a próxima página exige a verificação do
     * navegador); para passar disso, a mesma consulta traz também as lives das categorias mais vistas, e tudo é reunido por público.
     */
    suspend fun topStreams(limit: Int = 30, language: String? = null): List<Media> {
        val data = (if (language != null) gql(topStreamsQuery(limit, ", broadcasterLanguages: [$language]")) else null)
            ?: gql(topStreamsQuery(limit, ""))
        return parseTopStreams(data, limit)
    }

    internal fun topStreamsQuery(limit: Int, opts: String): String {
        val streams = "streams(first: ${limit.coerceIn(1, TOP_STREAMS_PAGE)}, options: { sort: VIEWER_COUNT$opts }) { edges { node { $streamFields } } }"
        if (limit <= TOP_STREAMS_PAGE) return "{ $streams }"
        val games = ((limit - TOP_STREAMS_PAGE + 14) / 15 + 2).coerceAtMost(12)
        return "{ $streams games(first: $games, options: { sort: VIEWER_COUNT }) { edges { node { " +
            "streams(first: 20, options: { sort: VIEWER_COUNT$opts }) { edges { node { $streamFields } } } } } } }"
    }

    internal fun parseTopStreams(data: JsonElement?, limit: Int): List<Media> {
        val top = data["streams"]["edges"].list().mapNotNull { streamToMedia(it["node"]) }
        val more = data["games"]["edges"].list().flatMap { g -> g["node"]["streams"]["edges"].list().mapNotNull { streamToMedia(it["node"]) } }
        return (top + more).distinctBy { it.id }.sortedByDescending { it.viewCount ?: 0 }.take(limit)
    }

    /**
     * Lives de uma categoria. [language] é o código da Twitch (PT, EN, ES…); [ascending] traz primeiro as de menos espectadores.
     * A Twitch só entrega os primeiros 100 de cada consulta (a próxima página exige a verificação do navegador).
     */
    suspend fun streamsByCategory(name: String, limit: Int = 30, language: String? = null, ascending: Boolean = false): List<Media> {
        val options = "sort: ${if (ascending) "VIEWER_COUNT_ASC" else "VIEWER_COUNT"}" +
            (language?.takeIf { it.matches(Regex("[A-Z_]{2,6}")) }?.let { ", broadcasterLanguages: [$it]" } ?: "")
        val data = gql("{ game(name: ${lit(name)}) { streams(first: ${limit.coerceAtMost(100)}, options: {$options}) { edges { node { $streamFields } } } } }")
        return data["game"]["streams"]["edges"].list().mapNotNull { streamToMedia(it["node"]) }
    }

    suspend fun searchStreams(query: String, limit: Int = 20): List<Media> {
        val data = gql("{ searchStreams(userQuery: ${lit(query)}, first: $limit) { edges { node { $streamFields } } } }")
        return data["searchStreams"]["edges"].list().mapNotNull { streamToMedia(it["node"]) }
    }

    suspend fun searchChannels(query: String, limit: Int = 10): List<ChannelHit> {
        val data = gql(
            """{ searchFor(userQuery: ${lit(query)}, platform: "web", options: {targets: [{index: CHANNEL}]}) {
                channels { edges { item { ... on User { login displayName profileImageURL(width: 70)
                followers { totalCount } stream { $streamFields } } } } } } }""",
        )
        return data["searchFor"]["channels"]["edges"].list().take(limit).mapNotNull { userToHit(it["item"]) }
    }

    suspend fun categories(limit: Int = 40): List<LiveCategory> {
        val data = gql("{ games(first: $limit, options: {sort: VIEWER_COUNT}) { edges { node { id name displayName viewersCount boxArtURL(width: 285, height: 380) } } } }")
        return data["games"]["edges"].list().mapNotNull {
            val n = it["node"]
            val name = n["displayName"].str() ?: return@mapNotNull null
            LiveCategory(n["id"].str().orEmpty(), name, n["viewersCount"].long(), n["boxArtURL"].str())
        }
    }

    /** Informação (e live atual) de vários canais de uma vez. */
    suspend fun channels(logins: List<String>): List<ChannelHit> {
        if (logins.isEmpty()) return emptyList()
        val out = mutableListOf<ChannelHit>()
        for (chunk in logins.chunked(35)) {
            val list = chunk.joinToString(",") { lit(it) }
            val data = gql(
                """{ users(logins: [$list]) { login displayName profileImageURL(width: 70)
                followers { totalCount } stream { $streamFields } } }""",
            )
            data["users"].list().mapNotNullTo(out) { userToHit(it) }
        }
        return out
    }

    /**
     * Como [channels], mas devolve `null` quando a Twitch não responde direito (erro ou resposta sem a lista), para quem vigia as lives
     * não confundir "a Twitch falhou" com "ninguém está ao vivo".
     */
    suspend fun channelsOrNull(logins: List<String>): List<ChannelHit>? {
        if (logins.isEmpty()) return emptyList()
        val out = mutableListOf<ChannelHit>()
        for (chunk in logins.chunked(35)) {
            val list = chunk.joinToString(",") { lit(it) }
            val data = gql(
                """{ users(logins: [$list]) { login displayName profileImageURL(width: 70)
                followers { totalCount } stream { $streamFields } } }""",
            ) ?: return null
            val users = data["users"] ?: return null
            users.list().mapNotNullTo(out) { userToHit(it) }
        }
        return out
    }

    private fun userToHit(u: JsonElement?): ChannelHit? {
        val login = u["login"].str() ?: return null
        val name = u["displayName"].str() ?: login
        val channel = Channel(
            Platform.Twitch, login, name, u["profileImageURL"].str(),
            handle = "@$login", followers = u["followers"]["totalCount"].long(), url = "https://www.twitch.tv/$login",
        )
        val s = u["stream"]
        val live = if (s != null) {
            val node = s
            Media(
                Platform.Twitch, login, node["title"].str().orEmpty().ifBlank { name }, channel,
                node["previewImageURL"].str(), null, node["viewersCount"].long(), null, true,
                node["game"]["displayName"].str(), "https://www.twitch.tv/$login",
            )
        } else null
        return ChannelHit(channel, live)
    }

    /**
     * A conta ligada tem o **Twitch Turbo** (sem anúncios nas lives)? `null` se não há conta ligada ou a Twitch não respondeu.
     * O app já pede o vídeo com a sessão da conta, que é o que a Twitch usa para pular os anúncios de quem tem Turbo.
     */
    suspend fun hasTurbo(): Boolean? {
        if (authToken == null) return null
        return gql("{ currentUser { hasTurbo } }")["currentUser"]["hasTurbo"].bool()
    }

    suspend fun currentUser(): Pair<String, String?>? {
        if (authToken == null) return null
        val data = gql("{ currentUser { login displayName profileImageURL(width: 70) } }")
        val u = data["currentUser"] ?: return null
        return (u["displayName"].str() ?: u["login"].str() ?: return null) to u["profileImageURL"].str()
    }

    /** Canais seguidos. [complete] = `false` quando pode haver mais canais do que a Twitch deixa ler (ver [followedChannels]). */
    class FollowList(val channels: List<ChannelHit>, val complete: Boolean)

    private class FollowsPage(val items: List<ChannelHit>, val hasNext: Boolean)

    /**
     * Canais seguidos pela conta logada (precisa do [authToken]). A consulta antiga (`currentUser.follows`) responde "service error";
     * esta é a "consulta salva" que o próprio site da Twitch usa. Cada página tem no máximo 100 canais e a seguinte (por cursor)
     * exige uma verificação de integridade que só o navegador da Twitch passa. Então lemos as duas pontas da lista (os 100 mais antigos
     * e os 100 mais recentes): quem segue até 200 canais recebe tudo.
     */
    suspend fun followedChannels(): FollowList {
        if (authToken == null) return FollowList(emptyList(), true)
        val first = followsPage("ASC")
        if (!first.hasNext) return FollowList(first.items, true)
        val last = followsPage("DESC")
        val merged = (first.items + last.items).distinctBy { it.channel.id }
        // Se as duas pontas se sobrepõem, a lista inteira coube nelas.
        return FollowList(merged, complete = merged.size < first.items.size + last.items.size)
    }

    private suspend fun followsPage(order: String): FollowsPage {
        val body = buildJsonObject {
            put("operationName", "ChannelFollows")
            put("variables", buildJsonObject {
                put("limit", PAGE)
                put("order", order)
            })
            put("extensions", buildJsonObject {
                put("persistedQuery", buildJsonObject {
                    put("version", 1)
                    put("sha256Hash", CHANNEL_FOLLOWS_HASH)
                })
            })
        }.toString()
        val response = parseJson(http.postJson(GQL_URL, body, headers()))
        val follows = response["data"]["user"]["follows"]
            ?: error("A Twitch não devolveu a lista de seguidos (${response["errors"].list().firstOrNull()["message"].str() ?: "sem detalhes"}).")
        val items = follows["edges"].list().mapNotNull { userToHit(it["node"]) }
        return FollowsPage(items, follows["pageInfo"]["hasNextPage"].str() == "true")
    }

    /** O que a Twitch devolveu do chat gravado: as mensagens e se há mais depois. */
    class VideoComments(val messages: List<ReplayMessage>, val hasNext: Boolean)

    /**
     * Chat gravado de um VOD a partir de [offsetSeconds] (segundos do vídeo): cerca de 50 mensagens, começando um pouco antes.
     * É a "consulta salva" que o site usa; pedir pela posição funciona sem login, a página seguinte por cursor não (verificação do navegador).
     */
    suspend fun videoComments(videoId: String, offsetSeconds: Long): VideoComments {
        val body = buildJsonArray {
            add(
                buildJsonObject {
                    put("operationName", "VideoCommentsByOffsetOrCursor")
                    put("variables", buildJsonObject {
                        put("videoID", videoId)
                        put("contentOffsetSeconds", offsetSeconds)
                    })
                    put("extensions", buildJsonObject {
                        put("persistedQuery", buildJsonObject {
                            put("version", 1)
                            put("sha256Hash", VIDEO_COMMENTS_HASH)
                        })
                    })
                },
            )
        }.toString()
        // Sem o login da conta: a consulta anônima é a que o site usa para quem não entrou.
        val response = parseJson(http.postJson(GQL_URL, body, mapOf("Client-ID" to CLIENT_ID))).list().firstOrNull()
        val comments = response["data"]["video"]["comments"]
            ?: error("A Twitch não devolveu o chat deste vídeo (${response["errors"].list().firstOrNull()["message"].str() ?: "sem detalhes"}).")
        return parseVideoComments(comments)
    }

    internal fun parseVideoComments(comments: JsonElement): VideoComments {
        val out = comments["edges"].list().mapNotNull { edge ->
            val node = edge["node"] ?: return@mapNotNull null
            val id = node["id"].str() ?: return@mapNotNull null
            val offset = node["contentOffsetSeconds"].long() ?: return@mapNotNull null
            val name = node["commenter"]["displayName"].str() ?: node["commenter"]["login"].str() ?: "?"
            val text = node["message"]["fragments"].list().joinToString("") { it["text"].str().orEmpty() }
            if (text.isBlank()) return@mapNotNull null
            ReplayMessage(
                offset * 1000,
                ChatMessage(
                    id = id, author = name, text = text,
                    color = app.apex.chat.parseHexColor(node["message"]["userColor"].str()) ?: app.apex.chat.colorFromName(name),
                    badges = node["message"]["userBadges"].list().mapNotNull { it["setID"].str()?.takeIf { s -> s.isNotBlank() } },
                ),
            )
        }.sortedBy { it.offsetMs }
        return VideoComments(out, comments["pageInfo"]["hasNextPage"].str() == "true")
    }

    /** Canais em que a conta é inscrita (sub pago, Prime ou presente). */
    suspend fun subscribedChannels(): List<ChannelHit> {
        if (authToken == null) return emptyList()
        val data = gql(
            """{ currentUser { subscriptionBenefits(criteria: {filter: ALL}) { edges { node {
            user { login displayName profileImageURL(width: 70) } } } } } }""",
        )
        return data["currentUser"]["subscriptionBenefits"]["edges"].list().mapNotNull { userToHit(it["node"]["user"]) }
    }

    /**
     * VODs (transmissões passadas) de um canal, os mais recentes primeiro. Vem uma página só (até [limit]): a página seguinte, por cursor,
     * exige a verificação de integridade que só o navegador da Twitch passa.
     */
    suspend fun vods(login: String, limit: Int = 100): List<Media> {
        val data = gql(
            """{ user(login: ${lit(login)}) { videos(first: $limit, type: ARCHIVE, sort: TIME) { edges { node { $VOD_FIELDS } } } } }""",
        )
        return data["user"]["videos"]["edges"].list().mapNotNull { vodToMedia(it["node"]) }
    }

    private suspend fun resolveVod(media: Media): Resolved {
        val videoId = media.id.removePrefix(VOD_PREFIX)
        val query = """query PlaybackAccessToken_Template(${'$'}id: ID!, ${'$'}playerType: String!) {
            videoPlaybackAccessToken(id: ${'$'}id, params: {platform: "web", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) { value signature }
            video(id: ${'$'}id) { $VOD_FIELDS }
        }"""
        val variables = buildJsonObject { put("id", videoId); put("playerType", "site") }.toString()
        val data = gql(query, variables)
        val token = data["videoPlaybackAccessToken"]["value"].str()
        val sig = data["videoPlaybackAccessToken"]["signature"].str()
        if (token == null || sig == null) error("A Twitch não liberou este VOD (apagado ou indisponível).")
        // O token traz a autorização: VOD só para inscritos vem com "forbidden".
        val authorization = parseJson(token)["authorization"]
        if (authorization["forbidden"].bool() == true) {
            error(authorization["reason"].str()?.takeIf { it.isNotBlank() }?.let { "Este VOD não está liberado para você ($it)." } ?: "Este VOD é só para inscritos do canal.")
        }
        val master = "https://usher.ttvnw.net/vod/$videoId.m3u8" +
            "?client_id=$CLIENT_ID&token=${token.encodeURLParameter()}&sig=$sig" +
            "&allow_source=true&allow_audio_only=true&playlist_include_framerate=true&p=${Random.nextInt(100000, 999999)}"
        val text = http.getText(master)
        if (!text.startsWith("#EXTM3U")) error("A Twitch não entregou este VOD.")
        return Resolved(
            media = vodToMedia(data["video"]) ?: media, description = null, likes = null, subscribers = null, uploadDate = null,
            chapters = emptyList(), subtitles = emptyList(), qualities = parseHlsMaster(master, text),
            userAgent = null, isLive = false,
        )
    }

    /** Clipes de um canal (uma página só, até [limit]; a seguinte exigiria a verificação de integridade do navegador). */
    suspend fun clips(login: String, sort: ClipSort = ClipSort.Popular, limit: Int = 100): List<Media> {
        // A Twitch recusa ordenar por data ("server error"); o "em alta" dela privilegia os clipes novos, então aqui eles são reordenados pela data.
        val order = if (sort == ClipSort.Popular) "VIEWS_DESC" else "TRENDING"
        val data = gql(
            """{ user(login: ${lit(login)}) { clips(first: $limit, criteria: {period: ALL_TIME, sort: $order}) { edges { node { $CLIP_FIELDS } } } } }""",
        )
        val list = data["user"]["clips"]["edges"].list().mapNotNull { clipToMedia(it["node"]) }
        return if (sort == ClipSort.Recent) list.sortedByDescending { it.publishedAt ?: 0L } else list
    }

    private suspend fun resolveClip(media: Media): Resolved {
        val slug = media.id.removePrefix(CLIP_PREFIX)
        val query = """query ClipForPlayback(${'$'}slug: ID!) { clip(slug: ${'$'}slug) { $CLIP_FIELDS
            playbackAccessToken(params: {platform: "web", playerBackend: "mediaplayer", playerType: "site"}) { value signature }
            videoQualities { frameRate quality sourceURL } } }"""
        val clip = gql(query, buildJsonObject { put("slug", slug) }.toString())["clip"] ?: error("A Twitch não achou este clipe (apagado ou indisponível).")
        val token = clip["playbackAccessToken"]["value"].str()
        val sig = clip["playbackAccessToken"]["signature"].str()
        if (token == null || sig == null) error("A Twitch não liberou este clipe.")
        val qualities = clipQualities(clip["videoQualities"].list(), token, sig)
        if (qualities.isEmpty()) error("A Twitch não entregou este clipe.")
        return Resolved(
            media = clipToMedia(clip) ?: media, description = null, likes = null, subscribers = null, uploadDate = null,
            chapters = emptyList(), subtitles = emptyList(), qualities = qualities, userAgent = null, isLive = false,
        )
    }

    /** Os arquivos do clipe são MP4 diretos; só tocam com a assinatura e o token no endereço. */
    internal fun clipQualities(list: List<JsonElement>, token: String, sig: String): List<Quality> =
        list.mapNotNull { q ->
            val src = q["sourceURL"].str() ?: return@mapNotNull null
            val height = q["quality"].str()?.toIntOrNull() ?: return@mapNotNull null
            val fps = q["frameRate"].double()?.roundToInt()
            Quality(
                label = "${height}p" + if (fps != null && fps > 30) "$fps" else "",
                height = height,
                videoUrl = "$src?sig=$sig&token=${token.encodeURLParameter()}",
                fps = fps,
            )
        }.sortedByDescending { it.height }

    suspend fun resolve(media: Media): Resolved {
        if (media.isVod) return resolveVod(media)
        if (media.isClip) return resolveClip(media)
        val login = media.id
        val query = """query PlaybackAccessToken_Template(${'$'}login: String!, ${'$'}playerType: String!) {
            streamPlaybackAccessToken(channelName: ${'$'}login, params: {platform: "web", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) { value signature }
        }"""
        val variables = buildJsonObject { put("login", login); put("playerType", "site") }.toString()
        val data = gql(query, variables)
        val token = data["streamPlaybackAccessToken"]["value"].str()
        val sig = data["streamPlaybackAccessToken"]["signature"].str()
        if (token == null || sig == null) error("A Twitch não liberou o stream (canal offline?).")
        val master = "https://usher.ttvnw.net/api/channel/hls/$login.m3u8" +
            "?client_id=$CLIENT_ID&token=${token.encodeURLParameter()}&sig=$sig" +
            "&allow_source=true&allow_audio_only=true&player=twitchweb&playlist_include_framerate=true&p=${Random.nextInt(100000, 999999)}"
        val text = http.getText(master)
        if (!text.startsWith("#EXTM3U")) error("O canal ${media.channel?.name ?: login} está offline.")
        val hit = runCatching { channels(listOf(login)).firstOrNull() }.getOrNull()
        val full = hit?.live?.copy(thumbnailUrl = hit.live.thumbnailUrl ?: media.thumbnailUrl)
            ?: media.copy(channel = hit?.channel ?: media.channel)
        return Resolved(
            media = full, description = null, likes = null, subscribers = hit?.channel?.followers, uploadDate = null,
            chapters = emptyList(), subtitles = emptyList(), qualities = parseHlsMaster(master, text),
            userAgent = null, isLive = true,
        )
    }

    internal fun vodToMedia(n: JsonElement?): Media? {
        val id = n["id"].str() ?: return null
        val owner = n["owner"]
        val login = owner["login"].str() ?: return null
        val name = owner["displayName"].str() ?: login
        return Media(
            platform = Platform.Twitch,
            id = VOD_PREFIX + id,
            title = n["title"].str().orEmpty().ifBlank { name },
            channel = Channel(Platform.Twitch, login, name, owner["profileImageURL"].str(), handle = "@$login", url = "https://www.twitch.tv/$login"),
            // Enquanto o VOD ainda está sendo processado a Twitch devolve uma imagem de "404": é melhor ficar sem miniatura.
            thumbnailUrl = n["previewThumbnailURL"].str()?.takeIf { !it.contains("404_processing") && !it.contains("/_404/") },
            durationSec = n["lengthSeconds"].long(),
            viewCount = n["viewCount"].long(),
            publishedAt = parseIsoMillis(n["createdAt"].str()),
            isLive = false,
            category = n["game"]["displayName"].str(),
            url = "https://www.twitch.tv/videos/$id",
        )
    }

    internal fun clipToMedia(n: JsonElement?): Media? {
        val slug = n["slug"].str() ?: return null
        val b = n["broadcaster"]
        val login = b["login"].str() ?: return null
        val name = b["displayName"].str() ?: login
        return Media(
            platform = Platform.Twitch,
            id = CLIP_PREFIX + slug,
            title = n["title"].str().orEmpty().ifBlank { name },
            channel = Channel(Platform.Twitch, login, name, b["profileImageURL"].str(), handle = "@$login", url = "https://www.twitch.tv/$login"),
            thumbnailUrl = n["thumbnailURL"].str(),
            durationSec = n["durationSeconds"].long(),
            viewCount = n["viewCount"].long(),
            publishedAt = parseIsoMillis(n["createdAt"].str()),
            isLive = false,
            category = n["game"]["displayName"].str(),
            url = n["url"].str() ?: "https://www.twitch.tv/$login/clip/$slug",
        )
    }

    companion object {
        const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        private const val GQL_URL = "https://gql.twitch.tv/gql"
        private const val PAGE = 100
        /** A "consulta salva" do chat gravado de VODs (a mesma que o site da Twitch usa). */
        private const val VIDEO_COMMENTS_HASH = "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a"
        /** O máximo que a consulta de "streams" da Twitch aceita em `first`. */
        private const val TOP_STREAMS_PAGE = 30

        private const val VOD_FIELDS = """id title lengthSeconds createdAt viewCount previewThumbnailURL(width: 440, height: 248) game { displayName }
            owner { login displayName profileImageURL(width: 70) }"""

        private const val CLIP_FIELDS = """slug title viewCount createdAt durationSeconds thumbnailURL url
            broadcaster { login displayName profileImageURL(width: 70) } game { displayName }"""

        /** Hash da consulta salva `ChannelFollows` do site da Twitch. Se a Twitch trocar, a importação avisa em vez de falhar calada. */
        private const val CHANNEL_FOLLOWS_HASH = "eecf815273d3d949e5cf0085cc5084cd8a1b5b7b6f7990cf43cb0beadf546907"
    }
}
