package app.apex.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import app.apex.source.Http
import app.apex.util.FrameMeter
import app.apex.util.Timing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

/** Quantos trechos da live o VLC enxerga pela lista curta (2 s cada na Twitch): menos que isso trava ao menor soluço da internet. */
internal const val EDGE_KEEP = 2

/** Com trechos adiantados (Twitch), basta o último completo: os adiantados dão o resto. */
internal const val EDGE_KEEP_WITH_PREFETCH = 1

/** O buffer do VLC pela lista curta. */
internal const val EDGE_CACHE_MS = 500

private val DOWNSCALE: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
private val UPSCALE: SamplingMode = SamplingMode.CATMULL_ROM

/**
 * As opções do libVLC para tocar [source]. Numa live, o VLC por padrão começa 8 segmentos (~16 s) antes do "ao vivo" na Twitch (a lista
 * declara segmentos de 6 s) e ainda guarda 3 s; com [PlaySource.lowLatency] ele começa 3 segmentos (~6 s) antes, o mínimo que o VLC
 * aceita, e guarda 1 s (medido nos logs do próprio VLC: de ~19 s para ~7 s de atraso).
 */
internal fun vlcOptions(source: PlaySource, viaEdge: Boolean = false): List<String> = buildList {
    source.audioUrl?.let { add(":input-slave=$it") }
    source.userAgent?.let { add(":http-user-agent=$it") }
    if (source.startMs > 1000) add(":start-time=${source.startMs / 1000.0}")
    source.endMs?.let { add(":stop-time=${it / 1000.0}") }
    when {
        !source.live -> add(":network-caching=1500")
        // Pela lista curta ([LiveEdgeProxy]) o VLC já começa perto do fim: só o buffer pequeno.
        viaEdge -> add(":network-caching=$EDGE_CACHE_MS")
        source.lowLatency -> {
            add(":network-caching=1000")
            add(":adaptive-livedelay=3000")
        }
        else -> add(":network-caching=3000")
    }
}

/**
 * O tamanho real dos quadros. O VLC oferece ([offeredWidth] x [offeredHeight]) a "altura codificada" do vídeo, que costuma ser maior
 * (1080p vira 1088 e depois 1090). A faixa de vídeo ([trackWidth] x [trackHeight]) ou a altura da qualidade escolhida ([hintHeight]) dizem o
 * tamanho certo; só valem se forem parecidos com o que o VLC ofereceu (senão são de outro vídeo e o oferecido fica).
 */
internal fun trueFrameSize(offeredWidth: Int, offeredHeight: Int, trackWidth: Int, trackHeight: Int, hintHeight: Int): Pair<Int, Int> = when {
    trackWidth > 0 && trackHeight > 0 && kotlin.math.abs(trackWidth - offeredWidth) <= SIZE_SLACK_W && kotlin.math.abs(trackHeight - offeredHeight) <= SIZE_SLACK_H ->
        trackWidth to trackHeight
    hintHeight > 0 && kotlin.math.abs(hintHeight - offeredHeight) <= SIZE_SLACK_H -> offeredWidth to hintHeight
    else -> offeredWidth to offeredHeight
}

private const val SIZE_SLACK_W = 32
private const val SIZE_SLACK_H = 64

/**
 * O quadro BGRA [bytes] ([width] x [height]) tem alguma imagem? Espia uma grade de 8 x 8 pontos: se todos estão abaixo de [level] nos três
 * canais, é um quadro preto (como os que a decodificação por hardware entrega quando falha em silêncio).
 */
internal fun hasPicture(bytes: ByteArray, width: Int, height: Int, level: Int = 24): Boolean {
    if (width <= 0 || height <= 0 || bytes.size < width * height * 4) return false
    for (gy in 0 until 8) {
        val y = (height * (2 * gy + 1)) / 16
        for (gx in 0 until 8) {
            val x = (width * (2 * gx + 1)) / 16
            val i = (y * width + x) * 4
            if ((bytes[i].toInt() and 0xFF) >= level || (bytes[i + 1].toInt() and 0xFF) >= level || (bytes[i + 2].toInt() and 0xFF) >= level) return true
        }
    }
    return false
}

/** Onde o quadro é desenhado no campo [canvasWidth] x [canvasHeight]: o maior retângulo com a proporção do vídeo, centralizado. */
internal class VideoFit(val x: Int, val y: Int, val width: Int, val height: Int, val scale: Float)

