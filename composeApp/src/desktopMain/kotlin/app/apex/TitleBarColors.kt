package app.apex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.apex.theme.Palette
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference

/**
 * A barra de título do Windows nas cores do tema: sem isto ela fica branca em cima de um app escuro e parece uma faixa a mais de moldura.
 * No Windows 11 a barra, o texto e a borda da janela tomam as cores do tema (a barra some na barra superior do app); no Windows 10 só dá
 * para pedir a barra escura nos temas escuros. Os botões de minimizar, maximizar e fechar seguem o claro ou escuro sozinhos.
 */
internal object TitleBarColors {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    /** Pinta a barra de [window] com o fundo, o texto apagado e a borda do tema [palette]. `false` se o Windows não aceitou a cor. */
    fun apply(window: java.awt.Window, palette: Palette): Boolean {
        val api = dwm ?: return false
        val pointer = Native.getComponentPointer(window) ?: return false
        val hwnd = WinDef.HWND(pointer)
        val dark = if (palette.isLight) 0 else 1
        // O número do "modo escuro" mudou numa atualização do Windows 10 (20 hoje, 19 antes do 20H1).
        if (!set(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark)) set(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, dark)
        val caption = set(api, hwnd, DWMWA_CAPTION_COLOR, colorRef(palette.background))
        set(api, hwnd, DWMWA_TEXT_COLOR, colorRef(palette.muted))
        set(api, hwnd, DWMWA_BORDER_COLOR, colorRef(palette.background))
        return caption
    }

    private fun set(api: Dwm, hwnd: WinDef.HWND, attribute: Int, value: Int): Boolean =
        runCatching { api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4) == 0 }.getOrDefault(false)

    /** O COLORREF do Windows é 0x00BBGGRR. */
    internal fun colorRef(color: Color): Int {
        val argb = color.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36
}
