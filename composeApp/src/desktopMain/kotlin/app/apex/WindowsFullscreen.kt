package app.apex

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/**
 * Tela cheia como o F11 do navegador: a janela perde a barra de título e as bordas e passa a cobrir o monitor inteiro (o Windows
 * esconde a barra de tarefas sozinho). Troca só o estilo da janela que já existe, então é instantâneo e nada é recriado.
 */
internal class WindowsFullscreen {
    private var style = 0
    private var exStyle = 0
    private var placement: WinUser.WINDOWPLACEMENT? = null

    var active: Boolean = false
        private set

    private fun handle(window: java.awt.Window) = WinDef.HWND(Native.getComponentPointer(window))

    fun enter(window: java.awt.Window): Boolean = runCatching {
        if (active) return@runCatching true
        val user32 = User32.INSTANCE
        val hwnd = handle(window)
        val saved = WinUser.WINDOWPLACEMENT().also {
            it.length = it.size()
            if (!user32.GetWindowPlacement(hwnd, it).booleanValue()) error("GetWindowPlacement falhou")
        }
        val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        if (!user32.GetMonitorInfo(monitor, info).booleanValue()) error("GetMonitorInfo falhou")
        style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        placement = saved
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle and EDGES.inv())
        val r = info.rcMonitor
        user32.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        active = true
        true
    }.getOrDefault(false)

    fun exit(window: java.awt.Window) {
        if (!active) return
        active = false
        runCatching {
            val user32 = User32.INSTANCE
            val hwnd = handle(window)
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style)
            user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle)
            // Devolve o tamanho e a posição de antes (e o "maximizado", se estava).
            placement?.let { user32.SetWindowPlacement(hwnd, it) }
            user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        }
        placement = null
    }

    private companion object {
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

/** Liga a tela cheia da janela; se o Windows não deixar mexer no estilo, cai no modo de tela cheia do próprio Compose. */
internal class FullscreenController(private val state: WindowState) {
    private val native = WindowsFullscreen()
    private var fallbackFrom: WindowPlacement? = null

    fun apply(window: java.awt.Window, on: Boolean) {
        if (on) {
            if (native.active || fallbackFrom != null) return
            if (!native.enter(window)) {
                fallbackFrom = state.placement.takeIf { it != WindowPlacement.Fullscreen } ?: WindowPlacement.Floating
                state.placement = WindowPlacement.Fullscreen
            }
        } else if (native.active) {
            native.exit(window)
        } else {
            fallbackFrom?.let { state.placement = it }
            fallbackFrom = null
        }
    }
}
