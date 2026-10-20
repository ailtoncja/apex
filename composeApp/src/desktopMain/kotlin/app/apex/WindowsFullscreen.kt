package app.apex

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.EventQueue
import java.util.concurrent.Executors

/**
 * Tela cheia como o F11 do navegador: a janela perde a barra de título e as bordas e passa a cobrir o monitor inteiro (o Windows
 * esconde a barra de tarefas sozinho). Troca só o estilo da janela que já existe, então é instantâneo e nada é recriado.
 */
internal class WindowsFullscreen(
    /** Só para ensaio (`APEX_BACKGROUND=1`): a "tela cheia" acontece fora da tela, com o tamanho do monitor, sem cobrir o que a pessoa usa. */
    private val offscreen: Boolean = false,
) {
    private var style = 0
    private var exStyle = 0
    /** Onde a janela estava (já fora do "maximizado") e se estava maximizada, para voltar igual. */
    private var rect: WinDef.RECT? = null
    private var maximized = false

    @Volatile var active: Boolean = false
        private set

    private fun handle(window: java.awt.Window) = WinDef.HWND(Native.getComponentPointer(window))

    /**
     * Entra na tela cheia como o Chrome faz: uma janela maximizada primeiro volta ao tamanho normal. Tirar as bordas de uma janela que continua
     * "maximizada" para o Windows fazia a saída falhar: ao devolver o estilo, o Windows a achava já maximizada e não a ajeitava, e ela ficava
     * do tamanho do monitor com a barra de título por cima, com o conteúdo de um tamanho e a janela de outro (a faixa branca ao voltar).
     */
    fun enter(window: java.awt.Window): Boolean = runCatching {
        if (active) return@runCatching true
        val user32 = User32.INSTANCE
        val hwnd = handle(window)
        maximized = (user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WS_MAXIMIZE) != 0
        if (maximized) user32.SendMessage(hwnd, WM_SYSCOMMAND, WinDef.WPARAM(SC_RESTORE.toLong()), WinDef.LPARAM(0))
        val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        if (!user32.GetMonitorInfo(monitor, info).booleanValue()) error("GetMonitorInfo falhou")
        style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        rect = WinDef.RECT().also { if (!user32.GetWindowRect(hwnd, it)) error("GetWindowRect falhou") }
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle and EDGES.inv())
        val r = info.rcMonitor
        user32.SetWindowPos(hwnd, null, if (offscreen) -30000 else r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        active = true
        true
    }.getOrDefault(false)

    /** Sai: devolve o estilo, o tamanho e a posição de antes e, se estava maximizada, maximiza de novo (como o Chrome). */
    fun exit(window: java.awt.Window) {
        if (!active) return
        active = false
        runCatching {
            val user32 = User32.INSTANCE
            val hwnd = handle(window)
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style)
            user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle)
            rect?.let { r ->
                user32.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            }
            if (maximized) user32.SendMessage(hwnd, WM_SYSCOMMAND, WinDef.WPARAM(SC_MAXIMIZE.toLong()), WinDef.LPARAM(0))
        }
        rect = null
    }

    companion object {
        /** Só para ensaio (`APEX_INVISIBLE=1`): a janela continua existindo e desenhando, mas transparente, sem cliques e fora da barra de tarefas. */
        fun makeInvisible(window: java.awt.Window) {
            runCatching {
                val user32 = User32.INSTANCE
                val hwnd = WinDef.HWND(Native.getComponentPointer(window))
                val ex = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
                user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, ex or WS_EX_LAYERED or WS_EX_TRANSPARENT or WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE)
                user32.SetLayeredWindowAttributes(hwnd, 0, 0.toByte(), LWA_ALPHA)
            }
        }

        /** Só para medir (`APEX_TIMING=1`): o tamanho da janela no Windows e o que o Java acha do conteúdo; a parte branca é a diferença. */
        fun describe(window: java.awt.Window): String = runCatching {
            val user32 = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            val w = WinDef.RECT().also { user32.GetWindowRect(hwnd, it) }
            val c = WinDef.RECT().also { user32.GetClientRect(hwnd, it) }
            val content = (window as? javax.swing.JFrame)?.contentPane
            val zoomed = (user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and 0x01000000) != 0
            "Windows: janela ${w.right - w.left}x${w.bottom - w.top} em (${w.left},${w.top}), cliente ${c.right}x${c.bottom}, maximizada=$zoomed; " +
                "Java: janela ${window.width}x${window.height}, bordas ${window.insets.let { "${it.top}/${it.left}/${it.bottom}/${it.right}" }}, " +
                "conteúdo ${content?.width}x${content?.height}"
        }.getOrElse { "sem medida (${it.message})" }

        const val WS_EX_LAYERED = 0x00080000
        const val WS_EX_TRANSPARENT = 0x00000020
        const val WS_EX_TOOLWINDOW = 0x00000080
        const val WS_EX_NOACTIVATE = 0x08000000
        const val LWA_ALPHA = 0x00000002
        const val WS_MAXIMIZE = 0x01000000
        const val WM_SYSCOMMAND = 0x0112
        const val SC_RESTORE = 0xF120
        const val SC_MAXIMIZE = 0xF030
        const val WS_CAPTION = 0x00C00000
        const val WS_THICKFRAME = 0x00040000
        const val WS_EX_DLGMODALFRAME = 0x00000001
        const val WS_EX_WINDOWEDGE = 0x00000100
        const val WS_EX_CLIENTEDGE = 0x00000200
        const val WS_EX_STATICEDGE = 0x00020000
        const val EDGES = WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE
        const val SWP_NOSIZE = 0x0001
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_FRAMECHANGED = 0x0020
        const val MONITOR_DEFAULTTONEAREST = 2
    }
}

