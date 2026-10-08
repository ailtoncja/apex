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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

private val DOWNSCALE: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
private val UPSCALE: SamplingMode = SamplingMode.CATMULL_ROM

/**
 * As opções do libVLC para tocar [source]. Numa live, o VLC por padrão começa 8 segmentos (~16 s) antes do "ao vivo" na Twitch (a lista
 * declara segmentos de 6 s) e ainda guarda 3 s; com [PlaySource.lowLatency] ele começa 3 segmentos (~6 s) antes, o mínimo que o VLC
 * aceita, e guarda 1 s (medido nos logs do próprio VLC: de ~19 s para ~7 s de atraso).
 */
internal fun vlcOptions(source: PlaySource): List<String> = buildList {
    source.audioUrl?.let { add(":input-slave=$it") }
    source.userAgent?.let { add(":http-user-agent=$it") }
    if (source.startMs > 1000) add(":start-time=${source.startMs / 1000.0}")
    source.endMs?.let { add(":stop-time=${it / 1000.0}") }
    when {
        !source.live -> add(":network-caching=1500")
        source.lowLatency -> {
            add(":network-caching=1000")
            add(":adaptive-livedelay=3000")
        }
        else -> add(":network-caching=3000")
    }
}

/** Player do Windows: o libVLC decodifica e entrega cada quadro, que o Compose desenha por cima de tudo. */
class VlcPlayerController(hardwareDecode: Boolean = true) : PlayerController {
    private val factory = MediaPlayerFactory(
        "--no-video-title-show", "--no-osd", "--no-snapshot-preview", "--quiet",
        if (hardwareDecode) "--avcodec-hw=any" else "--avcodec-hw=none",
    )
    private val mediaPlayer: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val frameLock = Any()
    private var frame: Image? = null
    private var frameTick by mutableIntStateOf(0)
    private var imageInfo: ImageInfo? = null
    private var frameBytes = ByteArray(0)

    /** A velocidade pedida: o libVLC só aceita mudar depois que o vídeo começa, por isso é reaplicada no evento "tocando". */
    @Volatile private var desiredRate = 1f
    private var lastSource: PlaySource? = null

    /** Clipe: o libVLC conta o tempo do arquivo inteiro; aqui ele é convertido para o do trecho. */
    @Volatile private var sectionStartMs = 0L
    @Volatile private var sectionEndMs: Long? = null

