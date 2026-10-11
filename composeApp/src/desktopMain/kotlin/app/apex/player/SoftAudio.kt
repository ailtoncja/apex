package app.apex.player

import app.apex.util.Timing
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A saída de som do Apex. O libVLC entrega o áudio já decodificado (callbacks "amem", 16 bits, 48 kHz, estéreo) e o app toca pelo Java
 * Sound com um buffer curto, aplicando o volume na hora de tocar.
 *
 * Por que não deixar o VLC tocar: no Windows ele guarda até 2 s de áudio no buffer de saída e, acima de 100%, amplifica por software antes
 * desse buffer, então cada mudança de volume levava ~1,2 s para ser ouvida (medido). Até 100% ele usava o volume da sessão do Windows, que é
 * imediato; mas qualquer pré-ganho satura antes desse volume. Aqui o ganho entra por último, num buffer de [QUEUE_MS] + [LINE_MS]: a mudança
 * é ouvida em ~0,1 s em qualquer faixa, e a curva de volume é a mesma do VLC ([gainFor]: cúbica, 200% = 8x).
 *
 * O VLC só chama [push] quando o bloco deve tocar em breve (sem "time_get" ele considera o bloco tocado ao entregar), por isso o buffer curto
 * mantém o som alinhado ao relógio dele; [DESYNC_MS] compensa o pouco que fica na fila e na linha. Quando a fila está cheia, [push] segura a
 * thread do VLC (é o que o próprio módulo de saída dele faz).
 */
internal class SoftAudio {
    /** O fator aplicado às amostras (0 = mudo); muda na hora, de qualquer thread. */
    @Volatile var factor = 1f

    private val lock = Object()
    private val queue = ArrayDeque<ShortArray>()
    private var queuedFrames = 0
    @Volatile private var running = false
    @Volatile private var line: SourceDataLine? = null
    private var writer: Thread? = null
    private val id = ids.incrementAndGet()

    /** Abre a linha de som (a padrão do Windows neste momento) e a thread que toca. Chamado antes de cada abertura de vídeo. */
    fun start(): Boolean {
        stop()
        val l = try {
            AudioSystem.getSourceDataLine(FORMAT).also {
                it.open(FORMAT, RATE * LINE_MS / 1000 * FRAME_BYTES)
                it.start()
            }
        } catch (e: Exception) {
            Timing.mark("som: não abriu a saída (${e.message})")
            return false
        }
        line = l
        running = true
        writer = Thread({ pump(l) }, "apex-audio-$id").apply { isDaemon = true; start() }
        return true
    }

    /** Da thread do VLC: [count] quadros (estéreo, 16 bits) em [samples]. Espera se a fila está cheia; volta na hora se a saída parou. */
    fun push(samples: Pointer, count: Int) {
        if (!running || count <= 0) return
        val block = samples.getShortArray(0, count * CHANNELS)
        synchronized(lock) {
            while (running && queuedFrames >= MAX_QUEUE_FRAMES) lock.wait(5)
            if (!running) return
            queue.addLast(block)
            queuedFrames += count
            lock.notifyAll()
        }
    }

    /** O VLC pulou de posição: o que estava esperando não vale mais. */
    fun flush() {
        synchronized(lock) {
            queue.clear()
            queuedFrames = 0
            lock.notifyAll()
        }
        runCatching { line?.flush() }
    }

    /** Para e fecha a saída; solta a thread do VLC se ela estava esperando na fila. */
    fun stop() {
        running = false
        synchronized(lock) {
            queue.clear()
            queuedFrames = 0
            lock.notifyAll()
        }
        val l = line
        line = null
        if (l != null) runCatching { l.stop(); l.flush() }
        writer?.let { runCatching { it.join(500) } }
        writer = null
        if (l != null) runCatching { l.close() }
    }

    private fun pump(l: SourceDataLine) {
        while (running) {
            val block = synchronized(lock) {
                while (running && queue.isEmpty()) lock.wait(20)
                if (!running) return
                val b = queue.removeFirst()
                queuedFrames -= b.size / CHANNELS
                lock.notifyAll()
                b
            }
            val bytes = withGain(block, factor)
            try {
                l.write(bytes, 0, bytes.size) // bloqueia enquanto a linha está cheia: é isso que mantém o buffer curto
            } catch (e: Exception) {
                if (running) Timing.mark("som: a saída falhou (${e.message})")
                return
            }
        }
    }

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        private const val FRAME_BYTES = CHANNELS * 2

        /** Quanto de áudio pode esperar na fila antes de segurar o VLC. */
        const val QUEUE_MS = 60

        /** O buffer da linha do Java Sound. */
        const val LINE_MS = 60

        /**
         * Quanto o VLC deve adiantar o áudio (opção `audio-desync`, em ms, negativa) para compensar o que fica na fila, na linha e no
         * dispositivo: sem isso o som chegava uns 140 ms depois da imagem (medido com o protótipo).
         */
        const val DESYNC_MS = -100

        private const val MAX_QUEUE_FRAMES = RATE * QUEUE_MS / 1000
        private val FORMAT = AudioFormat(RATE.toFloat(), 16, CHANNELS, true, false)
        private val ids = AtomicInteger()

        /** O formato que o VLC deve entregar (16 bits, ordem nativa). */
        const val VLC_FORMAT = "S16N"

        /** A saída do Java Sound existe e abre neste PC (senão o VLC toca direto, como antes). */
        fun available(): Boolean = runCatching {
            AudioSystem.getSourceDataLine(FORMAT).also { it.open(FORMAT, RATE * LINE_MS / 1000 * FRAME_BYTES); it.close() }
            true
        }.getOrDefault(false)

        /**
         * O fator de amplitude para o volume [percent] (0 a 200): a mesma curva cúbica do VLC no Windows (50% = 0,125x, 100% = 1x,
         * 200% = 8x); mudo = 0.
         */
        fun gainFor(percent: Int, muted: Boolean): Float = if (muted) 0f else (percent.coerceIn(0, MAX_VOLUME) / 100f).pow(3)

        /** As amostras vezes [gain], saturando em 16 bits, em bytes little-endian prontos para a linha. */
        fun withGain(block: ShortArray, gain: Float): ByteArray {
            val out = ByteArray(block.size * 2)
            for (i in block.indices) {
                val v = if (gain == 1f) block[i].toInt() else (block[i] * gain).roundToInt().coerceIn(-32768, 32767)
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
