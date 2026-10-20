package app.apex.player

import app.apex.util.Timing
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.CopyOnWriteArraySet

/** O quanto a imagem do vídeo é vista agora (ver [ViewingWatch]). */
enum class Viewing {
    /** O Apex em foco, ou outro programa numa janela comum: vídeo como sempre. */
    Normal,

    /** Um programa em tela cheia (um jogo) em OUTRO monitor, com o Apex à vista no dele: vídeo a até 30 quadros por segundo. */
    Gaming,

    /** O Apex minimizado, ou um programa em tela cheia no MESMO monitor (por cima dele): ninguém vê a imagem, só o som continua. */
    Hidden,
}

/** Um retângulo da tela (esquerda, topo, direita, base). */
internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun covers(other: ScreenRect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/**
 * Decide o [Viewing] pelo estado das janelas. Do programa em foco: [foregroundRect], se tem barra de título ([foregroundCaption]) e o
 * monitor dele ([foregroundMonitor], com a área de trabalho [foregroundWork], sem a barra de tarefas); [ownMonitor] é o monitor do Apex.
 * A área de trabalho e a barra de tarefas ocupam a tela toda sem cobrir nada, por isso [foregroundIsShell].
 *
 * - No mesmo monitor do Apex, uma janela que cobre a tela (tela cheia, ou maximizada) esconde o Apex: [Viewing.Hidden].
 * - Em outro monitor, só um programa em tela cheia de verdade (sem barra de título, cobrindo o monitor: um jogo) vale [Viewing.Gaming]; um
 *   navegador maximizado lá não muda nada.
 */
internal fun viewingFor(
    minimized: Boolean,
    foregroundIsOurs: Boolean,
    foregroundIsShell: Boolean,
    foregroundRect: ScreenRect?,
    foregroundCaption: Boolean,
    foregroundMonitor: ScreenRect?,
    foregroundWork: ScreenRect?,
    ownMonitor: ScreenRect?,
): Viewing {
    if (minimized) return Viewing.Hidden
    if (foregroundIsOurs || foregroundIsShell || foregroundRect == null || foregroundMonitor == null) return Viewing.Normal
    val fullscreen = foregroundRect.covers(foregroundMonitor) && !foregroundCaption
    val coversWork = foregroundWork != null && foregroundRect.covers(foregroundWork)
    return when {
        foregroundMonitor == ownMonitor -> if (fullscreen || coversWork) Viewing.Hidden else Viewing.Normal
        fullscreen -> Viewing.Gaming
        else -> Viewing.Normal
    }
}

/**
 * Vigia de quanto o vídeo é visto, para o Apex não pesar num jogo. Um vídeo 1080p60 custa perto de um núcleo do processador e um tanto da
 * placa de vídeo (decodificar, converter cada quadro e desenhar a janela de novo), e isso continuava com o Apex minimizado ou atrás de um
 * jogo em tela cheia, roubando quadros do jogo. A cada [PERIOD_MS] confere as janelas e avisa os players ([listeners]): escondido, eles
 * desligam a imagem no VLC (o som segue); com um jogo em outro monitor, entregam no máximo 30 quadros por segundo.
 */
object ViewingWatch {
    private const val PERIOD_MS = 700L

    @Volatile var viewing: Viewing = Viewing.Normal
        private set

    /** Ligado nos Ajustes ("Poupar o PC durante jogos"). */
    @Volatile var enabled: () -> Boolean = { true }

    /**
     * Só para ensaio (`APEX_VIEWING=normal|gaming|hidden|cycle`): finge esse estado, para medir sem um jogo aberto; `cycle` fica normal até 20 s
     * depois de abrir, escondido até 35 s e normal de novo.
     */
    private val forcedMode = System.getenv("APEX_VIEWING")
    private val bornAt = System.currentTimeMillis()

    private fun forced(): Viewing? = when (forcedMode) {
        "normal" -> Viewing.Normal
        "gaming" -> Viewing.Gaming
        "hidden" -> Viewing.Hidden
        "cycle" -> (System.currentTimeMillis() - bornAt).let { if (it in 20_000..35_000) Viewing.Hidden else Viewing.Normal }
        else -> null
    }

    internal val listeners = CopyOnWriteArraySet<(Viewing) -> Unit>()

    @Volatile private var started = false

    fun start(window: java.awt.Window) {
        if (started) return
        started = true
        Thread({ loop(window) }, "apex-viewing").apply { isDaemon = true }.start()
    }

    private fun loop(window: java.awt.Window) {
        while (true) {
            val now = if (!enabled()) Viewing.Normal else forced() ?: runCatching { measure(window) }.getOrDefault(Viewing.Normal)
            if (now != viewing) {
                viewing = now
                Timing.mark("visão do vídeo: $now")
                listeners.forEach { runCatching { it(now) } }
            }
            Thread.sleep(PERIOD_MS)
        }
    }

    private fun rectOf(r: WinDef.RECT) = ScreenRect(r.left, r.top, r.right, r.bottom)

    /** O monitor da janela [hwnd] e a área de trabalho dele (sem a barra de tarefas). */
    private fun monitorOf(user32: User32, hwnd: WinDef.HWND): Pair<ScreenRect, ScreenRect>? {
        val info = WinUser.MONITORINFO()
        val monitor = user32.MonitorFromWindow(hwnd, 2 /* MONITOR_DEFAULTTONEAREST */) ?: return null
        return if (user32.GetMonitorInfo(monitor, info).booleanValue()) rectOf(info.rcMonitor) to rectOf(info.rcWork) else null
    }

    private fun measure(window: java.awt.Window): Viewing {
        val user32 = User32.INSTANCE
        val own = WinDef.HWND(Native.getComponentPointer(window) ?: return Viewing.Normal)
        val minimized = (user32.GetWindowLong(own, WinUser.GWL_STYLE) and WS_MINIMIZE) != 0
        val fg = user32.GetForegroundWindow()
        val pid = IntByReference()
        if (fg != null) user32.GetWindowThreadProcessId(fg, pid)
        val ours = fg == null || pid.value == Kernel32.INSTANCE.GetCurrentProcessId()
        val shell = fg != null && CharArray(64).let { buf ->
            val n = user32.GetClassName(fg, buf, buf.size)
            String(buf, 0, n) in SHELL_CLASSES
        }
        val fgRect = fg?.let { WinDef.RECT().also { r -> user32.GetWindowRect(fg, r) } }?.let(::rectOf)
        val caption = fg != null && (user32.GetWindowLong(fg, WinUser.GWL_STYLE) and WS_CAPTION) == WS_CAPTION
        val fgMonitor = fg?.let { monitorOf(user32, it) }
        return viewingFor(minimized, ours, shell, fgRect, caption, fgMonitor?.first, fgMonitor?.second, monitorOf(user32, own)?.first)
    }

    private const val WS_MINIMIZE = 0x20000000
    private const val WS_CAPTION = 0x00C00000
    private val SHELL_CLASSES = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")
}