    init {
        mediaPlayer.videoSurface().set(
            CallbackVideoSurface(FormatCallback(), FrameCallback(), true, VideoSurfaceAdapters.getVideoSurfaceAdapter()),
        )
        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) = _state.update { it.copy(loading = true, ended = false, error = null) }
            override fun playing(mediaPlayer: MediaPlayer) {
                if (desiredRate != 1f) mediaPlayer.controls().setRate(desiredRate)
                _state.update { it.copy(playing = true, loading = false, ended = false, error = null) }
            }
            override fun paused(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false) }
            override fun stopped(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false, loading = false) }
            override fun finished(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false, ended = true, loading = false) }
            override fun error(mediaPlayer: MediaPlayer) =
                _state.update { it.copy(playing = false, loading = false, error = "Não foi possível reproduzir este vídeo.") }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = _state.update { it.copy(loading = newCache < 100f) }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) =
                _state.update { it.copy(positionMs = (newTime - sectionStartMs).coerceAtLeast(0)) }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) =
                _state.update { it.copy(durationMs = sectionEndMs?.let { end -> end - sectionStartMs } ?: newLength) }
        })
    }

    override fun play(source: PlaySource) {
        lastSource = source
        sectionStartMs = if (source.endMs != null) source.startMs else 0
        sectionEndMs = source.endMs
        _state.update {
            PlayerState(
                hasMedia = true, loading = true, volume = it.volume, muted = it.muted, rate = desiredRate,
                durationMs = source.endMs?.let { end -> end - source.startMs } ?: 0,
            )
        }
        mediaPlayer.media().play(source.videoUrl, *vlcOptions(source).toTypedArray())
    }

    override fun togglePause() {
        if (mediaPlayer.status().isPlaying) mediaPlayer.controls().pause() else resume()
    }

    override fun pause() {
        if (mediaPlayer.status().isPlaying) mediaPlayer.controls().pause()
    }

    override fun resume() {
        val again = lastSource
        if (_state.value.ended && again != null) {
            // Tocar de novo recomeça do começo (do trecho, no clipe), não de onde o vídeo foi retomado da primeira vez.
            play(again.copy(startMs = if (again.endMs != null) again.startMs else 0))
        } else if (!mediaPlayer.status().isPlaying) {
            mediaPlayer.controls().play()
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        mediaPlayer.controls().setTime(target + sectionStartMs)
        _state.update { it.copy(positionMs = target) }
    }

    override fun seekBy(deltaMs: Long) {
        val s = _state.value
        val target = (s.positionMs + deltaMs).coerceAtLeast(0).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs - 500) else it }
        seekTo(target)
    }

    override fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, MAX_VOLUME) // até 200%: o libVLC amplifica o que passa de 100
        mediaPlayer.audio().setVolume(v)
        _state.update { it.copy(volume = v, muted = if (v > 0) false else it.muted) }
        if (v > 0) mediaPlayer.audio().isMute = false
    }

    override fun setMuted(muted: Boolean) {
        mediaPlayer.audio().isMute = muted
        _state.update { it.copy(muted = muted) }
    }

    override fun setRate(rate: Float) {
        desiredRate = rate
        mediaPlayer.controls().setRate(rate)
        _state.update { it.copy(rate = rate) }
    }

    override fun setSubtitle(url: String?) {
        if (url == null) mediaPlayer.subpictures().setTrack(-1) else mediaPlayer.subpictures().setSubTitleUri(url)
    }

    override fun stop() {
        mediaPlayer.controls().stop()
        lastSource = null
        sectionStartMs = 0
        sectionEndMs = null
        _state.update { PlayerState(volume = it.volume, muted = it.muted) }
        synchronized(frameLock) {
            frame?.close()
            frame = null
        }
        frameTick++
    }

    override fun release() {
        mediaPlayer.release()
        factory.release()
        synchronized(frameLock) {
            frame?.close()
            frame = null
        }
    }

    @Composable
    override fun Video(modifier: Modifier) {
        Canvas(modifier.background(Color.Black)) {
            frameTick
            synchronized(frameLock) {
                val image = frame ?: return@Canvas
                val scale = min(size.width / image.width, size.height / image.height)
                val w = (image.width * scale).roundToInt().coerceAtLeast(1)
                val h = (image.height * scale).roundToInt().coerceAtLeast(1)
                val x = ((size.width - w) / 2).roundToInt()
                val y = ((size.height - h) / 2).roundToInt()
                // Sem filtro o Skia usa o "vizinho mais próximo": reduzindo 1080p para a janela o vídeo fica serrilhado e parece de bitrate
                // baixo. Reduzindo usa mipmap com filtro linear (suave); ampliando usa Catmull-Rom (nítido, sem "blocos").
                val sampling = if (scale < 0.98f) DOWNSCALE else UPSCALE
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
                        // strict = false: com `true` o Skia ignora os mipmaps e o vídeo reduzido sai serrilhado, como se tivesse bitrate baixo.
                        sampling, null, false,
                    )
                }
            }
        }
    }

    private inner class FormatCallback : BufferFormatCallbackAdapter() {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            // O RV32 do VLC não usa o canal alfa, por isso OPAQUE (senão o vídeo sairia transparente).
            imageInfo = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            _state.update { it.copy(videoWidth = sourceWidth, videoHeight = sourceHeight) }
            return RV32BufferFormat(sourceWidth, sourceHeight)
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
            val info = imageInfo ?: return
            nativeBuffers[0].rewind()
            nativeBuffers[0].get(frameBytes)
            val next = Image.makeRaster(info, frameBytes, info.width * 4)
            val previous = synchronized(frameLock) {
                val old = frame
                frame = next
                old
            }
            previous?.close()
            frameTick++
        }
    }
}
