package app.apex.browser

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

enum class BrowserEngine { Firefox, Chromium }

class InstalledBrowser(
    val id: String,
    val label: String,
    val exe: File,
    val engine: BrowserEngine,
    /** Firefox: parte do nome da pasta do perfil (a edição Developer usa `dev-edition`). */
    val profileHint: String? = null,
)

/** Descobre quais navegadores estão instalados e qual é o padrão do Windows. */
object Browsers {
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun firstExisting(vararg paths: String?): File? = paths.filterNotNull().map(::File).firstOrNull { it.isFile }

    fun installed(): List<InstalledBrowser> {
        val pf = env("ProgramFiles")
        val pf86 = env("ProgramFiles(x86)")
        val local = env("LOCALAPPDATA")
        return buildList {
            firstExisting(pf?.let { "$it\\Mozilla Firefox\\firefox.exe" }, pf86?.let { "$it\\Mozilla Firefox\\firefox.exe" })
                ?.let { add(InstalledBrowser("firefox", "Firefox", it, BrowserEngine.Firefox, null)) }
            firstExisting(pf?.let { "$it\\Firefox Developer Edition\\firefox.exe" }, pf86?.let { "$it\\Firefox Developer Edition\\firefox.exe" })
                ?.let { add(InstalledBrowser("firefox-dev", "Firefox Developer Edition", it, BrowserEngine.Firefox, "dev-edition")) }
            firstExisting(
                pf?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" }, pf86?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                local?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            )?.let { add(InstalledBrowser("chrome", "Google Chrome", it, BrowserEngine.Chromium)) }
            firstExisting(pf86?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" }, pf?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" })
                ?.let { add(InstalledBrowser("edge", "Microsoft Edge", it, BrowserEngine.Chromium)) }
            firstExisting(
                pf?.let { "$it\\BraveSoftware\\Brave-Browser\\Application\\brave.exe" },
                local?.let { "$it\\BraveSoftware\\Brave-Browser\\Application\\brave.exe" },
            )?.let { add(InstalledBrowser("brave", "Brave", it, BrowserEngine.Chromium)) }
            firstExisting(local?.let { "$it\\Vivaldi\\Application\\vivaldi.exe" }, pf?.let { "$it\\Vivaldi\\Application\\vivaldi.exe" })
                ?.let { add(InstalledBrowser("vivaldi", "Vivaldi", it, BrowserEngine.Chromium)) }
            firstExisting(local?.let { "$it\\Programs\\Opera\\opera.exe" }, pf?.let { "$it\\Opera\\opera.exe" })
                ?.let { add(InstalledBrowser("opera", "Opera", it, BrowserEngine.Chromium)) }
        }
    }

    /** O navegador padrão do Windows (para links https), se for um dos que sabemos usar. */
    fun defaultId(installed: List<InstalledBrowser> = installed()): String? = runCatching {
        val progId = Advapi32Util.registryGetStringValue(
            WinReg.HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice", "ProgId",
        )
        val command = Advapi32Util.registryGetStringValue(WinReg.HKEY_CLASSES_ROOT, "$progId\\shell\\open\\command", "")
        installed.firstOrNull { command.contains(it.exe.path, ignoreCase = true) }?.id
    }.getOrNull()
}
