package app.apex.util

import java.awt.EventQueue
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vigia de travamento: a cada segundo pede à thread da interface para responder; se ela passar de [LIMIT_MS] sem responder (a janela "não
 * responde"), grava em [file] o que cada thread estava fazendo naquela hora, com quem segura cada trava. É o que diz por que o app travou
 * quando a pessoa não consegue reproduzir com alguém olhando. Só escreve quando trava; guarda as últimas vezes (até [MAX_BYTES]).
 */
object FreezeWatch {
    private const val LIMIT_MS = 4_000L
    private const val MAX_BYTES = 2L * 1024 * 1024

    @Volatile private var started = false

    fun start(file: File) {
        if (started) return
        started = true
        Thread({ loop(file) }, "apex-freeze-watch").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    private fun loop(file: File) {
        while (true) {
            val answered = AtomicBoolean(false)
            val asked = System.currentTimeMillis()
            EventQueue.invokeLater { answered.set(true) }
            while (!answered.get() && System.currentTimeMillis() - asked < LIMIT_MS) Thread.sleep(50)
            if (!answered.get()) {
                Timing.mark("INTERFACE TRAVADA há ${LIMIT_MS / 1000} s: estado das threads em ${file.name}")
                runCatching { dump(file, "a interface não responde há ${LIMIT_MS / 1000} s") }
                // Uma vez por travamento: espera ela voltar (e registra quanto durou).
                while (!answered.get()) Thread.sleep(200)
                val took = System.currentTimeMillis() - asked
                Timing.mark("interface voltou depois de $took ms")
                runCatching { file.appendText("  (voltou depois de $took ms)\n\n") }
            }
            Thread.sleep(1_000)
        }
    }

    private fun dump(file: File, reason: String) {
        if (file.exists() && file.length() > MAX_BYTES) file.writeText(file.readText().takeLast((MAX_BYTES / 2).toInt()))
        val sb = StringBuilder()
        sb.append("==== ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}: $reason\n")
        val threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)
        // As threads que importam primeiro: a da interface, as das janelas do Windows, as do player.
        val order = { name: String ->
            when {
                name.startsWith("AWT-EventQueue") -> 0
                name.startsWith("AWT-") -> 1
                name.startsWith("apex-vlc") -> 2
                else -> 3
            }
        }
        for (t in threads.sortedBy { order(it.threadName) }) {
            sb.append("\"${t.threadName}\" ${t.threadState}")
            t.lockName?.let { sb.append(" esperando $it") }
            t.lockOwnerName?.let { sb.append(" (com \"$it\")") }
            sb.append('\n')
            t.stackTrace.take(40).forEach { sb.append("    at ").append(it).append('\n') }
            t.lockedMonitors.forEach { sb.append("    - segura ").append(it).append('\n') }
            sb.append('\n')
        }
        file.appendText(sb.toString())
    }
}
