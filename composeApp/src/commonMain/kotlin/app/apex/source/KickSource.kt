package app.apex.source

import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Resolved
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

class KickChannelInfo(val hit: ChannelHit, val chatroomId: Long?, val playbackUrl: String?)

class KickSource(private val http: HttpClient = Http.client) {
    /** Token de sessão da conta (cookie `session_token`), quando o usuário entrou. */
    var sessionToken: String? = null

    private val headers: Map<String, String>
        get() = buildMap {
            put("User-Agent", Http.UA)
            put("Accept", "application/json")
            sessionToken?.let { put("Authorization", "Bearer $it") }
        }

    private suspend fun json(url: String): JsonElement? = parseJson(http.getText(url, headers))

    private fun slugUrl(slug: String) = "https://kick.com/$slug"

    var hideMature: Boolean = true

    suspend fun liveStreams(language: String? = "pt", pages: Int = 3, category: String? = null): List<Media> = coroutineScope {
        val lang = language?.takeIf { it.isNotBlank() } ?: "en"
        (1..pages).map { page ->
            async {
                val extra = if (category != null) "&subcategory=$category" else ""
                json("https://kick.com/stream/livestreams/$lang?page=$page&limit=24&sort=desc$extra")
                    ?.get("data").list().filter { !(hideMature && it["is_mature"].bool() == true) }.mapNotNull { streamToMedia(it) }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    private fun streamToMedia(s: JsonElement?): Media? {
        val ch = s["channel"] ?: return null
        val slug = ch["slug"].str() ?: return null
        val user = ch["user"]
        val name = user["username"].str() ?: slug
        return Media(
            platform = Platform.Kick,
            id = slug,
            title = s["session_title"].str().orEmpty().ifBlank { name },
            channel = Channel(
                Platform.Kick, slug, name,
                user["profilepic"].str() ?: user["profile_pic"].str(), "@$slug", url = slugUrl(slug),
            ),
            thumbnailUrl = s["thumbnail"]["src"].str(),
            viewCount = s["viewer_count"].long() ?: s["viewers"].long(),
            isLive = true,
            category = s["categories"][0]["name"].str(),
            url = slugUrl(slug),
        )
    }

    suspend fun search(query: String): List<ChannelHit> {
        val encoded = query.replace(" ", "%20")
        val data = json("https://kick.com/api/search?searched_word=$encoded") ?: return emptyList()
        return data["channels"].list().mapNotNull { c ->
            val slug = c["slug"].str() ?: return@mapNotNull null
            val user = c["user"]
            val name = user["username"].str() ?: slug
            val channel = Channel(
                Platform.Kick, slug, name, user["profile_pic"].str() ?: user["profilepic"].str(),
                "@$slug", c["followers_count"].long() ?: c["followersCount"].long(),
                verified = c["verified"] != null, url = slugUrl(slug),
            )
            val live = if (c["isLive"].bool() == true) {
                Media(Platform.Kick, slug, name, channel, null, null, null, null, true, null, slugUrl(slug))
            } else null
            ChannelHit(channel, live)
        }
    }

    suspend fun channel(slug: String): KickChannelInfo? {
        val c = json("https://kick.com/api/v2/channels/$slug") ?: return null
        val id = c["slug"].str() ?: return null
        val user = c["user"]
        val name = user["username"].str() ?: id
        val channel = Channel(
            Platform.Kick, id, name, user["profile_pic"].str() ?: user["profilepic"].str(), "@$id",
            c["followers_count"].long(), c["verified"].let { it != null && it.bool() != false }, slugUrl(id),
        )
        val ls = c["livestream"]
        val live = if (ls != null && ls["is_live"].bool() != false) {
            Media(
                Platform.Kick, id, ls["session_title"].str().orEmpty().ifBlank { name }, channel,
                ls["thumbnail"].str() ?: ls["thumbnail"]["src"].str(), null,
                ls["viewer_count"].long(), null, true,
                ls["categories"][0]["name"].str(), slugUrl(id),
            )
        } else null
        return KickChannelInfo(ChannelHit(channel, live), c["chatroom"]["id"].long(), c["playback_url"].str())
    }

    suspend fun channels(slugs: List<String>): List<ChannelHit> = coroutineScope {
        slugs.chunked(6).flatMap { chunk -> chunk.map { s -> async { runCatching { channel(s)?.hit }.getOrNull() } }.awaitAll() }
            .filterNotNull()
    }

    suspend fun categories(limit: Int = 40): List<LiveCategory> {
        val data = json("https://kick.com/api/v1/subcategories?limit=$limit&page=1") ?: return emptyList()
        return data["data"].list().mapNotNull {
            val name = it["name"].str() ?: return@mapNotNull null
            LiveCategory(
                it["slug"].str().orEmpty(), name, it["viewers"].long(),
                it["banner"]["src"].str() ?: it["banner"]["responsive"].str()?.substringBefore(' '),
            )
        }
    }

    suspend fun resolve(media: Media): Resolved {
        val info = channel(media.id) ?: error("Canal não encontrado na Kick.")
        val url = info.playbackUrl ?: error("A Kick não liberou o stream.")
        val text = http.getText(url, headers)
        if (!text.startsWith("#EXTM3U")) error("O canal ${info.hit.channel.name} está offline.")
        return Resolved(
            media = info.hit.live ?: media, description = null, likes = null,
            subscribers = info.hit.channel.followers, uploadDate = null,
            chapters = emptyList(), subtitles = emptyList(),
            // A lista mestra da Kick aponta legendas que não existem e travam o VLC; usa as qualidades direto.
            qualities = parseHlsMaster(url, text).filter { it.height > 0 || it.audioOnly },
            userAgent = null, isLive = true,
        )
    }

    suspend fun chatroomId(slug: String): Long? = channel(slug)?.chatroomId

    suspend fun currentUser(): Pair<String, String?>? {
        if (sessionToken == null) return null
        val u = json("https://kick.com/api/v1/user") ?: return null
        val name = u["streamer_channel"]["user"]["username"].str() ?: u["username"].str() ?: return null
        return name to (u["profile_pic"].str() ?: u["profilepic"].str())
    }

    /** Canais que a conta logada segue (precisa do [sessionToken]). */
    suspend fun followedChannels(): List<Channel> {
        if (sessionToken == null) return emptyList()
        val out = mutableListOf<Channel>()
        var cursor = 0
        var guard = 0
        while (guard++ < 10) {
            val data = json("https://kick.com/api/v2/channels/followed?cursor=$cursor") ?: break
            val list = data["channels"].list()
            list.forEach { c ->
                val slug = c["channel_slug"].str() ?: c["slug"].str() ?: return@forEach
                val name = c["user_username"].str() ?: c["username"].str() ?: slug
                out += Channel(Platform.Kick, slug, name, c["profile_picture"].str() ?: c["profilepic"].str(), "@$slug", url = slugUrl(slug))
            }
            val next = data["nextCursor"].long()?.toInt() ?: break
            if (list.isEmpty()) break
            cursor = next
        }
        return out.distinctBy { it.id }
    }
}
