package app.apex.source

import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.CLIP_PREFIX
import app.apex.model.ClipSort
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.model.VOD_PREFIX
import app.apex.util.parseIsoMillis
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

class KickLivePage(val items: List<Media>, val next: String?)

class KickClipPage(val items: List<Media>, val next: String?)

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

    /**
     * Lives da Kick com o idioma, a categoria e a ordem por espectadores filtrados pelo próprio servidor (o endereço antigo ignorava o
     * idioma). [cursor] é o [KickLivePage.next] da página anterior.
     */
    suspend fun lives(
        language: String? = null, categoryId: String? = null, ascending: Boolean = false, cursor: String? = null, limit: Int = 24,
    ): KickLivePage {
        val data = json(livesUrl(language, categoryId, ascending, cursor, limit))["data"] ?: error("A Kick não devolveu a lista de lives.")
        val items = data["livestreams"].list().filter { !(hideMature && it["is_mature"].bool() == true) }.mapNotNull { webLiveToMedia(it) }
        return KickLivePage(items, data["pagination"]["next_cursor"].str()?.takeIf { it.isNotBlank() })
    }

    /** `limit` vai até 100. A página seguinte se pede com `after=` (o antigo `cursor=` passou a ser ignorado e devolvia sempre a primeira). */
    internal fun livesUrl(language: String?, categoryId: String?, ascending: Boolean, cursor: String?, limit: Int): String = buildString {
        append("https://web.kick.com/api/v1/livestreams?limit=${limit.coerceIn(1, 100)}&sort=").append(if (ascending) "viewer_count_asc" else "viewer_count_desc")
        language?.takeIf { it.isNotBlank() }?.let { append("&language=").append(it.encodeURLParameter()) }
        categoryId?.takeIf { it.isNotBlank() }?.let { append("&category_id=").append(it.encodeURLParameter()) }
        cursor?.takeIf { it.isNotBlank() }?.let { append("&after=").append(it.encodeURLParameter()) }
    }

    internal fun webLiveToMedia(s: JsonElement?): Media? {
        val ch = s["channel"] ?: return null
        val slug = ch["slug"].str() ?: return null
        val name = ch["username"].str() ?: slug
        return Media(
            platform = Platform.Kick,
            id = slug,
            title = s["title"].str().orEmpty().ifBlank { name },
            channel = Channel(Platform.Kick, slug, name, ch["profile_pic"].str(), "@$slug", url = slugUrl(slug)),
            thumbnailUrl = s["thumbnail"]["src"].str(),
            viewCount = s["viewer_count"].long(),
            isLive = true,
            category = s["category"]["name"].str(),
            url = slugUrl(slug),
        )
    }

    suspend fun liveStreams(language: String? = "pt", pages: Int = 3, category: String? = null): List<Media> {
        // Primeiro o endereço novo (com o idioma funcionando); se ele falhar ou vier vazio, o antigo.
        val fresh = runCatching {
            val out = mutableListOf<Media>()
            var cursor: String? = null
            repeat(pages) {
                val page = lives(language, category?.takeIf { it.all(Char::isDigit) }, cursor = cursor)
                out += page.items
                cursor = page.next ?: return@runCatching out.distinctBy { it.id }
            }
            out.distinctBy { it.id }
        }.getOrNull()
        return fresh?.takeIf { it.isNotEmpty() } ?: legacyLiveStreams(language, pages, category)
    }

    private suspend fun legacyLiveStreams(language: String?, pages: Int, category: String?): List<Media> = coroutineScope {
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
            // A resposta do canal já não traz a miniatura da live: ela vem do endereço da live do canal.
            val thumbnail = ls["thumbnail"].str() ?: ls["thumbnail"]["src"].str()
                ?: runCatching { json("https://kick.com/api/v2/channels/$id/livestream")["data"]["thumbnail"]["src"].str() }.getOrNull()
            Media(
                Platform.Kick, id, ls["session_title"].str().orEmpty().ifBlank { name }, channel,
                thumbnail, null,
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
                // O id numérico é o que o filtro de categoria das lives entende.
                it["id"].long()?.toString() ?: it["slug"].str().orEmpty(), name, it["viewers"].long(),
                it["banner"]["src"].str() ?: it["banner"]["responsive"].str()?.substringBefore(' '),
            )
        }
    }

    /** VODs (transmissões passadas) do canal: a Kick entrega os 30 mais recentes de uma vez. */
    suspend fun vods(channel: Channel): List<Media> {
        val items = json("https://kick.com/api/v2/channels/${channel.id}/videos").list()
        return items.mapNotNull { vodToMedia(it, channel) }
    }

    internal fun vodToMedia(item: JsonElement?, channel: Channel): Media? {
        if (item["is_live"].bool() == true) return null
        val video = item["video"]
        val uuid = video["uuid"].str() ?: return null
        // VOD apagado, privado ou limpo da Kick não toca.
        if (video["is_private"].bool() == true || video["is_pruned"].bool() == true || video["status"].str()?.let { it != "public" } == true) return null
        if (hideMature && item["is_mature"].bool() == true) return null
        return Media(
            platform = Platform.Kick,
            id = VOD_PREFIX + uuid,
            title = item["session_title"].str().orEmpty().ifBlank { channel.name },
            channel = channel,
            thumbnailUrl = item["thumbnail"]["src"].str(),
            durationSec = item["duration"].long()?.div(1000)?.takeIf { it > 0 },
            viewCount = video["views"].long() ?: item["views"].long(),
            publishedAt = parseIsoMillis(item["start_time"].str() ?: item["created_at"].str()),
            isLive = false,
            category = item["categories"][0]["name"].str(),
            url = "${slugUrl(channel.id)}/videos/$uuid",
        )
    }

    private suspend fun resolveVod(media: Media): Resolved {
        val uuid = media.id.removePrefix(VOD_PREFIX)
        val video = json("https://kick.com/api/v1/video/$uuid") ?: error("A Kick não achou este VOD (apagado ou privado).")
        val source = video["source"].str() ?: error("A Kick não liberou este VOD.")
        val text = http.getText(source, headers)
        if (!text.startsWith("#EXTM3U")) error("A Kick não entregou este VOD.")
        return Resolved(
            media = vodDetailToMedia(video) ?: media, description = null, likes = null, subscribers = null, uploadDate = null,
            chapters = emptyList(), subtitles = emptyList(),
            // Como nas lives: a lista mestra aponta legendas que travam o VLC; usa as qualidades direto.
            qualities = parseHlsMaster(source, text).filter { it.height > 0 || it.audioOnly },
            userAgent = null, isLive = false,
        )
    }

    /** A resposta de um VOD sozinho (`/api/v1/video/<uuid>`) tem outro formato que a lista do canal. */
    internal fun vodDetailToMedia(video: JsonElement?): Media? {
        val uuid = video["uuid"].str() ?: return null
        val live = video["livestream"]
        val ch = live["channel"]
        val slug = ch["slug"].str() ?: return null
        val name = ch["user"]["username"].str() ?: slug
        val channel = Channel(
            Platform.Kick, slug, name, ch["user"]["profilepic"].str() ?: ch["user"]["profile_pic"].str(), "@$slug",
            ch["followersCount"].long(), url = slugUrl(slug),
        )
        return Media(
            platform = Platform.Kick,
            id = VOD_PREFIX + uuid,
            title = live["session_title"].str().orEmpty().ifBlank { name },
            channel = channel,
            thumbnailUrl = live["thumbnail"].str() ?: live["thumbnail"]["src"].str(),
            durationSec = live["duration"].long()?.div(1000)?.takeIf { it > 0 },
            viewCount = video["views"].long(),
            publishedAt = parseIsoMillis(live["start_time"].str() ?: video["created_at"].str()),
            isLive = false,
            category = live["categories"][0]["name"].str(),
            url = "${slugUrl(slug)}/videos/$uuid",
        )
    }

    /** Clipes do canal. A Kick entrega páginas por cursor; [KickClipPage.next] é o cursor da seguinte (ou `null` no fim). */
    suspend fun clips(channel: Channel, sort: ClipSort = ClipSort.Popular, cursor: String? = null): KickClipPage {
        val order = if (sort == ClipSort.Popular) "view" else "date"
        val data = json("https://kick.com/api/v2/channels/${channel.id}/clips?cursor=${(cursor ?: "0").encodeURLParameter()}&sort=$order&time=all")
        val items = data["clips"].list().filter { !(hideMature && it["is_mature"].bool() == true) }.mapNotNull { clipToMedia(it, channel) }
        return KickClipPage(items, data["nextCursor"].str()?.takeIf { it.isNotBlank() && it != "0" })
    }

    internal fun clipToMedia(c: JsonElement?, fallback: Channel? = null): Media? {
        val id = c["id"].str() ?: return null
        val ch = c["channel"]
        val slug = ch["slug"].str() ?: fallback?.id ?: return null
        val name = ch["username"].str() ?: fallback?.name ?: slug
        val channel = fallback?.takeIf { it.id == slug }
            ?: Channel(Platform.Kick, slug, name, ch["profile_picture"].str() ?: ch["profilepic"].str(), "@$slug", url = slugUrl(slug))
        return Media(
            platform = Platform.Kick,
            id = CLIP_PREFIX + id,
            title = c["title"].str().orEmpty().ifBlank { name },
            channel = channel,
            thumbnailUrl = c["thumbnail_url"].str(),
            durationSec = c["duration"].long()?.takeIf { it > 0 },
            viewCount = c["view_count"].long() ?: c["views"].long(),
            publishedAt = parseIsoMillis(c["created_at"].str()),
            isLive = false,
            category = c["category"]["name"].str(),
            url = "${slugUrl(slug)}/clips/$id",
        )
    }

    private suspend fun resolveClip(media: Media): Resolved {
        val id = media.id.removePrefix(CLIP_PREFIX)
        val clip = json("https://kick.com/api/v2/clips/${id.encodeURLParameter()}")["clip"] ?: error("A Kick não achou este clipe (apagado ou privado).")
        val url = clip["clip_url"].str() ?: clip["video_url"].str() ?: error("A Kick não liberou este clipe.")
        val text = http.getText(url, headers)
        if (!text.startsWith("#EXTM3U")) error("A Kick não entregou este clipe.")
        // O clipe costuma ser uma lista única (uma só qualidade); se vier mestra, usa as qualidades dela.
        val qualities = (if ("#EXT-X-STREAM-INF" in text) parseHlsMaster(url, text).filter { it.height > 0 || it.audioOnly } else emptyList())
            .ifEmpty { listOf(Quality("Original", 0, url)) }
        return Resolved(
            media = clipToMedia(clip) ?: media, description = null, likes = null, subscribers = null, uploadDate = null,
            chapters = emptyList(), subtitles = emptyList(), qualities = qualities, userAgent = null, isLive = false,
        )
    }

    suspend fun resolve(media: Media): Resolved {
        if (media.isVod) return resolveVod(media)
        if (media.isClip) return resolveClip(media)
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
