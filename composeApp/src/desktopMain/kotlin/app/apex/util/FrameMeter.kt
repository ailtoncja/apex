package app.apex.util

import androidx.compose.runtime.withFrameNanos
import com.sun.management.OperatingSystemMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Medidor de fluidez (`APEX_FRAMES=1`): a cada [PERIOD_MS] escreve em `frames.txt` quantos quadros a interface desenhou, o tempo entre eles
 * (mediana, p95, pior), quantos passaram de 25 ms (travadinhas), o GC no período, o uso de CPU do processo e, se houver vídeo, os quadros
 * que chegaram do VLC e o tempo gasto em cada um. Só mede: sem a variável não roda nada.
 */
object FrameMeter {
    @Volatile var enabled = false

    /** Vídeo: quadros entregues pelo VLC no período e o tempo (ns) gasto copiando-os para o Skia. */
    val videoFrames = AtomicLong()
    val videoCopyNanos = AtomicLong()
    val videoDrawNanos = AtomicLong()
    val videoDraws = AtomicLong()

    const val PERIOD_MS = 2_000L

    suspend fun run(file: File) {
        val os = runCatching { ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean }.getOrNull()
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        val intervals = ArrayList<Long>(512)
        var last = 0L
        var windowStart = System.nanoTime()
        var gcCount = gcs.sumOf { it.collectionCount }
        var gcTime = gcs.sumOf { it.collectionTime }
        file.appendText("--- medidor ligado ---" + System.lineSeparator())
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    intervals += (now - last)
                    if (now - last > 40_000_000) file.appendText("  travada de ${(now - last) / 1_000_000} ms às ${java.time.LocalTime.now().withNano(0)}.${"%03d".format(System.currentTimeMillis() % 1000)}" + System.lineSeparator())
                }
                last = now
                val elapsedMs = (System.nanoTime() - windowStart) / 1_000_000
                if (elapsedMs >= PERIOD_MS) {
                    val sorted = intervals.sorted()
                    fun pct(p: Double) = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()] / 1_000_000
                    val jank = intervals.count { it > 25_000_000 }
                    val gcNow = gcs.sumOf { it.collectionCount }
                    val gcTimeNow = gcs.sumOf { it.collectionTime }
                    val rt = Runtime.getRuntime()
                    val v = videoFrames.getAndSet(0)
                    val vcopy = videoCopyNanos.getAndSet(0)
                    val vd = videoDraws.getAndSet(0)
                    val vdraw = videoDrawNanos.getAndSet(0)
                    val video = if (v > 0 || vd > 0) " | vídeo: ${v * 1000 / elapsedMs}/s entregues (cópia ${if (v > 0) vcopy / v / 1000 else 0} µs), ${vd * 1000 / elapsedMs}/s desenhados (${if (vd > 0) vdraw / vd / 1000 else 0} µs)" else ""
                    file.appendText(
                        "quadros ${intervals.size * 1000 / elapsedMs}/s | intervalo ms: mediana ${pct(0.5)} p95 ${pct(0.95)} pior ${sorted.lastOrNull()?.div(1_000_000) ?: 0} | >25ms: $jank" +
                            " | GC ${gcNow - gcCount}x ${gcTimeNow - gcTime} ms | CPU ${((os?.processCpuLoad ?: 0.0) * 100).toInt()}% | heap ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20} MB$video" +
                            System.lineSeparator(),
                    )
                    gcCount = gcNow; gcTime = gcTimeNow
                    intervals.clear()
                    windowStart = System.nanoTime()
                }
            }
        }
    }
}
