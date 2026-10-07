package app.apex.source

import app.apex.model.Channel
import app.apex.model.ChannelDetails
import app.apex.model.Chapter
import app.apex.model.Comment
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.model.SubtitleTrack
import app.apex.util.parseUploadDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.File

class YtDlpExtractor(
    private val bins: Binaries,
    private val cookieFile: () -> File? = { null },
) : Extractor {
    private val gate = Semaphore(4)

    private val _state = MutableStateFlow<ExtractorState>(ExtractorState.Checking)
    override val state: StateFlow<ExtractorState> = _state.asStateFlow()

    private val installLock = Mutex()

    /** Instala o yt-dlp e o Deno se faltarem e atualiza o yt-dlp se estiver velho (o YouTube muda toda semana). */
    override suspend fun ensureReady(): ExtractorState = installLock.withLock {
        if (!bins.ready || !bins.deno.isFile) {
            try {
                bins.install { _state.value = ExtractorState.Installing(it) }
            } catch (e: Exception) {
                if (!bins.ready) {
                    _state.value = ExtractorState.Missing("Não foi possível baixar o yt-dlp: ${e.message}")
                    return _state.value
                }
            }
        }
        _state.value = ExtractorState.Ready
        val age = System.currentTimeMillis() - bins.ytdlp.lastModified()
        if (age > 7L * 24 * 3600 * 1000) {
            _state.value = ExtractorState.Installing("Atualizando o yt-dlp…")
            runCatching { updateEngine() }
            bins.ytdlp.setLastModified(System.currentTimeMillis())
            _state.value = ExtractorState.Ready
        }
        _state.value
    }

    // ---------- execução ----------

    private suspend fun run(args: List<String>, timeoutMs: Long = 90_000): ByteArray = gate.withPermit {
        withContext(Dispatchers.IO) {
            if (!bins.ready) throw ExtractionException("O yt-dlp não está instalado. Abra Ajustes › Mecanismo para instalar.")
            val cmd = buildList {
                add(bins.ytdlp.absolutePath)
                addAll(listOf("--ignore-config", "--no-warnings", "--no-color", "--encoding", "utf-8", "--socket-timeout", "15"))
                addAll(bins.jsRuntimeArgs())
                cookieFile()?.takeIf { it.isFile }?.let { addAll(listOf("--cookies", it.absolutePath)) }
                addAll(args)
            }
            val process = ProcessBuilder(cmd).also {
                it.environment()["PYTHONUTF8"] = "1"
                it.environment()["PYTHONIOENCODING"] = "utf-8"
            }.start()
            try {
                coroutineScope {
                    val err = async { process.errorStream.readBytes() }
                    val out = async { process.inputStream.readBytes() }
                    val finished = withTimeoutOrNull(timeoutMs) { out.await(); err.await(); true }
                    if (finished == null) {
                        process.destroyForcibly()
                        throw ExtractionException("O yt-dlp demorou demais para responder.")
                    }
                    val stdout = out.await()
                    if (stdout.isEmpty()) throw ExtractionException(friendlyError(err.await().toString(Charsets.UTF_8)))
                    stdout
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private suspend fun json(args: List<String>, timeoutMs: Long = 90_000): JsonElement {
        val bytes = run(args, timeoutMs)
        return parseJson(bytes.toString(Charsets.UTF_8)) ?: throw ExtractionException("Resposta inválida do yt-dlp.")
    }

    private fun friendlyError(stderr: String): String {
        val line = stderr.lines().lastOrNull { it.contains("ERROR", true) }?.substringAfter("ERROR:")?.trim()
            ?: stderr.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            line.contains("not a bot", true) || line.contains("Sign in to confirm", true) ->
                "O YouTube pediu confirmação de que você não é um robô. Entre na sua conta em Ajustes › Contas e tente de novo."
            line.contains("Private video", true) -> "Este vídeo é privado."
            line.contains("members-only", true) || line.contains("Join this channel", true) -> "Vídeo exclusivo para membros do canal."
            line.contains("unavailable", true) -> "Vídeo indisponível."
            line.contains("will begin", true) || line.contains("Premieres in", true) -> "Esta live ainda não começou."
            line.contains("confirm your age", true) || line.contains("age-restricted", true) -> "Vídeo com restrição de idade: entre na conta para assistir."
            line.contains("offline", true) -> "O canal está offline."
            line.isBlank() -> "O yt-dlp não retornou nada."
            else -> line.take(240)
        }
    }

    // ---------- Extractor ----------

    override suspend fun resolve(media: Media): Resolved {
        val info = json(listOf("-J", "--no-playlist", "--skip-download", media.url))
        val live = info["is_live"].bool() == true || info["live_status"].str() == "is_live"
        val qualities = buildQualities(info, live)
        if (qualities.isEmpty()) throw ExtractionException("Nenhum formato de vídeo disponível.")

        val handle = info["uploader_id"].str()?.takeIf { it.startsWith("@") }
        val channel = Channel(
            Platform.YouTube,
            info["channel_id"].str() ?: media.channel?.id.orEmpty(),
            info["channel"].str() ?: info["uploader"].str() ?: media.channel?.name.orEmpty(),
            media.channel?.avatarUrl,
            handle ?: media.channel?.handle,
            info["channel_follower_count"].long() ?: media.channel?.followers,
            info["channel_is_verified"].bool() == true,
            info["channel_url"].str() ?: media.channel?.url.orEmpty(),
        )
        val updated = media.copy(
            title = cleanTitle(info["title"].str(), live) ?: media.title,
            channel = channel,
            thumbnailUrl = media.thumbnailUrl ?: info["thumbnail"].str(),
            durationSec = info["duration"].long() ?: media.durationSec,
            viewCount = info["view_count"].long() ?: media.viewCount,
            publishedAt = info["timestamp"].long()?.times(1000) ?: parseUploadDate(info["upload_date"].str()) ?: media.publishedAt,
            isLive = live,
        )
        return Resolved(
            media = updated,
            description = info["description"].str(),
            likes = info["like_count"].long(),
            subscribers = channel.followers,
            uploadDate = info["upload_date"].str(),
            chapters = info["chapters"].list().mapNotNull {
                val t = it["title"].str() ?: return@mapNotNull null
                Chapter(t, it["start_time"].long() ?: 0)
            },
            subtitles = buildSubtitles(info),
            qualities = qualities,
            userAgent = info["http_headers"]["User-Agent"].str(),
            tags = info["tags"].list().mapNotNull { it.str() },
            isLive = live,
        )
    }

    /** O yt-dlp acrescenta a data e a hora ao título das lives; aqui ela sai. */
    private fun cleanTitle(title: String?, live: Boolean): String? =
        if (live) title?.replace(Regex("""\s\d{4}-\d{2}-\d{2} \d{2}:\d{2}$"""), "") else title

    private fun hasVideo(f: JsonElement) = f["vcodec"].str().let { it != null && it != "none" }
    private fun hasAudio(f: JsonElement) = f["acodec"].str().let { it != null && it != "none" }
    private fun isDirect(f: JsonElement) = f["protocol"].str().let { it == "https" || it == "http" }

    private fun codecRank(vcodec: String, height: Int): Int = when {
        vcodec.startsWith("avc1") -> if (height <= 1080) 3 else 1
        vcodec.startsWith("vp09") || vcodec.startsWith("vp9") -> if (height > 1080) 3 else 2
        vcodec.startsWith("av01") -> if (height > 1080) 2 else 1
        else -> 0
    }

    private fun label(height: Int, fps: Int?): String = "${height}p" + if (fps != null && fps > 30) "$fps" else ""

    private fun buildQualities(info: JsonElement, live: Boolean): List<Quality> {
        val formats = info["formats"].list()
        if (live) {
            val hls = formats.filter { it["protocol"].str()?.startsWith("m3u8") == true && hasVideo(it) }
            val master = hls.firstNotNullOfOrNull { it["manifest_url"].str() }
            val out = mutableListOf<Quality>()
            if (master != null) out += Quality("Automático", 0, master)
            hls.sortedByDescending { it["height"].int() ?: 0 }
                .distinctBy { (it["height"].int() ?: 0) to (it["fps"].int() ?: 0) }
                .forEach { f ->
                    val url = f["url"].str() ?: return@forEach
                    val h = f["height"].int() ?: 0
                    if (h > 0) out += Quality(label(h, f["fps"].int()), h, url, fps = f["fps"].int())
                }
            return out
        }

        val audioUrl = formats.filter { hasAudio(it) && !hasVideo(it) && isDirect(it) }.lastOrNull()?.get("url").str()
        val byHeight = linkedMapOf<Int, Quality>()

        formats.filter { hasVideo(it) && !hasAudio(it) && isDirect(it) && (it["height"].int() ?: 0) > 0 }
            .groupBy { it["height"].int() ?: 0 }
            .forEach { (h, list) ->
                val best = list.sortedWith(
                    compareBy<JsonElement> { codecRank(it["vcodec"].str().orEmpty(), h) }
                        .thenBy { it["fps"].int() ?: 0 }
                        .thenBy { it["tbr"].double() ?: 0.0 },
                ).last()
                val url = best["url"].str() ?: return@forEach
                byHeight[h] = Quality(label(h, best["fps"].int()), h, url, audioUrl, best["fps"].int())
            }

        formats.filter { hasVideo(it) && hasAudio(it) && isDirect(it) && (it["height"].int() ?: 0) > 0 }.forEach { f ->
            val h = f["height"].int() ?: return@forEach
            val url = f["url"].str() ?: return@forEach
            if (h !in byHeight) byHeight[h] = Quality(label(h, f["fps"].int()), h, url, null, f["fps"].int())
        }
        return byHeight.values.sortedByDescending { it.height }
    }

    private fun buildSubtitles(info: JsonElement): List<SubtitleTrack> {
        val out = mutableListOf<SubtitleTrack>()
        fun pick(arr: JsonElement?) = arr.list().firstOrNull { it["ext"].str() == "vtt" } ?: arr.list().firstOrNull()
        for (lang in info["subtitles"].keys()) {
            val e = pick(info["subtitles"][lang]) ?: continue
            val url = e["url"].str() ?: continue
            out += SubtitleTrack(lang, e["name"].str() ?: lang, url, auto = false)
        }
        for (lang in info["automatic_captions"].keys()) {
            val wanted = lang.endsWith("-orig") || lang == "pt" || lang == "pt-BR" || lang == "en"
            if (!wanted) continue
            val e = pick(info["automatic_captions"][lang]) ?: continue
            val url = e["url"].str() ?: continue
            out += SubtitleTrack(lang, (e["name"].str() ?: lang) + " (automática)", url, auto = true)
        }
        return out
    }

    override suspend fun comments(media: Media, limit: Int, newest: Boolean): List<Comment> {
        val sort = if (newest) "new" else "top"
        val info = json(
            listOf(
                "--skip-download", "--write-comments", "--no-playlist",
                "--extractor-args", "youtube:max_comments=$limit,all,0,0;comment_sort=$sort",
                "-J", media.url,
            ),
            timeoutMs = 120_000,
        )
        return info["comments"].list().mapNotNull { c ->
            val text = c["text"].str() ?: return@mapNotNull null
            Comment(
                id = c["id"].str().orEmpty(),
                author = c["author"].str().orEmpty(),
                authorAvatar = c["author_thumbnail"].str(),
                text = text,
                likes = c["like_count"].long() ?: 0,
                pinned = c["is_pinned"].bool() == true,
                timeText = c["_time_text"].str().orEmpty(),
                isReply = c["parent"].str().let { it != null && it != "root" },
            )
        }.filterNot { it.isReply }
    }

    override suspend fun channelDetails(channelId: String): ChannelDetails {
        val info = json(listOf("--flat-playlist", "-J", "--playlist-end", "1", "https://www.youtube.com/channel/$channelId/videos"))
        val thumbs = info["thumbnails"].list()
        val avatar = thumbs.firstOrNull { it["id"].str() == "avatar_uncropped" }?.get("url").str()
            ?: thumbs.filter { (it["width"].int() ?: 0) > 0 && it["width"].int() == it["height"].int() }
                .maxByOrNull { it["width"].int() ?: 0 }?.get("url").str()
        val banner = thumbs.firstOrNull { it["id"].str() == "banner_uncropped" }?.get("url").str()
            ?: thumbs.filter { (it["width"].int() ?: 0) >= 1060 }.maxByOrNull { it["width"].int() ?: 0 }?.get("url").str()
        val id = info["channel_id"].str() ?: channelId
        return ChannelDetails(
            channel = Channel(
                Platform.YouTube, id, info["channel"].str() ?: info["uploader"].str().orEmpty(), avatar,
                info["uploader_id"].str()?.takeIf { it.startsWith("@") },
                info["channel_follower_count"].long(), info["channel_is_verified"].bool() == true,
                info["channel_url"].str() ?: "https://www.youtube.com/channel/$id",
            ),
            bannerUrl = banner,
            description = info["description"].str(),
        )
    }

    override suspend fun updateEngine(): String {
        val out = run(listOf("-U"), timeoutMs = 180_000).toString(Charsets.UTF_8)
        return out.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty().ifBlank { "Concluído." }
    }
}
