package app.apex.player

import app.apex.source.getText
import app.apex.util.parseIsoMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
 * Dá para abrir a live pela lista curta agora? Precisa de trechos adiantados e de que os trechos que o VLC vai ver sejam todos do mesmo vídeo: logo
 * depois de um anúncio, o último trecho completo ainda é dele (`…AA` `#EXT-X-DISCONTINUITY` `PP`), e o VLC, abrindo nesse ponto, não toca.
 */
internal fun readyForEdge(playlist: LivePlaylist, keep: Int, keepWithPrefetch: Int): Boolean =
    !playlist.ended && playlist.segments.any { it.prefetch } && keptSegments(playlist, keep, keepWithPrefetch).drop(1).none { it.discontinuity }

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

/** Onde (na hora do servidor) começam o trecho em que o VLC abre pela lista curta e o trecho em que ele abriria pela lista inteira (para medir). */
internal class EdgeEstimate(val trimmedStartServerMs: Long, val directStartServerMs: Long)

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

/**
 * Um servidorzinho local que entrega ao VLC a lista da live já cortada ([trimLivePlaylist]); os trechos de vídeo o VLC continua baixando
 * direto da plataforma. Atualiza a lista a cada [POLL_MS]. Só aceita conexões do próprio computador.
 */
internal class LiveEdgeProxy(
    private val scope: CoroutineScope, private val http: HttpClient, private val keep: Int, private val keepWithPrefetch: Int = keep,
) : LowLatencyFeed {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    @Volatile private var body: String = ""
    private var poller: Job? = null
    @Volatile var estimate: EdgeEstimate? = null
        private set

    override val startServerMs: Long? get() = estimate?.trimmedStartServerMs

    /** A hora do servidor, acertada a cada consulta da lista. */
    override val clock = ServerClock()

    override var failure: String? = null
        private set

    /**
     * [start] recusou só porque a lista (ainda) não tem trechos adiantados. Na Twitch isso acontece no anúncio do começo: a lista dele não adianta
     * nada, e a da live, que vem depois, sim. Ver [waitForPrefetch].
     */
    override var noPrefetch = false
        private set
    override var inAd = false
        private set

    /** Caminho com um trecho aleatório: outro programa (ou uma página no navegador) não acha a lista chutando o endereço. */
    private val path = "/${LocalHttp.newToken()}/live.m3u8"

    override val url: String get() = "http://127.0.0.1:${server.localPort}$path"

    /** Busca a primeira lista e começa a servir. `false` se [upstreamUrl] não é a lista de uma qualidade (ou não respondeu). */
    override suspend fun start(upstreamUrl: String, headers: Map<String, String>): Boolean {
        val text = runCatching { fetch(upstreamUrl, headers) }
            .onFailure { failure = "a lista não veio (${it::class.simpleName})" }.getOrNull() ?: return false
        val playlist = parseLivePlaylist(text, upstreamUrl) ?: run { failure = "não é a lista de uma qualidade"; return false }
        if (playlist.ended) { failure = "a live acabou"; return false }
        inAd = isTwitchAd(text)
        // Sem trechos adiantados (o que a Twitch e a Kick entregam) a lista curta só traz travadas: nesses casos o VLC toca direto.
        if (!readyForEdge(playlist, keep, keepWithPrefetch)) {
            failure = if (playlist.segments.none { it.prefetch }) "sem trechos adiantados" else "logo depois de um anúncio"
            noPrefetch = true
            return false
        }
        body = trimLivePlaylist(playlist, keep, keepWithPrefetch)
        val a = keptSegments(playlist, keep, keepWithPrefetch).first().startMs
        val b = playlist.segments.filter { !it.prefetch }.takeLast(3).first().startMs
        if (a != null && b != null) estimate = EdgeEstimate(a, b)
        Thread { accept() }.also { it.isDaemon = true; it.name = "apex-live-edge" }.start()
        poller = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val next = runCatching { fetch(upstreamUrl, headers) }.getOrNull() ?: continue
                parseLivePlaylist(next, upstreamUrl)?.let { body = trimLivePlaylist(it, keep, keepWithPrefetch) }
            }
        }
        return true
    }

    /** Busca a lista e, de passagem, acerta [clock] pelo cabeçalho `Date` da resposta. */
    private suspend fun fetch(url: String, headers: Map<String, String>): String {
        val sent = System.currentTimeMillis()
        val response = http.get(url) { headers.forEach { (k, v) -> header(k, v) } }
        val received = System.currentTimeMillis()
        response.headers[HttpHeaders.Date]?.let { parseHttpDate(it) }?.let { clock.note(it, sent, received) }
        return response.bodyAsText()
    }

    override fun close() {
        poller?.cancel()
        runCatching { server.close() }
    }

    private fun accept() = LocalHttp.acceptLoop(server, "apex-live-edge-conn", MAX_CONNECTIONS, { server.isClosed }) { serve(it) }

    private fun serve(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 5_000
                val request = LocalHttp.readRequest(s.getInputStream().buffered())
                val out = s.getOutputStream()
                if (request == null) {
                    LocalHttp.respondEmpty(s, "400 Bad Request")
                } else if (!LocalHttp.isLocalHost(request.header("Host"), server.localPort)) {
                    // Contra "DNS rebinding": uma página que aponta o domínio dela para 127.0.0.1 manda o domínio dela no Host.
                    LocalHttp.respondEmpty(s, "403 Forbidden")
                } else if (request.method != "GET" || request.path != path) {
                    LocalHttp.respondEmpty(s, "404 Not Found")
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

        /** Conexões atendidas ao mesmo tempo (o VLC pede a lista uma vez por vez). */
        const val MAX_CONNECTIONS = 8
    }
}
