package app.apex.player

import app.apex.util.Timing
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * A live entregue ao VLC como um fluxo só, sem lista. O app consulta a lista da plataforma e baixa os trechos em ordem, inclusive os
 * adiantados, que a plataforma manda aos poucos enquanto os gera, e repassa cada pedaço ao VLC assim que chega: é o que o player do site da Twitch
 * faz. Pela lista da plataforma o VLC espera cada trecho de 2 s inteiro antes de pedir o próximo e só relê a lista a cada 1 s; começando no
 * trecho mais novo, travava a cada trecho.
 *
 * Começa [startBack] trechos antes do primeiro adiantado (0 = no trecho que está sendo gerado agora). Serve trechos MPEG-TS e fMP4 (a Twitch usa os
 * dois, conforme o canal): no fMP4 o arquivo de início (`EXT-X-MAP`) vai antes, e o resto é a sequência de fragmentos. Só aceita conexões do
 * próprio computador, como os outros servidorzinhos ([LocalHttp]).
 *
 * Numa troca de vídeo (entrada ou saída de um anúncio: o trecho vem marcado, ou a numeração da lista muda) o relógio do vídeo salta, e num fluxo
 * só o VLC pode congelar. Aí o fluxo para e chama [onBreak]: o player reabre a live, que no anúncio toca pela lista da plataforma e, quando ele
 * acaba, volta para cá. O mesmo se o fluxo ficou para trás da lista (pausa longa): reabrir leva ao "ao vivo".
 */
