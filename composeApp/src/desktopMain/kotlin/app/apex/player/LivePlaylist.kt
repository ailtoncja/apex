package app.apex.player

import app.apex.source.getText
import app.apex.util.parseIsoMillis
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay

/** Um trecho da live (HLS) como a lista o descreve. */
internal class LiveSegment(
    val uri: String,
    val duration: Double,
    /** A lista marcou uma descontinuidade (troca de arquivo de vídeo, como num anúncio) antes dele. */
    val discontinuity: Boolean,
    /** O arquivo de inicialização (`EXT-X-MAP`) que vale para ele (vídeo fMP4); `null` em MPEG-TS. */
    val map: String?,
    /** Hora (do relógio do servidor) em que o trecho começa, em ms, quando a lista informa. */
    val startMs: Long?,
    /** Trecho "adiantado" (`EXT-X-TWITCH-PREFETCH` na Twitch, `EXT-X-PREFETCH` na Kick): ainda está sendo gerado, e o servidor o entrega aos poucos conforme fica pronto. */
    val prefetch: Boolean = false,
)

internal class LivePlaylist(val version: Int, val mediaSequence: Long, val segments: List<LiveSegment>, val serverTimeMs: Long?, val ended: Boolean)

private val SERVER_TIME = Regex("""X-SERVER-TIME="([0-9.]+)"""")
private val FRACTION = Regex("""T\d{2}:\d{2}:\d{2}\.(\d{1,3})""")

/** `2026-10-08T17:25:25.591Z` em ms (o `parseIsoMillis` do app ignora a fração). */
internal fun isoMillisPrecise(text: String): Long? {
    val whole = parseIsoMillis(text) ?: return null
    val fraction = FRACTION.find(text)?.groupValues?.get(1)?.padEnd(3, '0')?.toLongOrNull() ?: 0
    return whole + fraction
}

/**
 * Lê a lista de uma live HLS (a lista de UMA qualidade, com os trechos). Devolve `null` se for a lista mestra (que só aponta outras listas) ou
 * se não houver trecho nenhum. Os endereços relativos viram absolutos a partir de [baseUrl].
 */
internal fun parseLivePlaylist(text: String, baseUrl: String): LivePlaylist? {
    val lines = text.lines().map { it.trim() }
    if (lines.firstOrNull() != "#EXTM3U" || lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return null
    var version = 3
    var sequence = 0L
    var serverTime: Long? = null
    var ended = false
    var map: String? = null
    var pendingDuration = 0.0
    var pendingDiscontinuity = false
    var pendingStart: Long? = null
    val segments = mutableListOf<LiveSegment>()
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-VERSION:") -> version = line.substringAfter(':').toIntOrNull() ?: version
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
            line.startsWith("#EXT-X-ENDLIST") -> ended = true
            line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> pendingDiscontinuity = true
            line.startsWith("#EXT-X-MAP:") -> map = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { absolute(baseUrl, it) }
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pendingStart = isoMillisPrecise(line.substringAfter(':'))
            line.startsWith("#EXT-X-TWITCH-PREFETCH:") || line.startsWith("#EXT-X-PREFETCH:") -> {
                val last = segments.lastOrNull { !it.prefetch } ?: continue
                val previous = segments.last()
                // A Twitch marca a troca entre a live e um anúncio também antes de um trecho adiantado (`…PP` `#EXT-X-DISCONTINUITY` `P`): sem
                // a marca o VLC vê o relógio do vídeo saltar e para de tocar.
                segments += LiveSegment(
                    absolute(baseUrl, line.substringAfter(':').trim()), last.duration, pendingDiscontinuity, map,
                    previous.startMs?.plus((previous.duration * 1000).toLong()), prefetch = true,
                )
                pendingDiscontinuity = false
            }
            line.startsWith("#EXT-X-DATERANGE") && serverTime == null ->
                serverTime = SERVER_TIME.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
            line.isNotEmpty() && !line.startsWith("#") -> {
                segments += LiveSegment(absolute(baseUrl, line), pendingDuration, pendingDiscontinuity, map, pendingStart)
                pendingDiscontinuity = false
                pendingStart = pendingStart?.plus((pendingDuration * 1000).toLong())
            }
        }
    }
    if (segments.isEmpty()) return null
    return LivePlaylist(version, sequence, segments, serverTime, ended)
}

private fun absolute(base: String, ref: String): String {
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    val cut = base.substringBefore('?').substringBeforeLast('/', "")
    return if (ref.startsWith("/")) base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
    else "$cut/$ref"
}

/**
 * Espera a lista de [upstreamUrl] ficar [ready] (com trechos adiantados e sem o fim de um anúncio no caminho), consultando a cada [everyMs] por até
 * [maxMs]: `true` quando fica; `false` se não ficou, se a live acabou ou se [stillWanted] deixou de valer.
 */
internal suspend fun waitForPrefetch(
    http: HttpClient, upstreamUrl: String, headers: Map<String, String>, ready: (LivePlaylist) -> Boolean,
    everyMs: Long, maxMs: Long, stillWanted: () -> Boolean,
): Boolean {
    val until = System.currentTimeMillis() + maxMs
    while (System.currentTimeMillis() < until) {
        delay(everyMs)
        if (!stillWanted()) return false
        val text = runCatching { http.getText(upstreamUrl, headers) }.getOrNull() ?: continue
        val playlist = parseLivePlaylist(text, upstreamUrl) ?: return false
        if (playlist.ended) return false
        if (ready(playlist)) return stillWanted()
    }
    return false
}

/**
 * O relógio deste PC menos o do servidor, em ms. Cada resposta traz no cabeçalho `Date` a hora do servidor em segundos inteiros, e ela foi gerada
 * entre o envio e a chegada: o desvio fica numa faixa de 1 s mais a ida e volta, que encolhe a cada resposta (em poucos segundos, para perto da ida
 * e volta). O relógio do PC sozinho não serve: pode estar vários segundos errado. (O `X-SERVER-TIME` da lista da Twitch também não: é a hora em
 * que a sessão foi criada, e envelhece.)
 */
internal class ServerClock {
    private var low = Long.MIN_VALUE
    private var high = Long.MAX_VALUE

    /** O servidor disse [serverSecondMs] (o segundo inteiro) numa resposta pedida em [sentMs] e recebida em [receivedMs] (relógio do PC). */
    @Synchronized
    fun note(serverSecondMs: Long, sentMs: Long, receivedMs: Long) {
        val lo = sentMs - serverSecondMs - 999
        val hi = receivedMs - serverSecondMs
        val newLow = maxOf(low, lo)
        val newHigh = minOf(high, hi)
        // Faixas que não se cruzam: o relógio do PC foi acertado no meio do caminho. Vale a resposta nova.
        if (newLow > newHigh) { low = lo; high = hi } else { low = newLow; high = newHigh }
    }

    /** `null` enquanto nenhuma resposta trouxe a hora. */
    val skewMs: Long? @Synchronized get() = if (low == Long.MIN_VALUE) null else (low + high) / 2
}
