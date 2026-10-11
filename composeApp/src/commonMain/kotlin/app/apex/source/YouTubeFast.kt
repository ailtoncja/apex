package app.apex.source

import app.apex.model.Channel
import app.apex.model.Chapter
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.util.parseIsoMillis
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put

/**
 * Abre vídeos do YouTube sem o yt-dlp: o `player` da API do YouTube, falando como o app do iPhone, devolve na hora (uns 0,2 s) os endereços
 * diretos dos vídeos e do áudio, sem o código JavaScript do site e sem entrar na conta. O yt-dlp leva uns 4 s para o mesmo (abrir o programa,
 * pedir a página, resolver os desafios do site). Quando o YouTube não quer entregar assim (vídeo que pede login, restrição de idade, live, etc.)
 * devolve `null` e o app segue pelo yt-dlp, como sempre foi.
 *
 * O resultado vem sem legendas e sem a contagem de curtidas (isso só o yt-dlp traz): `complete = false` avisa a sessão de reprodução, que
 * completa esses dados em segundo plano com o yt-dlp, enquanto o vídeo já toca.
 */
class YouTubeFast(private val tube: InnerTube) {
    suspend fun resolve(media: Media): Resolved? {
        // Clipes e lives têm caminho próprio (a live só tem lista HLS com o código do site); um endereço de vídeo tem sempre 11 caracteres.
        if (media.isClip || media.isLive || media.id.length != VIDEO_ID_LENGTH) return null
        val root = runCatching {
            tube.callAs(InnerClient.Ios, "player") {
                put("videoId", media.id)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
        }.getOrNull() ?: return null
        return parse(root, media)
    }

    internal fun parse(root: JsonElement, media: Media): Resolved? {
        if (root.path("playabilityStatus", "status").str() != "OK") return null
        val details = root["videoDetails"]
        // Live no ar (ou marcada para começar): só o yt-dlp sabe abrir. Transmissão que já acabou (isLiveContent) é um vídeo como outro qualquer.
        if (details["isLive"].bool() == true || details["isUpcoming"].bool() == true) return null
        val streaming = root["streamingData"]
        val qualities = buildQualities(streaming["adaptiveFormats"].list(), streaming["formats"].list())
        if (qualities.isEmpty()) return null

        val channelId = details["channelId"].str() ?: media.channel?.id
        val channel = channelId?.let {
            Channel(
                Platform.YouTube, it, details["author"].str() ?: media.channel?.name.orEmpty(), media.channel?.avatarUrl,
                media.channel?.handle, media.channel?.followers, media.channel?.verified == true, "https://www.youtube.com/channel/$it",
            )
        } ?: media.channel
        val description = details["shortDescription"].str()
        val micro = root.path("microformat", "playerMicroformatRenderer")
        val uploaded = micro["uploadDate"].str()
        val updated = media.copy(
            title = details["title"].str() ?: media.title,
            channel = channel,
            durationSec = details["lengthSeconds"].str()?.toLongOrNull() ?: media.durationSec,
            viewCount = details["viewCount"].str()?.toLongOrNull() ?: media.viewCount,
            publishedAt = parseIsoMillis(micro["publishDate"].str() ?: uploaded) ?: media.publishedAt,
            isLive = false,
        )
        return Resolved(
            media = updated,
            description = description,
            likes = null,
            subscribers = channel?.followers,
            uploadDate = uploaded?.takeIf { it.length >= 10 }?.take(10)?.replace("-", ""),
            chapters = chaptersFrom(description),
            subtitles = emptyList(),
            qualities = qualities,
            userAgent = InnerClient.Ios.userAgent,
            tags = details["keywords"].list().mapNotNull { it.str() },
            isLive = false,
            complete = false,
            rangeBridge = true,
        )
    }

    private fun mime(f: JsonElement) = f["mimeType"].str().orEmpty()
    private fun codecs(f: JsonElement) = mime(f).substringAfter("codecs=\"", "").substringBefore('"')

    private fun codecRank(codec: String, height: Int): Int = when {
        codec.startsWith("avc1") -> if (height <= 1080) 3 else 1
        codec.startsWith("vp09") || codec.startsWith("vp9") -> if (height > 1080) 3 else 2
        codec.startsWith("av01") -> if (height > 1080) 2 else 1
        else -> 0
    }

    private fun label(height: Int, fps: Int?): String = "${height}p" + if (fps != null && fps > 30) "$fps" else ""

    private fun buildQualities(adaptive: List<JsonElement>, muxed: List<JsonElement>): List<Quality> {
        // O áudio: o de maior qualidade da faixa principal (o YouTube também oferece dublagens e uma versão "com volume nivelado").
        val audioUrl = adaptive.filter {
            mime(it).startsWith("audio/") && it["url"].str() != null && it["isDrc"].bool() != true && it["audioTrack"]["audioIsDefault"].bool() != false
        }.maxByOrNull { it["bitrate"].int() ?: 0 }?.get("url").str()

        val byHeight = linkedMapOf<Int, Quality>()
        if (audioUrl != null) {
            adaptive.filter { mime(it).startsWith("video/") && it["url"].str() != null && (it["height"].int() ?: 0) > 0 }
                .groupBy { it["height"].int() ?: 0 }
                .forEach { (h, list) ->
                    val best = list.sortedWith(
                        compareBy<JsonElement> { codecRank(codecs(it), h) }.thenBy { it["fps"].int() ?: 0 }.thenBy { it["bitrate"].int() ?: 0 },
                    ).last()
                    byHeight[h] = Quality(label(h, best["fps"].int()), h, best["url"].str()!!, audioUrl, best["fps"].int())
                }
        }
        // Os que já vêm com áudio junto (360p), para as alturas que faltam.
        muxed.filter { it["url"].str() != null && (it["height"].int() ?: 0) > 0 }.forEach { f ->
            val h = f["height"].int() ?: return@forEach
            if (h !in byHeight) byHeight[h] = Quality(label(h, f["fps"].int()), h, f["url"].str()!!, null, f["fps"].int())
        }
        return byHeight.values.sortedByDescending { it.height }
    }

    private companion object {
        const val VIDEO_ID_LENGTH = 11
        val CHAPTER_LINE = Regex("""^\s*(?:[-•▶►]\s*)?((?:\d{1,2}:)?\d{1,2}:\d{2})\s*[-–—:)]?\s+(.+?)\s*$""")
    }

    /** Os capítulos que o autor escreveu na descrição ("0:00 Introdução"): valem quando começam em 0:00, são pelo menos três e crescem. */
    internal fun chaptersFrom(description: String?): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()
        val found = description.lines().mapNotNull { line ->
            val m = CHAPTER_LINE.find(line) ?: return@mapNotNull null
            val start = parseClock(m.groupValues[1]) ?: return@mapNotNull null
            Chapter(m.groupValues[2], start)
        }
        if (found.size < 3 || found.first().startSec != 0L) return emptyList()
        if (found.zipWithNext().any { (a, b) -> b.startSec <= a.startSec }) return emptyList()
        return found
    }
}