internal class LiveTsRelay(
    private val scope: CoroutineScope, private val http: HttpClient, private val startBack: Int = RELAY_START_BACK,
    private val onBreak: () -> Unit = {},
) {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val path = "/${LocalHttp.newToken()}/live"
    /** O endereço local que o VLC abre. */
    val url: String get() = "http://127.0.0.1:${server.localPort}$path"
    /** A hora do servidor, para medir o atraso. */
    val clock = ServerClock()

    /** Hora do servidor em que começa o trecho que o VLC mostra no primeiro quadro; `null` se a lista não informa. */
    @Volatile var startServerMs: Long? = null
        private set
    /** Por que [start] devolveu `false` (para o registro de tempos). */
    var failure: String? = null
        private set
    /** [start] recusou só porque a lista ainda não está pronta (anúncio da Twitch): vale esperar ([waitForPrefetch]). */
    var noPrefetch = false
        private set
    /** A lista que [start] viu era de um anúncio da Twitch: enquanto ele passa, o VLC toca com o buffer normal (com o mínimo, travava). */
    var inAd = false
        private set

    /** O vídeo é fMP4 (com arquivo de início), não MPEG-TS: o VLC lê o fluxo como MP4. */
    @Volatile var fmp4 = false
        private set

    /** O arquivo de início já repassado à conexão de agora (fMP4); `null` = ainda não (ou o VLC reconectou e precisa dele de novo). */
    @Volatile private var initSent: String? = null

    private var upstreamUrl = ""
    private var requestHeaders: Map<String, String> = emptyMap()

    /** A lista mais nova (o [poller] troca a cada [POLL_MS]). */
    @Volatile private var latest: LivePlaylist? = null

    /** O número (`MEDIA-SEQUENCE`) do próximo trecho a repassar. */
    @Volatile private var nextSequence = -1L
    private var poller: Job? = null
    private var pump: Job? = null

    /** A conexão do VLC que recebe o fluxo agora (uma só: se ele reconecta, a nova assume e a velha é fechada). */
    private class Client(val socket: Socket, val out: OutputStream) { val done = CountDownLatch(1) }
    private val clientLock = Object()
    private var client: Client? = null

    suspend fun start(upstreamUrl: String, headers: Map<String, String>): Boolean {
        this.upstreamUrl = upstreamUrl
        this.requestHeaders = headers
        val text = runCatching { fetch(upstreamUrl) }.onFailure { failure = "a lista não veio (${it::class.simpleName})" }.getOrNull() ?: return false
        val playlist = parseLivePlaylist(text, upstreamUrl) ?: run { failure = "não é a lista de uma qualidade"; return false }
        if (playlist.ended) { failure = "a live acabou"; return false }
        inAd = isTwitchAd(text)
        val start = relayStart(playlist, startBack) ?: run {
            failure = if (playlist.segments.none { it.prefetch }) "sem trechos adiantados" else "logo depois de um anúncio"
            noPrefetch = true
            return false
        }
        latest = playlist
        nextSequence = playlist.mediaSequence + start
        startServerMs = playlist.segments[start].startMs
        fmp4 = playlist.segments[start].map != null
        Thread { LocalHttp.acceptLoop(server, "apex-live-relay-conn", MAX_CONNECTIONS, { server.isClosed }) { serve(it) } }
            .also { it.isDaemon = true; it.name = "apex-live-relay" }.start()
        poller = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val next = runCatching { fetch(upstreamUrl) }.getOrNull() ?: continue
                parseLivePlaylist(next, upstreamUrl)?.let { latest = it }
            }
        }
        return true
    }

    fun close() {
        poller?.cancel()
        pump?.cancel()
        runCatching { server.close() }
        synchronized(clientLock) { client?.let { drop(it) }; client = null }
    }

    /** Busca a lista e, de passagem, acerta [clock] pelo cabeçalho `Date` da resposta. */
    private suspend fun fetch(url: String): String {
        val sent = System.currentTimeMillis()
        val response = http.get(url) { requestHeaders.forEach { (k, v) -> header(k, v) } }
        val received = System.currentTimeMillis()
        response.headers[HttpHeaders.Date]?.let { parseHttpDate(it) }?.let { clock.note(it, sent, received) }
        return response.bodyAsText()
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val request = runCatching { s.soTimeout = 5_000; LocalHttp.readRequest(s.getInputStream().buffered()) }.getOrNull()
            when {
                request == null -> return LocalHttp.respondEmpty(s, "400 Bad Request")
                // Contra "DNS rebinding": uma página que aponta o domínio dela para 127.0.0.1 manda o domínio dela no Host.
                !LocalHttp.isLocalHost(request.header("Host"), server.localPort) -> return LocalHttp.respondEmpty(s, "403 Forbidden")
                request.method != "GET" || request.path != path -> return LocalHttp.respondEmpty(s, "404 Not Found")
            }
            val out = runCatching {
                s.soTimeout = 0
                s.getOutputStream().also {
                    // Sem tamanho: um fluxo sem fim, que o VLC trata como "ao vivo" (sem pular para trás nem para a frente).
                    val type = if (fmp4) "video/mp4" else "video/mp2t"
                    it.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
                    it.flush()
                }
            }.getOrNull() ?: return
            val mine = Client(s, out)
            synchronized(clientLock) {
                client?.let { drop(it) }
                client = mine
                // Conexão nova (o VLC reconectou): no fMP4 ela precisa do arquivo de início antes de tudo.
                initSent = null
                if (pump == null) pump = scope.launch { pumpLoop() }
            }
            // A conexão fica aberta enquanto o fluxo vai para ela.
            mine.done.await()
        }
    }

    private fun drop(c: Client) {
        c.done.countDown()
        runCatching { c.socket.close() }
    }

    /** Repassa [n] bytes ao VLC; `false` se não há conexão (ou ela caiu). */
    private fun send(buffer: ByteArray, n: Int): Boolean {
        val c = synchronized(clientLock) { client } ?: return false
        return try {
            c.out.write(buffer, 0, n)
            c.out.flush()
            true
        } catch (_: IOException) {
            synchronized(clientLock) { if (client === c) client = null }
            drop(c)
            false
        }
    }

    private suspend fun pumpLoop() {
        val buffer = ByteArray(64 * 1024)
        while (scope.isActive && !server.isClosed) {
            val playlist = latest ?: return
            val index = nextSequence - playlist.mediaSequence
            // O trecho esperado não está na lista nem é o próximo dela: a Twitch troca a numeração ao entrar e sair de um anúncio (a lista dele
            // começa do 0), ou o fluxo ficou parado (pausa) e a lista andou.
            if (index < 0 || index > playlist.segments.size) {
                return breakOff("esperava o trecho $nextSequence, a lista vai de ${playlist.mediaSequence} a ${playlist.mediaSequence + playlist.segments.size - 1}")
            }
            if (index == playlist.segments.size.toLong()) { delay(WAIT_NEXT_MS); continue }
            val segment = playlist.segments[index.toInt()]
            if (segment.discontinuity) return breakOff("troca de vídeo no trecho $nextSequence (anúncio)")
            // fMP4: o arquivo de início vai antes do primeiro fragmento (e de novo se o VLC reconectou). Se ele muda no meio, o vídeo mudou de
            // formato: como numa troca de vídeo, a live é reaberta.
            if ((segment.map != null) != fmp4) return breakOff("o vídeo mudou de formato no trecho $nextSequence")
            val init = segment.map
            if (init != null && init != initSent) {
                if (initSent != null && synchronized(clientLock) { client } != null) return breakOff("arquivo de início novo no trecho $nextSequence")
                if (!relaySegment(init, buffer)) { delay(WAIT_NEXT_MS); continue }
                initSent = init
            }
            val t0 = System.currentTimeMillis()
            val sent = relaySegment(segment.uri, buffer)
            if (Timing.enabled) {
                Timing.mark("fluxo: trecho $nextSequence${if (segment.prefetch) " (adiantado)" else ""} em ${System.currentTimeMillis() - t0} ms${if (!sent) " (VLC desconectado)" else ""}")
            }
            if (!sent) {
                // O VLC desconectou: espera ele voltar (ou o player fechar tudo).
                if (synchronized(clientLock) { client } == null) { delay(WAIT_NEXT_MS); continue }
            }
            nextSequence++
        }
    }

    /** Para o fluxo e devolve a live ao player ([onBreak]). */
    private fun breakOff(why: String) {
        Timing.mark("fluxo da live: parou ($why), reabrindo")
        onBreak()
    }

    /** Baixa o trecho [uri] repassando cada pedaço; `false` se o VLC não está recebendo. Um trecho que não vem (erro da plataforma) é pulado. */
    private suspend fun relaySegment(uri: String, buffer: ByteArray): Boolean {
        repeat(SEGMENT_TRIES) { attempt ->
            var delivered = true
            var ok = false
            runCatching {
                http.prepareGet(uri) { requestHeaders.forEach { (k, v) -> header(k, v) } }.execute { response ->
                    if (!response.status.isSuccess()) return@execute
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val n = channel.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n > 0 && !send(buffer, n)) { delivered = false; break }
                    }
                    ok = true
                }
            }
            if (!delivered) return false
            if (ok) return true
            if (attempt < SEGMENT_TRIES - 1) delay(RETRY_MS)
        }
        Timing.mark("fluxo da live: trecho não veio, pulado")
        return true
    }

    companion object {
        const val POLL_MS = 500L
        const val WAIT_NEXT_MS = 100L
        const val SEGMENT_TRIES = 3
        const val RETRY_MS = 300L

        /** O VLC e, se ele reconectar, a conexão nova. */
        const val MAX_CONNECTIONS = 4
    }
}

