package app.apex.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.apex.chat.EmoteRatios
import app.apex.source.Http
import app.apex.util.Timing
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.min

/** Os quadros de um emote já decodificados, com a duração de cada um (ms) e o total de uma volta. */
private class EmoteFrames(val frames: List<Image>, val durations: IntArray) {
    val loopMs: Int = durations.sum().coerceAtLeast(1)
    val bytes: Long = frames.sumOf { it.width.toLong() * it.height * 4 }

    /** O quadro de agora, pelo relógio: todas as cópias do mesmo emote mostram o mesmo quadro. */
    fun indexAt(nowMs: Long): Int {
        var t = (nowMs % loopMs).toInt()
        for (i in durations.indices) {
            if (t < durations[i]) return i
            t -= durations[i]
        }
        return frames.lastIndex
    }

    /** Quanto falta para o quadro mudar. */
    fun untilNext(nowMs: Long): Long {
        var t = (nowMs % loopMs).toInt()
        for (d in durations) {
            if (t < d) return (d - t).toLong()
            t -= d
        }
        return 16
    }
}

/**
 * Os emotes baixados e decodificados, por endereço. Um chat movimentado repete os mesmos emotes o tempo todo: cada um é baixado e
 * decodificado uma vez. Os mais antigos saem quando a memória dos quadros passa de [BUDGET_BYTES].
 */
private object EmoteCache {
    private const val BUDGET_BYTES = 96L * 1024 * 1024
    private const val MAX_FRAMES = 150
    private const val MAX_SIDE = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = LinkedHashMap<String, Deferred<EmoteFrames?>>(64, 0.75f, true)
    private val sizes = HashMap<String, Long>()
    private var total = 0L

    fun get(url: String): Deferred<EmoteFrames?> = synchronized(this) {
        entries.getOrPut(url) { scope.async { load(url)?.also { remember(url, it) } } }
    }

    private fun remember(url: String, frames: EmoteFrames) = synchronized(this) {
        sizes[url] = frames.bytes
        total += frames.bytes
        val it = entries.keys.iterator()
        while (total > BUDGET_BYTES && it.hasNext()) {
            val oldest = it.next()
            if (oldest == url) continue
            total -= sizes.remove(oldest) ?: 0L
            it.remove()
        }
    }

    private suspend fun load(url: String): EmoteFrames? = runCatching {
        val bytes = Http.client.get(url).readRawBytes()
        decode(bytes)
    }.onFailure { Timing.mark("emote: não abriu $url (${it.message})") }.getOrNull()
        ?.also { if (Timing.enabled && it.frames.size > 1) Timing.mark("emote: animado com ${it.frames.size} quadros (${it.loopMs} ms) $url") }
        ?.also { f -> f.frames.firstOrNull()?.let { EmoteRatios.known[url] = it.width.toFloat() / it.height.coerceAtLeast(1) } }

    /**
     * Decodifica todos os quadros (até [MAX_FRAMES]). Cada quadro de um GIF ou WebP animado pode depender de um anterior (só a parte que
     * mudou vem no arquivo): ele é desenhado por cima de uma cópia daquele quadro, como o próprio Skia pede.
     */
    private fun decode(bytes: ByteArray): EmoteFrames? {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        // Pixels com a transparência pré-multiplicada, como o Skia desenha (um GIF transparente chega "sem pré-multiplicar").
        val info = codec.imageInfo.withColorAlphaType(ColorAlphaType.PREMUL)
        if (info.width <= 0 || info.height <= 0 || info.width > MAX_SIDE * 4 || info.height > MAX_SIDE * 4) return null
        val count = codec.frameCount.coerceIn(1, MAX_FRAMES)
        if (count == 1) {
            val bitmap = Bitmap().apply { allocPixels(info) }
            codec.readPixels(bitmap, 0)
            bitmap.setImmutable()
            return EmoteFrames(listOf(Image.makeFromBitmap(bitmap)), intArrayOf(1_000))
        }
        val infos = codec.framesInfo
        val frames = ArrayList<Image>(count)
        val durations = IntArray(count)
        for (i in 0 until count) {
            val bitmap = Bitmap().apply { allocPixels(info) }
            val required = infos.getOrNull(i)?.requiredFrame ?: -1
            if (required in 0 until i) {
                frames[required].readPixels(bitmap)
                codec.readPixels(bitmap, i, required)
            } else {
                bitmap.erase(0)
                codec.readPixels(bitmap, i)
            }
            bitmap.setImmutable()
            frames += Image.makeFromBitmap(bitmap)
            // Como os navegadores: quadro de 0 ou quase 0 ms vira 100 ms (senão o emote "pisca" rápido demais).
            durations[i] = (infos.getOrNull(i)?.duration ?: 100).let { if (it < 20) 100 else it }
        }
        return EmoteFrames(frames, durations)
    }
}

private val DOWNSCALE: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
private val UPSCALE: SamplingMode = SamplingMode.CATMULL_ROM

@Composable
actual fun EmoteImage(url: String, modifier: Modifier) {
    val loaded by produceState<EmoteFrames?>(null, url) { value = EmoteCache.get(url).await() }
    val frames = loaded
    var index by remember(frames) { mutableIntStateOf(0) }
    if (frames != null && frames.frames.size > 1) {
        LaunchedEffect(frames) {
            while (true) {
                val now = System.currentTimeMillis()
                index = frames.indexAt(now)
                delay(frames.untilNext(now).coerceAtLeast(10))
            }
        }
    }
    Canvas(modifier) {
        val image = frames?.frames?.getOrNull(index) ?: return@Canvas
        // Cabe no espaço sem esticar, centralizado; reduzido com suavização (a imagem é a 2x).
        val scale = min(size.width / image.width, size.height / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val x = (size.width - w) / 2
        val y = (size.height - h) / 2
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeXYWH(x, y, w, h),
                if (scale < 0.98f) DOWNSCALE else UPSCALE, null, false,
            )
        }
    }
}
