package app.apex

import app.apex.browser.BrowserEngine
import app.apex.browser.Browsers
import app.apex.browser.ChromiumSession
import app.apex.browser.FirefoxCookies
import app.apex.browser.InstalledBrowser
import app.apex.data.Account
import app.apex.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext

class Cookie(val domain: String, val name: String, val value: String, val line: String)

/**
 * Entrar nas contas pelo navegador que o usuário já tem instalado:
 * - Firefox: abre o Firefox normal e lê o login do perfil dele (se já estiver logado, importa na hora).
 * - Chrome, Edge, Brave e outros Chromium: abre uma janela separada só do Apex e lê o login pelo protocolo de depuração.
 */
class DesktopAccounts(private val dataDir: File) {
    /** Onde o yt-dlp lê os cookies para o YouTube; só as linhas do Google/YouTube. */
    val youtubeCookieFile: File get() = File(dataDir, "cookies/youtube.txt")

    private val browsers: List<InstalledBrowser> by lazy { Browsers.installed() }

    fun installedBrowsers(): List<BrowserOption> = browsers.map {
        BrowserOption(
            it.id, it.label,
            when (it.engine) {
                BrowserEngine.Firefox -> "Usa o seu perfil do ${it.label}. Se você já estiver logado, o Apex importa na hora."
                BrowserEngine.Chromium -> "Abre uma janela separada do ${it.label}, só do Apex. Entre na conta lá e ela fecha sozinha."
            },
        )
    }

    fun defaultBrowserId(): String? = Browsers.defaultId(browsers)

    private fun loginUrl(platform: Platform) = when (platform) {
        Platform.YouTube -> "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
        Platform.Twitch -> "https://www.twitch.tv/login"
        Platform.Kick -> "https://kick.com/"
    }

    /** Abre o navegador escolhido, espera o usuário entrar e devolve a conta (ou `null` se ele desistiu ou fechou a janela). */
    suspend fun browserLogin(platform: Platform, browserId: String, onStatus: (String) -> Unit): Account? = withContext(Dispatchers.IO) {
        val browser = browsers.firstOrNull { it.id == browserId } ?: return@withContext null
        withTimeoutOrNull(10 * 60_000L) {
            when (browser.engine) {
                BrowserEngine.Firefox -> firefoxLogin(platform, browser, onStatus)
                BrowserEngine.Chromium -> chromiumLogin(platform, browser, onStatus)
            }
        }
    }

    private suspend fun firefoxLogin(platform: Platform, browser: InstalledBrowser, onStatus: (String) -> Unit): Account? {
        accountFromCookies(platform, FirefoxCookies.read(browser))?.let {
            onStatus("Encontrei o seu login no ${browser.label}.")
            return it
        }
        onStatus("Entre na sua conta na janela do ${browser.label}. O Apex detecta sozinho.")
        ProcessBuilder(browser.exe.absolutePath, loginUrl(platform)).start()
        while (coroutineContext.isActive) {
            delay(2_500)
            accountFromCookies(platform, runCatching { FirefoxCookies.read(browser) }.getOrDefault(emptyList()))?.let { return it }
        }
        return null
    }

    private suspend fun chromiumLogin(platform: Platform, browser: InstalledBrowser, onStatus: (String) -> Unit): Account? {
        onStatus("Abrindo o ${browser.label}…")
        val profile = File(dataDir, "login-browser/${browser.id}")
        ChromiumSession.open(browser.exe, profile, loginUrl(platform)).use { session ->
            onStatus("Entre na sua conta na janela que abriu. O Apex detecta sozinho e fecha a janela.")
            while (coroutineContext.isActive) {
                delay(2_000)
                val cookies = runCatching { session.cookies() }.getOrNull()
                if (cookies != null) accountFromCookies(platform, cookies)?.let { return it }
                if (!session.isAlive) return null
            }
        }
        return null
    }

    fun accountFromCookies(platform: Platform, cookies: List<Cookie>): Account? {
        fun host(c: Cookie, vararg hosts: String) = c.domain.trimStart('.').let { d -> hosts.any { d == it || d.endsWith(".$it") } }
        return when (platform) {
            Platform.YouTube -> {
                val yt = cookies.filter { host(it, "youtube.com") }
                val google = cookies.filter { host(it, "google.com") }
                val hasLogin = (yt + google).any { it.name == "SAPISID" || it.name == "__Secure-3PAPISID" }
                if (!hasLogin) return null
                // O SAPISID costuma existir só no google.com; junta os dois (os do youtube.com vencem).
                val header = (google + yt).associate { it.name to it.value }.entries.joinToString("; ") { "${it.key}=${it.value}" }
                writeFilteredJar(youtubeCookieFile, (yt + google).map { it.line })
                Account(Platform.YouTube, "Conta do Google", null, header)
            }
            Platform.Twitch -> {
                val tw = cookies.filter { host(it, "twitch.tv") }
                val token = tw.firstOrNull { it.name == "auth-token" }?.value ?: return null
                val login = tw.firstOrNull { it.name == "login" }?.value ?: tw.firstOrNull { it.name == "name" }?.value
                Account(Platform.Twitch, login ?: "Conta da Twitch", null, token)
            }
            Platform.Kick -> {
                val kick = cookies.filter { host(it, "kick.com") }
                val raw = kick.firstOrNull { it.name == "session_token" }?.value ?: return null
                Account(Platform.Kick, "Conta da Kick", null, runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw))
            }
        }
    }

    private fun writeFilteredJar(target: File, lines: List<String>) {
        target.parentFile.mkdirs()
        target.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
    }

    fun signOut(platform: Platform) {
        if (platform == Platform.YouTube) youtubeCookieFile.delete()
    }
}
