package app.apex.source

import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Um canal e, se estiver no ar, a live em andamento. */
data class ChannelHit(val channel: Channel, val live: Media?)

class TwitchSource(private val http: HttpClient = Http.client) {
    /** Token de login (cookie `auth-token`), quando o usuário entrou na conta. */
    var authToken: String? = null

    private suspend fun gql(query: String, variables: String? = null): JsonElement? {
        val body = buildJsonObject {
            put("query", query)
            if (variables != null) parseJson(variables)?.let { put("variables", it) }
        }.toString()
        val headers = buildMap {
            put("Client-ID", CLIENT_ID)
            authToken?.let { put("Authorization", "OAuth $it") }
        }
        return parseJson(http.postJson("https://gql.twitch.tv/gql", body, headers))["data"]
    }

    private fun lit(s: String) = JsonPrimitive(s).toString()

    private val streamFields = """
        id title viewersCount createdAt
        previewImageURL(width: 440, height: 248)
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

    suspend fun topStreams(limit: Int = 30, language: String? = null): List<Media> {
        suspend fun query(opts: String) =
            gql("{ streams(first: $limit, options: { sort: VIEWER_COUNT$opts }) { edges { node { $streamFields } } } }")
        val data = (if (language != null) query(", broadcasterLanguages: [$language]") else null)
            ?: query("")
        return data["streams"]["edges"].list().mapNotNull { streamToMedia(it["node"]) }
    }

    suspend fun streamsByCategory(name: String, limit: Int = 30): List<Media> {
        val data = gql("{ game(name: ${lit(name)}) { streams(first: $limit) { edges { node { $streamFields } } } } }")
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

    suspend fun currentUser(): Pair<String, String?>? {
        if (authToken == null) return null
        val data = gql("{ currentUser { login displayName profileImageURL(width: 70) } }")
        val u = data["currentUser"] ?: return null
        return (u["displayName"].str() ?: u["login"].str() ?: return null) to u["profileImageURL"].str()
    }

    /** Canais seguidos pela conta logada (precisa do [authToken]). */
    suspend fun followedChannels(limit: Int = 100): List<ChannelHit> {
        if (authToken == null) return emptyList()
        val data = gql(
            """{ currentUser { follows(first: $limit) { edges { node { login displayName profileImageURL(width: 70)
            followers { totalCount } stream { $streamFields } } } } } }""",
        )
        return data["currentUser"]["follows"]["edges"].list().mapNotNull { userToHit(it["node"]) }
    }

    suspend fun resolve(media: Media): Resolved {
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

    companion object {
        const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    }
}
