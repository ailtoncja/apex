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
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

/** O buffer do VLC pelo fluxo contínuo. */
internal const val RELAY_CACHE_MS = 500

/**
 * As opções do libVLC para tocar [source]. Numa live, o VLC por padrão começa 8 segmentos (~16 s) antes do "ao vivo" na Twitch (a lista
 * declara segmentos de 6 s) e ainda guarda 3 s; com [PlaySource.lowLatency] ele começa 3 segmentos (~6 s) antes, o mínimo que o VLC
 * aceita, e guarda 1 s (medido nos logs do próprio VLC: de ~19 s para ~7 s de atraso).
 */
internal fun vlcOptions(source: PlaySource, viaRelay: Boolean = false, relayMp4: Boolean = false): List<String> = buildList {
    source.audioUrl?.let { add(":input-slave=$it") }
    source.userAgent?.let { add(":http-user-agent=$it") }
    if (source.startMs > 1000) add(":start-time=${source.startMs / 1000.0}")
    source.endMs?.let { add(":stop-time=${it / 1000.0}") }
    // Só som (a música de fundo, ou "Somente áudio"): o VLC nem decodifica a imagem, que ninguém vê.
    if (source.audioOnly) add(":no-video")
    when {
        !source.live -> add(":network-caching=1500")
        // Pelo fluxo contínuo ([LiveTsRelay]) o vídeo chega conforme é gerado: um buffer pequeno, e o VLC já sabe o formato (MPEG-TS ou fMP4) e
        // não perde tempo adivinhando.
        viaRelay -> {
            add(":network-caching=$RELAY_CACHE_MS")
            add(if (relayMp4) ":demux=mp4" else ":demux=ts")
        }
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
class VlcPlayerController(private val hardwareDecode: Boolean = true) : PlayerController {
    private companion object {
        /** Relógio sem nenhum quadro de vídeo (com o VLC tocando e sem buffer a encher): passado isso, o vídeo é reaberto uma vez em [SAFE_MODE_OPTIONS]. */
        const val NO_PICTURE_MS = 6_000L
        const val CATCH_UP_START_MS = 1_500L
        const val RELAY_CATCH_UP_START_MS = 300L
        const val RELAY_CATCH_UP_STOP_MS = 100L

        /** Atraso medido abaixo disto é erro de medida (só o buffer do VLC já é isso). */
        const val MIN_PLAUSIBLE_LATENCY_MS = RELAY_CACHE_MS.toLong()
        const val CATCH_UP_STOP_MS = 400L
        const val CATCH_UP_MAX_MS = 20_000L
        const val CATCH_UP_RATE = 1.05f

        /** Numa live, quanto tempo escondido antes de desligar a imagem no VLC (ver [requestHide]). */
        const val LIVE_HIDE_GRACE_MS = 60_000L

        /** Live aberta sem trechos adiantados (anúncio da Twitch): de quanto em quanto a lista é consultada, e por quanto tempo no máximo. */
        const val PREFETCH_POLL_MS = 1_000L
        const val PREFETCH_WAIT_MS = 180_000L

        /** Quanto [release] espera o VLC soltar o player de fato (ao fechar o app, para o processo não sair no meio disso). */
        const val RELEASE_WAIT_MS = 1_500L

        /**
         * Depois de um pulo, o tempo que o VLC informa só volta a valer quando sai da linha do tempo antiga (mais de [SEEK_OLD_TOLERANCE_MS]
         * de diferença do que seria sem o pulo) ou passado [SEEK_WAIT_MS].
         */
        const val SEEK_OLD_TOLERANCE_MS = 1_500L
        const val SEEK_WAIT_MS = 3_000L

        /** Com um jogo em outro monitor, o intervalo mínimo entre quadros mostrados (~30 por segundo). */
        const val GAMING_FRAME_NS = 31_000_000L

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
            // `APEX_VLC_LOG=arquivo` (só para ensaio): o registro detalhado do próprio VLC vai para esse arquivo.
            val log = System.getenv("APEX_VLC_LOG")?.takeIf { it.isNotBlank() }
                ?.let { listOf("--file-logging", "--logfile=$it", "--log-verbose=2") } ?: listOf("--quiet")
            MediaPlayerFactory(
                *(listOf("--no-video-title-show", "--no-osd", "--no-snapshot-preview", if (hardwareDecode) "--avcodec-hw=any" else "--avcodec-hw=none") + log).toTypedArray(),
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

    // ---------- economia quando ninguém vê (ver ViewingWatch) ----------

    /** A imagem está desligada no VLC (Apex escondido): ele nem decodifica o vídeo, só o som. */
    @Volatile private var videoOff = false
    private var savedVideoTrack = -1
    @Volatile private var hideQueued = false

    /** Com um jogo em outro monitor: quando foi entregue o último quadro (no máximo ~30 por segundo). */
    @Volatile private var lastShownNanos = 0L

    /** Só para medir: quando a imagem foi religada (para marcar quanto ela levou para voltar). */
    @Volatile private var reshownAt = 0L

    /** Desde quando o Apex está escondido ([Viewing.Hidden]); `-1` = à vista. */
    @Volatile private var hiddenSinceMs = -1L

    /** A imagem foi religada numa live e o quadro novo ainda não veio: até ele chegar, o player mostra "carregando" (não a imagem velha parada). */
    @Volatile private var awaitingPicture = false

    private val viewingListener: (Viewing) -> Unit = { v ->
        if (v == Viewing.Hidden) {
            if (hiddenSinceMs < 0) hiddenSinceMs = System.currentTimeMillis()
            requestHide()
        } else {
            hiddenSinceMs = -1
            vlc { showVideo() }
        }
    }

    init {
        ViewingWatch.listeners += viewingListener
    }

    /**
     * Dá para desligar a faixa de vídeo sem parar o vídeo? Só quando o som vem no mesmo arquivo (lives e VODs da Twitch e da Kick, lives do
     * YouTube). No YouTube gravado o som vem num arquivo à parte e o arquivo principal é o do vídeo: sem a faixa dele, o VLC dá o vídeo por
     * acabado (e a reprodução automática pulava para o próximo). Nesse caso o VLC segue decodificando, mas nenhum quadro é copiado nem desenhado.
     */
    private fun canDropVideoTrack() = lastSource?.let { it.audioUrl == null && !it.audioOnly } == true

    private fun requestHide() {
        if (hideQueued || videoOff || !canDropVideoTrack()) return
        // Numa live, religar a imagem espera o próximo quadro-chave (até 2 s na Twitch), e nesse tempo a imagem de antes ficava parada na tela:
        // a cada troca rápida de janela a live parecia travar. Só depois de [LIVE_HIDE_GRACE_MS] escondido a imagem é desligada; até lá o VLC
        // segue decodificando, mas nenhum quadro é copiado nem desenhado.
        val since = hiddenSinceMs
        if (lastSource?.live == true && (since < 0 || System.currentTimeMillis() - since < LIVE_HIDE_GRACE_MS)) return
        hideQueued = true
        vlc {
            hideQueued = false
            hideVideo()
        }
    }

    /** Na fila do VLC: desliga a faixa de vídeo (lembrando qual era). Sem faixa ainda (o vídeo está abrindo), tenta de novo no próximo quadro. */
    private fun hideVideo() {
        if (videoOff || !canDropVideoTrack() || ViewingWatch.viewing != Viewing.Hidden) return
        val track = mediaPlayer.video().track()
        if (track < 0) return
        savedVideoTrack = track
        mediaPlayer.video().setTrack(-1)
        videoOff = true
        Timing.mark("imagem desligada: ninguém está vendo (o som continua)")
    }

    /** Na fila do VLC: religa a imagem. Num vídeo gravado, um pulo para o mesmo ponto faz a imagem voltar já, sem esperar o próximo quadro-chave. */
    private fun showVideo() {
        if (!videoOff) return
        videoOff = false
        noPictureSinceMs = -1
        mediaPlayer.video().setTrack(savedVideoTrack)
        if (lastSource?.live == false && _state.value.durationMs > 0) mediaPlayer.controls().setTime(mediaPlayer.status().time())
        // Numa live a imagem volta no próximo quadro-chave: até lá, "carregando" em vez da imagem de quando o Apex foi escondido, parada.
        if (lastSource?.live == true) {
            awaitingPicture = true
            clearFrame()
            _state.update { it.copy(loading = true) }
        }
        if (Timing.enabled) reshownAt = System.currentTimeMillis()
        Timing.mark("imagem religada")
    }

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    // ---------- a thread do VLC ----------

    /** A fila deste player: cada chamada ao libVLC roda aqui, uma de cada vez, na ordem em que foi pedida. */
    /** O número deste player (o principal, os do Multi, o da música de fundo), nos nomes das threads e nas medições. */
    private val tag = ids.incrementAndGet()
    private val vlcThread = Executors.newSingleThreadExecutor { r -> Thread(r, "apex-vlc-$tag").apply { isDaemon = true } }
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
    private var frame: YuvFrame? = null
    private var frameTick by mutableIntStateOf(0)

    /** O tamanho do quadro combinado com o VLC e as três partes dele (Y, U, V) copiadas a cada quadro. Ver [YuvFrame]. */
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

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

    /** Trabalho de rede do player (o fluxo contínuo da live). */
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var edge: LiveTsRelay? = null
    @Volatile private var retriedWithoutPicture = false

    init {
        mediaPlayer.videoSurface().set(
            CallbackVideoSurface(FormatCallback(), FrameCallback(), true, VideoSurfaceAdapters.getVideoSurfaceAdapter()),
        )
        softAudio?.let { out ->
            mediaPlayer.audio().callback(SoftAudio.VLC_FORMAT, SoftAudio.RATE, SoftAudio.CHANNELS, object : AudioCallbackAdapter() {
                override fun play(mediaPlayer: MediaPlayer, samples: com.sun.jna.Pointer, sampleCount: Int, pts: Long) {
                    if (seekWatchSound) { seekWatchSound = false; Timing.mark("pulo: som de volta em ${System.currentTimeMillis() - seekWatchAt} ms") }
                    out.push(samples, sampleCount)
                }
                override fun flush(mediaPlayer: MediaPlayer, pts: Long) {
                    // O som que chega depois do esvaziamento já é o do ponto novo.
                    if (seekWatchSoundArmed) { seekWatchSoundArmed = false; seekWatchSound = true; Timing.mark("pulo: VLC esvaziou o som em ${System.currentTimeMillis() - seekWatchAt} ms") }
                    out.flush()
                }
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
                // Sempre: o libVLC guarda a velocidade de um vídeo para o seguinte (reabrindo a live enquanto alcançava, ela ficava em 1,05x).
                vlc { mediaPlayer.controls().setRate(desiredRate * if (catchingUp) CATCH_UP_RATE else 1f) }
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
                    if (Timing.enabled) Timing.mark("pulo: o tempo chegou em ${newTime / 1000.0} s (pedido ${seekTargetMs / 1000.0} s) em $elapsed ms")
                    seekTargetMs = -1
                }
                if (pendingSeekMs >= 0 && issuePendingSeek(newTime)) return
                _state.update {
                    it.copy(positionMs = (newTime - sectionStartMs).coerceAtLeast(0), behindLiveMs = trackBehind(newTime), liveLatencyMs = trackLatency(newTime))
                }
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
        if (framesSincePlay == 0L && lastSource?.audioOnly != true) return
        if (driftTimeStart < 0) { driftTimeStart = newTime; driftWallStart = now; driftLoggedAt = now; return }
        if (now - driftLoggedAt < 10_000) return
        driftLoggedAt = now
        Timing.mark("deriva ${(now - driftWallStart) - (newTime - driftTimeStart)} ms em ${(now - driftWallStart) / 1000} s")
        vlc {
            val info = mediaPlayer.media().info()
            val stats = info?.statistics()
            if (stats != null) Timing.mark("áudio[$tag]: ${info.audioTracks().size} faixa(s), ${stats.decodedAudio()} blocos decodificados, ${stats.audioBuffersPlayed()} tocados, ${stats.audioBuffersLost()} perdidos; vídeo: ${stats.decodedVideo()} quadros, ${stats.picturesLost()} perdidos")
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

    /**
     * A partir de quanto atraso acumulado ele acelera, e até quanto. Pelo fluxo contínuo o ritmo é estável (sem os soluços da lista): dá para
     * reagir a cada travada pequena e voltar quase ao atraso do começo; senão cada uma deixava uns décimos de segundo para sempre.
     */
    @Volatile private var catchUpStartMs = CATCH_UP_START_MS
    @Volatile private var catchUpStopMs = CATCH_UP_STOP_MS

    private fun trackLiveLag(newTime: Long) {
        val source = lastSource
        if (source == null || !source.live || !source.lowLatency || framesSincePlay == 0L || !_state.value.playing) { liveTimeStart = -1; return }
        val now = System.currentTimeMillis()
        if (liveTimeStart < 0) { liveTimeStart = newTime; liveWallStart = now; liveMinDrift = 0; return }
        val drift = (now - liveWallStart) - (newTime - liveTimeStart)
        if (drift < liveMinDrift) liveMinDrift = drift // o VLC pulou para a frente: a nova posição é o novo ponto de partida
        val behind = drift - liveMinDrift
        val want = when {
            catchingUp -> behind > catchUpStopMs
            else -> behind in catchUpStartMs..CATCH_UP_MAX_MS
        }
        if (want != catchingUp) {
            catchingUp = want
            Timing.mark(if (want) "alcançando o ao vivo (atrás ${behind} ms)" else "alcançou o ao vivo")
            val rate = desiredRate * if (want) CATCH_UP_RATE else 1f
            vlc { mediaPlayer.controls().setRate(rate) }
        }
    }

    // ---------- atraso em relação à transmissão ----------
    // A lista diz a hora (do servidor) em que começa o trecho onde o VLC abriu, que é o que toca no primeiro aviso de tempo; dali em diante a
    // tela está nessa hora mais o quanto o vídeo andou. O atraso é a hora do servidor agora menos essa. (Conta pelo tempo, não pelo primeiro
    // quadro: com o Apex escondido a imagem pode nem estar sendo entregue, e o som já toca.)
    @Volatile private var firstFrameServerMs = -1L
    private var latencyMedia0 = -1L
    private var latencyLoggedAt = 0L

    private fun trackLatency(newTime: Long): Long? {
        if (firstFrameServerMs < 0) edge?.startServerMs?.let { firstFrameServerMs = it; latencyMedia0 = newTime }
        val shownAt0 = firstFrameServerMs
        val skew = edge?.clock?.skewMs
        if (shownAt0 < 0 || skew == null) return null
        val now = System.currentTimeMillis()
        if (latencyMedia0 < 0) latencyMedia0 = newTime
        val latency = (now - skew) - (shownAt0 + (newTime - latencyMedia0))
        if (Timing.enabled && now - latencyLoggedAt >= 5_000) {
            latencyLoggedAt = now
            Timing.mark("atraso agora $latency ms${if (catchingUp) " (alcançando)" else ""}")
        }
        // Menos que o próprio buffer do VLC não existe: é a hora dos trechos que veio errada (a Twitch a refaz depois de um anúncio). Melhor não
        // mostrar nada que mostrar um número falso.
        return latency.takeIf { it >= MIN_PLAUSIBLE_LATENCY_MS }
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
                durationMs = source.endMs?.let { end -> end - source.startMs } ?: 0, adsSeen = it.adsSeen,
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
        firstFrameServerMs = -1
        latencyMedia0 = -1
        noPictureSinceMs = -1
        retriedWithoutPicture = safeMode
        videoOff = false
        awaitingPicture = false
        savedVideoTrack = -1
        // `APEX_TEST_NOVIDEO=1` (só para ensaio): a primeira abertura sai sem vídeo, para ver a recuperação acontecer.
        val noVideo = if (!safeMode && System.getenv("APEX_TEST_NOVIDEO") == "1") listOf(":no-video") else emptyList()
        // Pela saída do app, o VLC adianta o áudio um pouco (o que fica na fila curta e na linha de som).
        val sync = if (softAudio != null && softAudio.start()) listOf(":audio-desync=${SoftAudio.DESYNC_MS}") else emptyList()
        // O libVLC desliga a decodificação pela placa de vídeo quando o vídeo é entregue ao app (o "vmem"), mesmo com --avcodec-hw na fábrica:
        // por mídia ela volta (medido: o motor de vídeo da placa sai de 0% e o processador cai junto com o YUV, ver YuvFrame).
        val hw = if (hardwareDecode) listOf(":avcodec-hw=any") else emptyList()
        val extra = (if (safeMode) SAFE_MODE_OPTIONS else hw) + noVideo + sync
        // Live de uma qualidade só (lista de trechos): o app entrega ao VLC um fluxo contínuo, perto do "ao vivo" (ver LiveTsRelay). No modo
        // seguro vai direto.
        if (source.live && source.lowLatency && !safeMode && !source.audioOnly) {
            netScope.launch {
                val headers = source.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()
                // Numa troca de vídeo (anúncio) o fluxo para: a live é reaberta.
                val relay = LiveTsRelay(netScope, Http.client) { if (mine == generation.get()) request(source, safeMode = false) }
                val ok = relay.start(source.videoUrl, headers)
                if (!ok) relay.close()
                if (mine != generation.get()) { relay.close(); return@launch }
                if (ok) edge = relay
                catchUpStartMs = if (ok) RELAY_CATCH_UP_START_MS else CATCH_UP_START_MS
                catchUpStopMs = if (ok) RELAY_CATCH_UP_STOP_MS else CATCH_UP_STOP_MS
                Timing.mark(if (ok) "fluxo contínuo: ligado${if (relay.fmp4) " (fMP4)" else ""}" else "fluxo contínuo: não deu (${relay.failure}), o VLC toca direto")
                // No anúncio o VLC toca a lista da plataforma com o buffer normal: com o mínimo da baixa latência travava a cada trecho, e um
                // anúncio não precisa estar perto do "ao vivo" (a live é reaberta quando ele acaba).
                val played = if (!ok && relay.inAd) source.copy(lowLatency = false) else source
                // Avisa a sessão: se a live veio por um servidor sem anúncio, ela troca de servidor (ver PlaybackSession.onAd).
                if (relay.inAd) _state.update { it.copy(adsSeen = it.adsSeen + 1) }
                val options = vlcOptions(played, viaRelay = ok, relayMp4 = relay.fmp4) + extra
                vlc { if (mine == generation.get()) open(mine, if (ok) relay.url else source.videoUrl, options) }
                // A Twitch abre a live com um anúncio, e a lista dele não tem trechos adiantados: sem isto o VLC ficaria tocando direto (e
                // atrasado) a live inteira. Quando a lista da live chega, a live é reaberta perto do "ao vivo".
                if (relay.noPrefetch) {
                    val ready: (LivePlaylist) -> Boolean = { p -> relayStart(p, RELAY_START_BACK) != null }
                    val stillWanted = { mine == generation.get() && _state.value.playing }
                    if (waitForPrefetch(Http.client, source.videoUrl, headers, ready, PREFETCH_POLL_MS, PREFETCH_WAIT_MS) { mine == generation.get() } &&
                        stillWanted()
                    ) {
                        Timing.mark("a lista passou a ter trechos adiantados (fim do anúncio): reabrindo perto do ao vivo")
                        request(source, safeMode = false)
                    }
                }
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
        // Quadros do vídeo anterior ainda chegam enquanto o fluxo contínuo é preparado: a contagem (e o "primeiro quadro", onde o atraso é medido)
        // recomeça aqui, com o novo já aberto.
        framesSincePlay = 0
        firstFrameServerMs = -1
        latencyMedia0 = -1
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
        // Com a imagem desligada de propósito (Apex escondido), não chegar imagem é o esperado.
        if (source.audioOnly || pictureSeen || retriedWithoutPicture || videoOff || ViewingWatch.viewing == Viewing.Hidden) return
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

    /** Só para medir (`APEX_TIMING=1`): quando o último pulo saiu, para marcar quando a imagem e o som voltam depois dele. */
    @Volatile private var seekWatchAt = 0L
    @Volatile private var seekWatchFrame = false
    @Volatile private var seekWatchSound = false
    @Volatile private var seekWatchSoundArmed = false

    /** Manda o VLC pular para [absolute] (tempo do arquivo), partindo de [from]. */
    private fun issueSeek(absolute: Long, from: Long) {
        Timing.mark("VLC: pulo para ${absolute / 1000.0} s (de ${from / 1000.0} s)")
        seekFromMs = from
        seekAskedAt = System.currentTimeMillis()
        if (Timing.enabled) { seekWatchAt = seekAskedAt; seekWatchFrame = true; seekWatchSoundArmed = true }
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
        _state.update { PlayerState(volume = it.volume, muted = it.muted, adsSeen = it.adsSeen) }
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
        ViewingWatch.listeners -= viewingListener
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
                // A conversão para RGB (e o filtro que evita o serrilhado ao reduzir) é feita na placa de vídeo: ver YuvPainter.
                drawIntoCanvas { canvas ->
                    YuvPainter.draw(canvas.skiaCanvas, image, fit.x.toFloat(), fit.y.toFloat(), fit.width.toFloat(), fit.height.toFloat())
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
            // faixas pretas dos lados. O tamanho de verdade vem da faixa de vídeo ou da qualidade escolhida. (Pedir o tamanho oferecido e
            // cortar as linhas a mais ao desenhar foi medido: o VLC gasta o mesmo, e o processo todo ainda gasta ~10% de um núcleo a mais.)
            val track = runCatching { mediaPlayer.media().info()?.videoTracks()?.firstOrNull() }.getOrNull()
            val (width, height) = trueFrameSize(sourceWidth, sourceHeight, track?.width() ?: 0, track?.height() ?: 0, hintHeight)
            val sarNum = track?.sampleAspectRatio() ?: 0
            val sarDen = track?.sampleAspectRatioBase() ?: 0
            pixelAspect = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
            if (!stale()) _state.update { it.copy(videoWidth = width, videoHeight = height) }
            frameWidth = width
            frameHeight = height
            // YUV 4:2:0 como o vídeo vem (ver YuvFrame): o VLC não converte nada para RGB, e a cópia é de 3 MB por quadro em 1080p, não 8.
            val cw = YuvFrame.chromaSize(width)
            val ch = YuvFrame.chromaSize(height)
            return BufferFormat("I420", width, height, intArrayOf(width, cw, cw), intArrayOf(height, ch, ch))
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
            yBytes = ByteArray(frameWidth * frameHeight)
            uBytes = ByteArray(YuvFrame.chromaSize(frameWidth) * YuvFrame.chromaSize(frameHeight))
            vBytes = ByteArray(uBytes.size)
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
            when (ViewingWatch.viewing) {
                // Ninguém vê: nada de copiar nem desenhar; e a imagem é desligada no VLC (quadros que ainda chegam pedem de novo).
                Viewing.Hidden -> { requestHide(); return }
                // Um jogo em outro monitor: no máximo ~30 quadros por segundo (os outros nem são copiados).
                Viewing.Gaming -> {
                    val now = System.nanoTime()
                    if (now - lastShownNanos < GAMING_FRAME_NS) return
                    lastShownNanos = now
                }
                Viewing.Normal -> {}
            }
            if (reshownAt > 0) { Timing.mark("imagem de volta ${System.currentTimeMillis() - reshownAt} ms depois de religar"); reshownAt = 0 }
            if (awaitingPicture) {
                awaitingPicture = false
                _state.update { it.copy(loading = false) }
            }
            val width = frameWidth
            val height = frameHeight
            if (width <= 0 || nativeBuffers.size < 3 || yBytes.size != width * height) return
            val t0 = if (FrameMeter.enabled) System.nanoTime() else 0L
            nativeBuffers[0].rewind(); nativeBuffers[0].get(yBytes)
            nativeBuffers[1].rewind(); nativeBuffers[1].get(uBytes)
            nativeBuffers[2].rewind(); nativeBuffers[2].get(vBytes)
            val next = YuvFrame.of(width, height, yBytes, uBytes, vBytes)
            val previous = synchronized(frameLock) {
                val old = frame
                frame = next
                old
            }
            previous?.close()
            if (seekWatchFrame && seekTargetMs < 0) { seekWatchFrame = false; Timing.mark("pulo: imagem de volta em ${System.currentTimeMillis() - seekWatchAt} ms") }
            if (t0 != 0L) {
                FrameMeter.videoFrames.incrementAndGet()
                FrameMeter.videoCopyNanos.addAndGet(System.nanoTime() - t0)
            }
            if (!pictureSeen && framesSincePlay % BLACK_CHECK_EVERY == 0L && hasPictureLuma(yBytes, width, height)) {
                pictureSeen = true
                if (framesSincePlay > 0) Timing.mark("primeira imagem de verdade no quadro ${framesSincePlay + 1} (antes só quadros pretos)")
            }
            if (framesSincePlay++ == 0L) {
                Timing.mark("primeiro quadro (${width}x$height)")
                edge?.let { feed ->
                    val shown = feed.startServerMs ?: return@let
                    val skew = feed.clock.skewMs ?: return@let
                    Timing.mark("atraso no primeiro quadro: ${System.currentTimeMillis() - skew - shown} ms")
                }
            }
            frameTick++
        }
    }
}
