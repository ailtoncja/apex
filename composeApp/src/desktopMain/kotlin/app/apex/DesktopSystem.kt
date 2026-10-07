package app.apex

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import app.apex.data.Account
import app.apex.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

class DesktopSystem(
    private val windowState: WindowState,
    private val accounts: DesktopAccounts,
) : SystemServices {
    private val _fullscreen = MutableStateFlow(false)
    override val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private var placementBeforeFullscreen = WindowPlacement.Floating

    override fun setFullscreen(on: Boolean) {
        if (on == _fullscreen.value) return
        if (on) {
            placementBeforeFullscreen = windowState.placement.takeIf { it != WindowPlacement.Fullscreen } ?: WindowPlacement.Floating
            windowState.placement = WindowPlacement.Fullscreen
        } else {
            windowState.placement = placementBeforeFullscreen
        }
        _fullscreen.value = on
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun copyText(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun saveDownload(fileName: String, text: String): String? = runCatching {
        val dir = File(System.getProperty("user.home"), "Downloads").also { it.mkdirs() }
        val name = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        // Nunca sobrescreve um arquivo que já existe.
        val target = generateSequence(0) { it + 1 }.map { n -> File(dir, if (n == 0) fileName else "$name ($n)$ext") }.first { !it.exists() }
        target.writeText(text, Charsets.UTF_8)
        target.absolutePath
    }.getOrNull()

    override val installedBrowsers: List<BrowserOption> get() = accounts.installedBrowserOptions()

    override val defaultBrowserId: String? get() = accounts.defaultBrowserId()

    override suspend fun browserLogin(platform: Platform, browserId: String, onStatus: (String) -> Unit): Account? =
        accounts.browserLogin(platform, browserId, onStatus)

    override fun signOut(platform: Platform) = accounts.signOut(platform)

    override suspend fun refreshAccount(account: Account): Account? = withContext(Dispatchers.IO) { accounts.refreshLinked(account) }
}