internal fun fitVideo(canvasWidth: Float, canvasHeight: Float, imageWidth: Int, imageHeight: Int, pixelAspect: Float = 1f): VideoFit {
    val shownWidth = imageWidth * pixelAspect
    val scale = min(canvasWidth / shownWidth, canvasHeight / imageHeight)
    val w = (shownWidth * scale).roundToInt().coerceAtLeast(1)
    val h = (imageHeight * scale).roundToInt().coerceAtLeast(1)
    return VideoFit(((canvasWidth - w) / 2).roundToInt(), ((canvasHeight - h) / 2).roundToInt(), w, h, scale)
}

/**
 * Player do Windows: o libVLC decodifica e entrega cada quadro, que o Compose desenha por cima de tudo.
 *
 * Tudo o que fala com o libVLC passa por uma thread só deste player ([vlc]), uma coisa de cada vez e na ordem pedida, e nunca pela thread
 * da interface. No libVLC 3 parar e trocar de vídeo são síncronos e levam de dezenas de milissegundos a alguns segundos (esperam a rede e o
 * decodificador): feitos na interface, travavam a janela a cada vídeo fechado ou trocado. E duas threads mexendo no mesmo player ao mesmo
 * tempo (a interface parando enquanto outra abria, ou a recuperação sem imagem reabrindo) corrompiam o VLC e derrubavam o processo. Por isso
 * os métodos públicos voltam na hora: o [state] muda já, e o VLC acompanha em seguida, na ordem.
 */
class VlcPlayerController(hardwareDecode: Boolean = true) : PlayerController {
    private companion object {
        /** Relógio sem nenhum quadro de vídeo (com o VLC tocando e sem buffer a encher): passado isso, o vídeo é reaberto uma vez em [SAFE_MODE_OPTIONS]. */
        const val NO_PICTURE_MS = 6_000L
        const val CATCH_UP_START_MS = 1_500L
        const val CATCH_UP_STOP_MS = 400L
        const val CATCH_UP_MAX_MS = 20_000L
        const val CATCH_UP_RATE = 1.05f

        /** Quanto [release] espera o VLC soltar o player de fato (ao fechar o app, para o processo não sair no meio disso). */
        const val RELEASE_WAIT_MS = 1_500L

        /**
         * Depois de um pulo, o tempo que o VLC informa só volta a valer quando sai da linha do tempo antiga (mais de [SEEK_OLD_TOLERANCE_MS]
         * de diferença do que seria sem o pulo) ou passado [SEEK_WAIT_MS].
         */
        const val SEEK_OLD_TOLERANCE_MS = 1_500L
        const val SEEK_WAIT_MS = 3_000L

        /** Setas apertadas em sequência viram um pulo só: o VLC sai para o último pedido este tempo depois da última. */
        const val SEEK_DEBOUNCE_MS = 120L

        /** A cada quantos quadros a imagem é espiada para ver se é toda preta (ver [recoverIfNoPicture]). */
        const val BLACK_CHECK_EVERY = 8
        const val BLACK_LEVEL = 24
        val SAFE_MODE_OPTIONS = listOf(":avcodec-hw=none", ":no-avcodec-dr")

        /** Uma fábrica do libVLC por modo de decodificação, para todos os players (o principal e os do Multi): carregá-la é caro. */
        private val factories = HashMap<Boolean, MediaPlayerFactory>()
        private val ids = AtomicInteger()

        @Synchronized
        private fun sharedFactory(hardwareDecode: Boolean): MediaPlayerFactory = factories.getOrPut(hardwareDecode) {
            MediaPlayerFactory(
                "--no-video-title-show", "--no-osd", "--no-snapshot-preview", "--quiet",
                if (hardwareDecode) "--avcodec-hw=any" else "--avcodec-hw=none",
            )
        }
    }

    private val factory = sharedFactory(hardwareDecode)
    private val mediaPlayer: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    /**
     * A saída de som do app ([SoftAudio]): o VLC entrega o áudio decodificado e o app toca com um buffer curto, com o volume aplicado na hora.
     * `null` quando o Java Sound não abre neste PC: aí o VLC toca direto e o volume vai pelo libVLC (acima de 100% demora ~1 s).
     */
    private val softAudio: SoftAudio? = if (SoftAudio.available()) SoftAudio() else null

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    // ---------- a thread do VLC ----------

