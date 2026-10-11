package app.apex.player

import app.apex.source.getText
import app.apex.util.parseIsoMillis
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.ceil

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
                segments += LiveSegment(
                    absolute(baseUrl, line.substringAfter(':').trim()), last.duration, false, map,
                    previous.startMs?.plus((previous.duration * 1000).toLong()), prefetch = true,
                )
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

/**
 * Os trechos que o VLC vai enxergar: os [keep] últimos completos e, se a plataforma adianta trechos (Twitch com `fast_bread`), os adiantados
 * junto, só que então os completos são [keepWithPrefetch] (os adiantados já cobrem o "ao vivo").
 */
internal fun keptSegments(playlist: LivePlaylist, keep: Int, keepWithPrefetch: Int): List<LiveSegment> {
    val complete = playlist.segments.filter { !it.prefetch }
    val ahead = playlist.segments.filter { it.prefetch }
    return complete.takeLast((if (ahead.isEmpty()) keep else keepWithPrefetch).coerceAtLeast(if (ahead.isEmpty()) 1 else 0)) + ahead
}

/**
 * A mesma live só com os últimos [keep] trechos e `TARGETDURATION` verdadeiro (o da Twitch diz 6 s para trechos de 2 s). O VLC começa uma live 3
 * trechos antes do fim (a regra do HLS) e consulta a lista só a cada meio `TARGETDURATION`: dando a ele uma lista curta, o atraso cai de ~7 s para ~3 s.
 */
internal fun trimLivePlaylist(playlist: LivePlaylist, keep: Int, keepWithPrefetch: Int = keep): String {
    val kept = keptSegments(playlist, keep, keepWithPrefetch)
    val firstSequence = playlist.mediaSequence + playlist.segments.indexOf(kept.first())
    return buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:${playlist.version}")
        appendLine("#EXT-X-TARGETDURATION:${ceil(kept.maxOf { it.duration }).toInt().coerceAtLeast(1)}")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$firstSequence")
        var currentMap: String? = null
        kept.forEachIndexed { i, seg ->
            if (seg.discontinuity && i > 0) appendLine("#EXT-X-DISCONTINUITY")
            if (seg.map != currentMap || i == 0) {
                seg.map?.let { appendLine("#EXT-X-MAP:URI=\"$it\"") }
                currentMap = seg.map
            }
            appendLine("#EXTINF:${"%.3f".format(java.util.Locale.ROOT, seg.duration)},")
            appendLine(seg.uri)
        }
        if (playlist.ended) appendLine("#EXT-X-ENDLIST")
    }
}

private fun absolute(base: String, ref: String): String {
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    val cut = base.substringBefore('?').substringBeforeLast('/', "")
    return if (ref.startsWith("/")) base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
    else "$cut/$ref"
}

/** Quanto atraso a lista cortada tem a menos que a lista inteira, estimado no momento em que ela foi aberta (para medir). */
internal class EdgeEstimate(val trimmedStartServerMs: Long, val directStartServerMs: Long, val skewMs: Long)

/**
 * Um servidorzinho local que entrega ao VLC a lista da live já cortada ([trimLivePlaylist]); os trechos de vídeo o VLC continua baixando
 * direto da plataforma. Atualiza a lista a cada [POLL_MS]. Só aceita conexões do próprio computador.
 */
internal class LiveEdgeProxy(
    private val scope: CoroutineScope, private val http: HttpClient, private val keep: Int, private val keepWithPrefetch: Int = keep,
) {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    @Volatile private var body: String = ""
    private var poller: Job? = null
    @Volatile var estimate: EdgeEstimate? = null
        private set

    val url: String get() = "http://127.0.0.1:${server.localPort}/live.m3u8"

    /** Busca a primeira lista e começa a servir. `false` se [upstreamUrl] não é a lista de uma qualidade (ou não respondeu). */
    suspend fun start(upstreamUrl: String, headers: Map<String, String>): Boolean {
        val sent = System.currentTimeMillis()
        val text = runCatching { http.getText(upstreamUrl, headers) }.getOrNull() ?: return false
        val received = System.currentTimeMillis()
        val playlist = parseLivePlaylist(text, upstreamUrl) ?: return false
        // Sem trechos adiantados (o que a Twitch e a Kick entregam) a lista curta só traz travadas: nesses casos o VLC toca direto.
        if (playlist.ended || playlist.segments.none { it.prefetch }) return false
        body = trimLivePlaylist(playlist, keep, keepWithPrefetch)
        playlist.serverTimeMs?.let { server ->
            // quanto o relógio deste PC está adiantado em relação ao do servidor (no meio da ida e volta)
            val skew = (sent + received) / 2 - server
            val kept = keptSegments(playlist, keep, keepWithPrefetch)
            val direct = playlist.segments.filter { !it.prefetch }.takeLast(3).first()
            val a = kept.first().startMs
            val b = direct.startMs
            if (a != null && b != null) estimate = EdgeEstimate(a, b, skew)
        }
        Thread { accept() }.also { it.isDaemon = true; it.name = "apex-live-edge" }.start()
        poller = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val next = runCatching { http.getText(upstreamUrl, headers) }.getOrNull() ?: continue
                parseLivePlaylist(next, upstreamUrl)?.let { body = trimLivePlaylist(it, keep, keepWithPrefetch) }
            }
        }
        return true
    }

    fun close() {
        poller?.cancel()
        runCatching { server.close() }
    }

    private fun accept() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            Thread { serve(socket) }.also { it.isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 5_000
                val reader = s.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                while (reader.readLine().orEmpty().isNotEmpty()) { /* cabeçalhos: ignorados */ }
                val out = s.getOutputStream()
                if (!request.startsWith("GET /live.m3u8")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nCache-Control: no-cache\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                    )
                    out.write(bytes)
                }
                out.flush()
            }
        }
    }

    companion object {
        const val POLL_MS = 500L
    }
}