/** Trechos antes do primeiro adiantado onde o fluxo começa (ver [LiveTsRelay]). */
internal const val RELAY_START_BACK = 0

/**
 * Onde começar o fluxo em [playlist]: o índice do trecho [startBack] antes do primeiro adiantado. `null` se não há adiantados ou se, do trecho
 * completo anterior ao início até o fim, há uma troca de vídeo: logo depois de um anúncio o trecho completo ainda é dele, e a hora de um adiantado
 * (que a lista não diz) sai do completo anterior; do anúncio ela viria errada. Aí ainda não é hora: cerca de 2 s depois já é.
 */
internal fun relayStart(playlist: LivePlaylist, startBack: Int): Int? {
    val firstAhead = playlist.segments.indexOfFirst { it.prefetch }.takeIf { it >= 0 } ?: return null
    val start = (firstAhead - startBack).coerceAtLeast(0)
    // A troca antes do próprio início só importa se ele é adiantado (a hora dele sai do trecho anterior); um completo tem a hora dele.
    val first = playlist.segments[start]
    if ((first.prefetch && first.discontinuity) || playlist.segments.drop(start + 1).any { it.discontinuity }) return null
    return start
}

/** A lista da Twitch está num anúncio (ela marca o intervalo com um `EXT-X-DATERANGE` da classe `twitch-stitched-ad`). */
internal fun isTwitchAd(playlistText: String): Boolean = "twitch-stitched-ad" in playlistText

internal fun parseHttpDate(text: String): Long? =
    runCatching { java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