    /** A fila deste player: cada chamada ao libVLC roda aqui, uma de cada vez, na ordem em que foi pedida. */
    private val vlcThread = Executors.newSingleThreadExecutor { r -> Thread(r, "apex-vlc-${ids.incrementAndGet()}").apply { isDaemon = true } }
    @Volatile private var released = false

    /**
     * Cada pedido (abrir, parar, soltar) leva um número. O que a fila faz por um pedido só vale se ele ainda é o mais novo: abrir três vídeos
     * em seguida abre só o terceiro.
     */
    private val generation = AtomicInteger()

    /** O número do pedido do vídeo que está aberto no VLC agora: os eventos de um vídeo já trocado ou parado não mexem no [state]. */
    @Volatile private var playingGen = 0

    /** Os quadros que chegam valem. Entre um "parar" e a próxima abertura, os que ainda vêm do vídeo antigo são descartados. */
    @Volatile private var showing = false

    /** Põe [block] na fila do VLC. Depois de [release] não há mais fila: o pedido é ignorado. */
    private fun vlc(block: () -> Unit) {
        if (released) return
        runCatching {
            vlcThread.execute {
                if (!released) runCatching(block).onFailure { Timing.mark("VLC falhou: ${it.message}") }
            }
        }
    }

    /** O evento veio de um vídeo que já não é o pedido mais novo (foi trocado, parado ou solto). */
    private fun stale() = playingGen != generation.get()

    private val frameLock = Any()
    private var frame: Image? = null
    private var frameTick by mutableIntStateOf(0)
    private var imageInfo: ImageInfo? = null
    private var frameBytes = ByteArray(0)

    /** A velocidade pedida: o libVLC só aceita mudar depois que o vídeo começa, por isso é reaplicada no evento "tocando". */
    @Volatile private var desiredRate = 1f
    @Volatile private var lastSource: PlaySource? = null

    /** Clipe: o libVLC conta o tempo do arquivo inteiro; aqui ele é convertido para o do trecho. */
    @Volatile private var sectionStartMs = 0L
    @Volatile private var sectionEndMs: Long? = null

    /** A altura conhecida da qualidade tocando (0 = não se sabe): serve para corrigir o tamanho que o VLC oferece, ver [FormatCallback]. */
    @Volatile private var hintHeight = 0

    /** Largura de um pixel dividida pela altura (a "SAR" do vídeo; quase sempre 1). O VLC não a aplica ao entregar os quadros. */
    @Volatile private var pixelAspect = 1f

    @Volatile private var framesSincePlay = 0L
    @Volatile private var noPictureSinceMs = -1L

    /** Algum quadro entregue tinha imagem (não era todo preto). Ver [recoverIfNoPicture]. */
    @Volatile private var pictureSeen = false

    /** Trabalho de rede do player (a lista curta da live). */
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var edge: LiveEdgeProxy? = null
    @Volatile private var retriedWithoutPicture = false