/**
 * Liga a tela cheia da janela; se o Windows não deixar mexer no estilo, cai no modo de tela cheia do próprio Compose.
 *
 * As chamadas ao Windows (trocar o estilo, o tamanho e a posição da janela) saem de uma thread própria, nunca da thread da interface. Elas
 * esperam a thread das janelas do Java processar as mensagens da janela; se essa thread, no meio disso, esperar a da interface (o que o Java
 * faz em alguns redimensionamentos), as duas ficam esperando uma pela outra para sempre e o app trava. Fora da interface, ela segue livre.
 */
internal class FullscreenController(private val state: WindowState, offscreen: Boolean = false) {
    private val native = WindowsFullscreen(offscreen)

    /** De onde a janela veio quando o Windows recusou e valeu a tela cheia do Compose (só a thread da interface mexe). */
    private var fallbackFrom: WindowPlacement? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "apex-fullscreen").apply { isDaemon = true } }

    /**
     * Com as bordas trocadas por fora do Java, ele pode não refazer o encaixe do conteúdo quando o tamanho da janela não muda (só as bordas):
     * o conteúdo ficava de um tamanho e a área da janela de outro. Aqui ele refaz, já e de novo logo depois (o Windows avisa aos poucos).
     */
    private fun relayout(window: java.awt.Window) {
        val again = { window.invalidate(); window.validate(); window.repaint() }
        EventQueue.invokeLater(again)
        Thread { Thread.sleep(250); EventQueue.invokeLater(again) }.apply { isDaemon = true }.start()
    }

    /** Chamado na thread da interface; o Windows é chamado na thread [worker], na ordem dos pedidos. */
    fun apply(window: java.awt.Window, on: Boolean) {
        if (fallbackFrom != null) {
            if (!on) {
                fallbackFrom?.let { state.placement = it }
                fallbackFrom = null
            }
            return
        }
        if (app.apex.util.Timing.enabled) {
            // Como ficou a janela meio segundo depois (o Java recebe o tamanho novo do Windows aos poucos).
            Thread {
                Thread.sleep(600)
                EventQueue.invokeLater { app.apex.util.Timing.mark("tela cheia ${if (on) "ligada" else "desligada"}: ${WindowsFullscreen.describe(window)}") }
            }.apply { isDaemon = true }.start()
        }
        worker.execute {
            if (!on) {
                native.exit(window)
                return@execute relayout(window)
            }
            if (native.enter(window)) return@execute relayout(window)
            EventQueue.invokeLater {
                if (fallbackFrom == null && state.placement != WindowPlacement.Fullscreen) {
                    fallbackFrom = state.placement
                    state.placement = WindowPlacement.Fullscreen
                }
            }
        }
    }
}