    init {
        mediaPlayer.videoSurface().set(
            CallbackVideoSurface(FormatCallback(), FrameCallback(), true, VideoSurfaceAdapters.getVideoSurfaceAdapter()),
        )
        softAudio?.let { out ->
            mediaPlayer.audio().callback(SoftAudio.VLC_FORMAT, SoftAudio.RATE, SoftAudio.CHANNELS, object : AudioCallbackAdapter() {
                override fun play(mediaPlayer: MediaPlayer, samples: com.sun.jna.Pointer, sampleCount: Int, pts: Long) = out.push(samples, sampleCount)
                override fun flush(mediaPlayer: MediaPlayer, pts: Long) = out.flush()
            })
        }
        // Os eventos chegam na thread de eventos do libVLC: aqui só se mexe no estado; o que precisa falar com o VLC vai para a fila dele.
        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) {
                if (stale()) return
                Timing.mark("VLC abrindo")
                _state.update { it.copy(loading = true, ended = false, error = null) }
            }
            override fun playing(mediaPlayer: MediaPlayer) {
                if (stale()) return
                Timing.mark("VLC tocando")
                liveTimeStart = -1 // voltou a tocar (ou começou): a conta do atraso recomeça
                if (desiredRate != 1f || catchingUp) vlc { mediaPlayer.controls().setRate(desiredRate * if (catchingUp) CATCH_UP_RATE else 1f) }
                _state.update { it.copy(playing = true, loading = false, ended = false, error = null) }
                if (pendingSeekMs >= 0) issuePendingSeek(_state.value.positionMs + sectionStartMs)
            }
            override fun paused(mediaPlayer: MediaPlayer) {
                if (!stale()) _state.update { it.copy(playing = false) }
            }
            override fun stopped(mediaPlayer: MediaPlayer) {
                if (!stale()) _state.update { it.copy(playing = false, loading = false) }
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                if (!stale()) _state.update { it.copy(playing = false, ended = true, loading = false) }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                if (!stale()) _state.update { it.copy(playing = false, loading = false, error = "Não foi possível reproduzir este vídeo.") }
            }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (stale()) return
                // Só para medir: uma travada no meio da live (o buffer esvaziou e o VLC parou para encher de novo).
                if (Timing.enabled && framesSincePlay > 0 && newCache < 100f && !_state.value.loading) Timing.mark("TRAVOU (buffer ${newCache.toInt()}%)")
                _state.update { it.copy(loading = newCache < 100f) }
                // O buffer encheu depois de um pulo: se há outro pulo esperando, é a hora dele.
                if (newCache >= 100f && pendingSeekMs >= 0) issuePendingSeek(_state.value.positionMs + sectionStartMs)
            }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (stale()) return
                // Logo depois de um pulo o VLC ainda informa o tempo antigo por um instante (numa live gravada da Twitch, até baixar o trecho novo):
                // se a posição mostrada voltasse, a seta seguinte partiria dela e pulos seguidos não somariam.
                if (seekTargetMs >= 0) {
                    val elapsed = System.currentTimeMillis() - seekAskedAt
                    val stillOld = kotlin.math.abs(newTime - (seekFromMs + elapsed)) < SEEK_OLD_TOLERANCE_MS
                    if (stillOld && elapsed < SEEK_WAIT_MS) return
                    seekTargetMs = -1
                }
                if (pendingSeekMs >= 0 && issuePendingSeek(newTime)) return
                _state.update { it.copy(positionMs = (newTime - sectionStartMs).coerceAtLeast(0), behindLiveMs = trackBehind(newTime)) }
                recoverIfNoPicture(newTime)
                if (Timing.enabled) logDrift(newTime)
                trackLiveLag(newTime)
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                if (!stale()) _state.update { it.copy(durationMs = sectionEndMs?.let { end -> end - sectionStartMs } ?: newLength) }
            }
        })
    }

    /** Só para medir (`APEX_TIMING=1`): quanto o vídeo ficou para trás do relógio desde o primeiro quadro (cada travada soma ao atraso). */
    private var driftWallStart = 0L
    private var driftTimeStart = -1L
    private var driftLoggedAt = 0L

    private fun logDrift(newTime: Long) {
        val now = System.currentTimeMillis()
        if (framesSincePlay == 0L) return
        if (driftTimeStart < 0) { driftTimeStart = newTime; driftWallStart = now; driftLoggedAt = now; return }
        if (now - driftLoggedAt < 10_000) return
        driftLoggedAt = now
        Timing.mark("deriva ${(now - driftWallStart) - (newTime - driftTimeStart)} ms em ${(now - driftWallStart) / 1000} s")
        vlc {
            val info = mediaPlayer.media().info()
            val stats = info?.statistics()
            if (stats != null) Timing.mark("áudio: ${info.audioTracks().size} faixa(s), ${stats.decodedAudio()} blocos decodificados, ${stats.audioBuffersPlayed()} tocados, ${stats.audioBuffersLost()} perdidos; vídeo: ${stats.decodedVideo()} quadros, ${stats.picturesLost()} perdidos")
        }
    }

    // ---------- alcançar o "ao vivo" ----------
    // O relógio do VLC anda um pouco mais devagar que o da live (cerca de 1%): sem correção, o atraso cresce ~0,6 s por minuto, e a vantagem de
    // abrir perto do "ao vivo" some em poucos minutos. Quando o vídeo fica mais de [CATCH_UP_START_MS] para trás do que estava, ele toca um pouco
    // mais rápido (a voz não muda) até voltar.
    private var liveWallStart = 0L
    private var liveTimeStart = -1L
    private var liveMinDrift = 0L
    @Volatile private var catchingUp = false

    private fun trackLiveLag(newTime: Long) {
        val source = lastSource
        if (source == null || !source.live || !source.lowLatency || framesSincePlay == 0L || !_state.value.playing) { liveTimeStart = -1; return }
        val now = System.currentTimeMillis()
        if (liveTimeStart < 0) { liveTimeStart = newTime; liveWallStart = now; liveMinDrift = 0; return }
        val drift = (now - liveWallStart) - (newTime - liveTimeStart)
        if (drift < liveMinDrift) liveMinDrift = drift // o VLC pulou para a frente: a nova posição é o novo ponto de partida
        val behind = drift - liveMinDrift
        val want = when {
            catchingUp -> behind > CATCH_UP_STOP_MS
            else -> behind in CATCH_UP_START_MS..CATCH_UP_MAX_MS
        }
        if (want != catchingUp) {
            catchingUp = want
            Timing.mark(if (want) "alcançando o ao vivo (atrás ${behind} ms)" else "alcançou o ao vivo")
            val rate = desiredRate * if (want) CATCH_UP_RATE else 1f
            vlc { mediaPlayer.controls().setRate(rate) }
        }
    }

    override fun play(source: PlaySource) = request(source, safeMode = false)

    // ---------- atraso acumulado da live (o botão "Voltar ao vivo") ----------
    // Mede quanto o vídeo andou menos que o relógio desde que a live abriu: pausas e travadas somam, o ritmo mais rápido do "alcançar" diminui.
    private var behindWall0 = 0L
    private var behindMedia0 = 0L

    private fun trackBehind(newTime: Long): Long {
        val source = lastSource
        if (source == null || !source.live || framesSincePlay == 0L) { behindWall0 = 0; return 0 }
        val now = System.currentTimeMillis()
        if (behindWall0 == 0L) { behindWall0 = now; behindMedia0 = newTime; return 0 }
        val behind = (now - behindWall0) - (newTime - behindMedia0)
        // O vídeo pulou para a frente (ou alcançou): a conta recomeça daqui.
        if (behind < 0) { behindWall0 = now; behindMedia0 = newTime; return 0 }
        return behind
    }

    override fun jumpToLive() {
        val source = lastSource?.takeIf { it.live } ?: return
        Timing.start("voltar ao vivo")
        request(source, safeMode = false)
    }

    /** Pede para abrir [source]: o estado já mostra "carregando"; a abertura em si sai na fila do VLC, se até lá ninguém pediu outra coisa. */
    private fun request(source: PlaySource, safeMode: Boolean) {
        val mine = generation.incrementAndGet()
        closeEdge()
        lastSource = source
        sectionStartMs = if (source.endMs != null) source.startMs else 0
        sectionEndMs = source.endMs
        _state.update {
            PlayerState(
                hasMedia = true, loading = true, volume = it.volume, muted = it.muted, rate = desiredRate,
                durationMs = source.endMs?.let { end -> end - source.startMs } ?: 0,
            )
        }
        vlc { if (mine == generation.get()) start(source, safeMode, mine) }
    }

    /** Na fila do VLC: prepara e abre [source] (pedido número [mine]). */
    private fun start(source: PlaySource, safeMode: Boolean, mine: Int) {
        hintHeight = source.videoHeight
        pixelAspect = 1f
        seekTargetMs = -1
        pendingSeekMs = -1
        seekDebounce?.cancel()
        pictureSeen = false
        framesSincePlay = 0
        driftTimeStart = -1
        liveTimeStart = -1
        behindWall0 = 0
        catchingUp = false
        noPictureSinceMs = -1
        retriedWithoutPicture = safeMode
        // `APEX_TEST_NOVIDEO=1` (só para ensaio): a primeira abertura sai sem vídeo, para ver a recuperação acontecer.
        val noVideo = if (!safeMode && System.getenv("APEX_TEST_NOVIDEO") == "1") listOf(":no-video") else emptyList()
        // Pela saída do app, o VLC adianta o áudio um pouco (o que fica na fila curta e na linha de som).
        val sync = if (softAudio != null && softAudio.start()) listOf(":audio-desync=${SoftAudio.DESYNC_MS}") else emptyList()
        val extra = (if (safeMode) SAFE_MODE_OPTIONS else emptyList()) + noVideo + sync
        // Live de uma qualidade só (lista de trechos): o VLC lê uma lista curta e local, e fica perto do "ao vivo". No modo seguro vai direto.
        if (source.live && source.lowLatency && !safeMode && !source.audioOnly) {
            netScope.launch {
                val proxy = LiveEdgeProxy(netScope, Http.client, EDGE_KEEP, EDGE_KEEP_WITH_PREFETCH)
                val ok = proxy.start(source.videoUrl, source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap())
                if (mine != generation.get()) { proxy.close(); return@launch }
                if (ok) edge = proxy else proxy.close()
                vlc { if (mine == generation.get()) open(mine, if (ok) proxy.url else source.videoUrl, vlcOptions(source, viaEdge = ok) + extra) }
            }
        } else {
            // Endereços que só aceitam pedidos de um trecho (a via rápida do YouTube): o VLC toca pela ponte local, que busca em pedaços.
            val shown = if (source.bridgeRanges) bridged(source) else source
            open(mine, shown.videoUrl, vlcOptions(shown) + extra)
        }
    }

    /**
     * Na fila do VLC: abre [mrl]. Se havia outro vídeo aberto, o VLC para esse primeiro, de forma síncrona (é o que demora, e por isso
     * nunca acontece na interface). Os eventos do vídeo antigo que ainda chegam ficam de fora porque [playingGen] só passa a valer o novo depois.
     */
    private fun open(mine: Int, mrl: String, options: List<String>) {
        showing = true
        val started = mediaPlayer.media().play(mrl, *options.toTypedArray())
        playingGen = mine
        if (!started && mine == generation.get()) _state.update { it.copy(loading = false, error = "Não foi possível reproduzir este vídeo.") }
    }

    @Volatile private var rangeProxyRef: RangeProxy? = null
    private val rangeProxy: RangeProxy get() = rangeProxyRef ?: RangeProxy(Http.client).also { rangeProxyRef = it }

    /** [source] com o vídeo e o áudio apontando para a ponte local; se um endereço não diz o tamanho do arquivo, fica como estava. */
    private fun bridged(source: PlaySource): PlaySource {
        rangeProxy.clear()
        val headers = source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()
        val video = rangeProxy.register(source.videoUrl, headers, source.fullAccess) ?: return source
        val audio = source.audioUrl?.let { rangeProxy.register(it, headers, source.fullAccess) ?: return source }
        return source.copy(videoUrl = video, audioUrl = audio, userAgent = null)
    }

    private fun closeEdge() {
        edge?.close()
        edge = null
    }

    /**
     * Som sem imagem (a tela fica preta e o áudio toca): se o VLC já terminou de encher o buffer, está tocando e, passados [NO_PICTURE_MS] de relógio,
     * nenhum quadro com imagem chegou (nenhum quadro, ou só quadros todos pretos, que é como a decodificação por hardware falha em alguns VODs
     * da Twitch), reabre uma vez do mesmo ponto no modo seguro (sem decodificação por hardware e sem renderização direta). Enquanto ainda está
     * enchendo o buffer, ou no começo lento de uma live, não conta.
     */
    private fun recoverIfNoPicture(time: Long) {
        val source = lastSource ?: return
        if (source.audioOnly || pictureSeen || retriedWithoutPicture) return
        if (_state.value.loading) { noPictureSinceMs = -1; return }
        val now = System.currentTimeMillis()
        if (noPictureSinceMs < 0) { noPictureSinceMs = now; return }
        if (now - noPictureSinceMs < NO_PICTURE_MS) return
        retriedWithoutPicture = true
        val resumeAt = when {
            source.live -> 0L
            source.endMs != null -> source.startMs // clipe: recomeça o trecho
            else -> time
        }
        // Vai para a fila do VLC (o libVLC não pode ser chamado de dentro do próprio evento).
        request(source.copy(startMs = resumeAt), safeMode = true)
    }

    override fun togglePause() {
        if (_state.value.ended) { resume(); return }
        vlc { if (mediaPlayer.status().isPlaying) mediaPlayer.controls().pause() else mediaPlayer.controls().play() }
    }

    override fun pause() {
        vlc { if (mediaPlayer.status().isPlaying) mediaPlayer.controls().pause() }
    }

    override fun resume() {
        val again = lastSource
        if (_state.value.ended && again != null) {
            // Tocar de novo recomeça do começo (do trecho, no clipe), não de onde o vídeo foi retomado da primeira vez.
            play(again.copy(startMs = if (again.endMs != null) again.startMs else 0))
        } else {
            vlc { if (!mediaPlayer.status().isPlaying) mediaPlayer.controls().play() }
        }
    }

    /** O tempo (do arquivo) do pulo em andamento, de onde ele partiu e quando; -1 = nenhum. */
    @Volatile private var seekTargetMs = -1L
    @Volatile private var seekFromMs = 0L
    @Volatile private var seekAskedAt = 0L

    /** O pulo pedido enquanto outro estava em andamento (só o mais novo vale); sai quando o atual chegar. */
    @Volatile private var pendingSeekMs = -1L

    private var seekDebounce: Job? = null

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        val absolute = target + sectionStartMs
        Timing.mark("pular para ${absolute / 1000.0} s")
        if (seekTargetMs < 0 && pendingSeekMs < 0) seekFromMs = _state.value.positionMs + sectionStartMs
        _state.update { it.copy(positionMs = target) }
        pendingSeekMs = absolute
        // Várias setas seguidas: um pulo só, para o último pedido, logo depois da última tecla.
        seekDebounce?.cancel()
        seekDebounce = netScope.launch {
            delay(SEEK_DEBOUNCE_MS)
            issuePendingSeek(seekFromMs)
        }
    }

    /**
     * O pulo que ficou esperando sai quando o atual terminou de verdade: o VLC já informa o tempo novo logo depois do pedido, mas ainda está
     * buscando o trecho (buffer enchendo), e um pulo pedido nessa hora ele descarta. `true` se saiu.
     */
    private fun issuePendingSeek(from: Long): Boolean {
        if (seekTargetMs >= 0 || _state.value.loading) return false
        val next = pendingSeekMs
        if (next < 0) return false
        pendingSeekMs = -1
        issueSeek(next, from)
        return true
    }

    /** Manda o VLC pular para [absolute] (tempo do arquivo), partindo de [from]. */
    private fun issueSeek(absolute: Long, from: Long) {
        Timing.mark("VLC: pulo para ${absolute / 1000.0} s (de ${from / 1000.0} s)")
        seekFromMs = from
        seekAskedAt = System.currentTimeMillis()
        seekTargetMs = absolute
        vlc { mediaPlayer.controls().setTime(absolute) }
    }

    override fun seekBy(deltaMs: Long) {
        val s = _state.value
        val target = (s.positionMs + deltaMs).coerceAtLeast(0).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs - 500) else it }
        seekTo(target)
    }

    override fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, MAX_VOLUME) // até 200%: acima de 100 o som é amplificado
        Timing.mark("volume: $v")
        val s = _state.updateAndGet { it.copy(volume = v, muted = if (v > 0) false else it.muted) }
        if (softAudio != null) {
            softAudio.factor = SoftAudio.gainFor(s.volume, s.muted)
        } else {
            vlc {
                mediaPlayer.audio().setVolume(v)
                if (v > 0) mediaPlayer.audio().isMute = false
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        val s = _state.updateAndGet { it.copy(muted = muted) }
        if (softAudio != null) softAudio.factor = SoftAudio.gainFor(s.volume, s.muted)
        else vlc { mediaPlayer.audio().isMute = muted }
    }

    override fun setRate(rate: Float) {
        desiredRate = rate
        _state.update { it.copy(rate = rate) }
        vlc { mediaPlayer.controls().setRate(rate * if (catchingUp) CATCH_UP_RATE else 1f) }
    }

    override fun setSubtitle(url: String?) {
        vlc { if (url == null) mediaPlayer.subpictures().setTrack(-1) else mediaPlayer.subpictures().setSubTitleUri(url) }
    }

    /** Para na hora para quem olha (quadro limpo, estado zerado); o VLC para em seguida, na fila, sem segurar a interface. */
    override fun stop() {
        generation.incrementAndGet()
        showing = false
        closeEdge()
        lastSource = null
        sectionStartMs = 0
        sectionEndMs = null
        _state.update { PlayerState(volume = it.volume, muted = it.muted) }
        clearFrame()
        vlc {
            softAudio?.stop()
            mediaPlayer.controls().stop()
        }
    }

    /**
     * Solta o player. O que ainda estava na fila é descartado; o VLC é solto na própria fila, e aqui se espera por isso até [RELEASE_WAIT_MS]
     * (ao fechar o app, para o processo não sair no meio; quem chama nunca é a thread da interface no meio de um quadro).
     */
    override fun release() {
        if (released) return
        released = true
        generation.incrementAndGet()
        showing = false
        closeEdge()
        rangeProxyRef?.close()
        netScope.cancel()
        clearFrame()
        runCatching {
            vlcThread.execute {
                runCatching { softAudio?.stop() }
                runCatching { mediaPlayer.release() }.onFailure { Timing.mark("VLC não soltou: ${it.message}") }
            }
        }
        vlcThread.shutdown()
        runCatching { vlcThread.awaitTermination(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    private fun clearFrame() {
        synchronized(frameLock) {
            frame?.close()
            frame = null
        }
        frameTick++
    }

    @Composable
    override fun Video(modifier: Modifier) {
        Canvas(modifier.background(Color.Black)) {
            frameTick
            synchronized(frameLock) {
                val image = frame ?: return@Canvas
                val d0 = if (FrameMeter.enabled) System.nanoTime() else 0L
                val fit = fitVideo(size.width, size.height, image.width, image.height, pixelAspect)
                // Sem filtro o Skia usa o "vizinho mais próximo": reduzindo 1080p para a janela o vídeo fica serrilhado e parece de bitrate
                // baixo. Reduzindo usa mipmap com filtro linear (suave); ampliando usa Catmull-Rom (nítido, sem "blocos").
                val sampling = if (fit.scale < 0.98f) DOWNSCALE else UPSCALE
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeXYWH(fit.x.toFloat(), fit.y.toFloat(), fit.width.toFloat(), fit.height.toFloat()),
                        // strict = false: com `true` o Skia ignora os mipmaps e o vídeo reduzido sai serrilhado, como se tivesse bitrate baixo.
                        sampling, null, false,
                    )
                }
                if (d0 != 0L) {
                    FrameMeter.videoDraws.incrementAndGet()
                    FrameMeter.videoDrawNanos.addAndGet(System.nanoTime() - d0)
                }
            }
        }
    }

    private inner class FormatCallback : BufferFormatCallbackAdapter() {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            // A altura que o VLC oferece aqui não é a do vídeo: é a "altura codificada" (1080p vira 1088) e, depois que as tentativas de
            // decodificação por hardware falham, ainda cresce (1090). Aceitar esse tamanho estica a imagem e, na tela cheia, deixa
            // faixas pretas dos lados. O tamanho de verdade vem da faixa de vídeo ou da qualidade escolhida.
            val track = runCatching { mediaPlayer.media().info()?.videoTracks()?.firstOrNull() }.getOrNull()
            val (width, height) = trueFrameSize(sourceWidth, sourceHeight, track?.width() ?: 0, track?.height() ?: 0, hintHeight)
            val sarNum = track?.sampleAspectRatio() ?: 0
            val sarDen = track?.sampleAspectRatioBase() ?: 0
            pixelAspect = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
            // O RV32 do VLC não usa o canal alfa, por isso OPAQUE (senão o vídeo sairia transparente).
            imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            if (!stale()) _state.update { it.copy(videoWidth = width, videoHeight = height) }
            return RV32BufferFormat(width, height)
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
            frameBytes = ByteArray(buffers[0].remaining())
        }
    }

    private inner class FrameCallback : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) {}

        override fun unlock(mediaPlayer: MediaPlayer) {}

        override fun display(
            mediaPlayer: MediaPlayer,
            nativeBuffers: Array<ByteBuffer>,
            bufferFormat: BufferFormat,
            displayWidth: Int,
            displayHeight: Int,
        ) {
            if (!showing) return
            val info = imageInfo ?: return
            val t0 = if (FrameMeter.enabled) System.nanoTime() else 0L
            nativeBuffers[0].rewind()
            nativeBuffers[0].get(frameBytes)
            val next = Image.makeRaster(info, frameBytes, info.width * 4)
            val previous = synchronized(frameLock) {
                val old = frame
                frame = next
                old
            }
            previous?.close()
            if (t0 != 0L) {
                FrameMeter.videoFrames.incrementAndGet()
                FrameMeter.videoCopyNanos.addAndGet(System.nanoTime() - t0)
            }
            if (!pictureSeen && framesSincePlay % BLACK_CHECK_EVERY == 0L && hasPicture(frameBytes, info.width, info.height)) {
                pictureSeen = true
                if (framesSincePlay > 0) Timing.mark("primeira imagem de verdade no quadro ${framesSincePlay + 1} (antes só quadros pretos)")
            }
            if (framesSincePlay++ == 0L) {
                Timing.mark("primeiro quadro (${info.width}x${info.height})")
                edge?.estimate?.let { e ->
                    val serverNow = System.currentTimeMillis() - e.skewMs
                    Timing.mark("atraso estimado ${serverNow - e.trimmedStartServerMs} ms (direto no VLC seria ${serverNow - e.directStartServerMs} ms)")
                }
            }
            frameTick++
        }
    }
}
