package app.apex.browser

import app.apex.DesktopAccounts
import app.apex.model.Platform
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserLoginTest {
    private fun tempDir() = Files.createTempDirectory("apex-browser-test").toFile()

    /** Cria um `cookies.sqlite` no formato do Firefox, com o login falso de cada plataforma. */
    private fun fakeFirefoxProfile(root: File, name: String, withLogin: Boolean = true): File {
        val profile = File(root, name).also { it.mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(profile, "cookies.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.execute("create table moz_cookies (host text, name text, value text, path text, expiry integer, isSecure integer, isHttpOnly integer)")
                st.execute("insert into moz_cookies values ('.exemplo.com', 'outro', 'x', '/', 0, 0, 0)")
                if (withLogin) {
                    st.execute("insert into moz_cookies values ('.youtube.com', '__Secure-3PAPISID', 'apisid-falso', '/', 2000000000, 1, 0)")
                    st.execute("insert into moz_cookies values ('.youtube.com', 'LOGIN_INFO', 'info-falso', '/', 2000000000, 1, 1)")
                    st.execute("insert into moz_cookies values ('.google.com', 'SAPISID', 'sapisid-google-falso', '/', 2000000000, 1, 0)")
                    st.execute("insert into moz_cookies values ('.twitch.tv', 'auth-token', 'token-twitch-falso', '/', 2000000000, 1, 1)")
                    st.execute("insert into moz_cookies values ('.twitch.tv', 'login', 'fulano', '/', 2000000000, 1, 0)")
                    st.execute("insert into moz_cookies values ('kick.com', 'session_token', '123%7Cabc', '/', 2000000000, 1, 1)")
                }
            }
        }
        return profile
    }

    private val firefox = InstalledBrowser("firefox", "Firefox", File("firefox.exe"), BrowserEngine.Firefox)
    private val firefoxDev = InstalledBrowser("firefox-dev", "Firefox Developer Edition", File("firefox.exe"), BrowserEngine.Firefox, "dev-edition")

    @Test
    fun le_o_login_do_perfil_do_firefox() {
        val root = tempDir()
        val profile = fakeFirefoxProfile(root, "abc123.default")
        val accounts = DesktopAccounts(tempDir())

        val cookies = FirefoxCookies.readProfile(profile)
        // Só traz cookies das plataformas do Apex, nunca de outros sites.
        assertTrue(cookies.none { it.domain.contains("exemplo") })

        val youtube = assertNotNull(accounts.accountFromCookies(Platform.YouTube, cookies))
        assertTrue("__Secure-3PAPISID=apisid-falso" in youtube.credential)
        assertTrue("SAPISID=sapisid-google-falso" in youtube.credential)
        assertTrue(accounts.youtubeCookieFile.readText().contains("LOGIN_INFO"))

        assertEquals("token-twitch-falso", accounts.accountFromCookies(Platform.Twitch, cookies)?.credential)
        assertEquals("fulano", accounts.accountFromCookies(Platform.Twitch, cookies)?.displayName)
        assertEquals("123|abc", accounts.accountFromCookies(Platform.Kick, cookies)?.credential)
    }

    @Test
    fun renova_o_login_quando_o_firefox_troca_os_cookies() {
        val root = tempDir()
        val profile = fakeFirefoxProfile(root, "abc123.default")
        val accounts = DesktopAccounts(tempDir(), firefoxProfilesRoot = root, installedBrowsers = { listOf(firefox) })
        val first = FirefoxCookies.readProfile(profile).let { accounts.accountFromCookies(Platform.YouTube, it) }
        val linked = assertNotNull(first).copy(source = "firefox:firefox", displayName = "Fulano")
        assertTrue("__Secure-3PAPISID=apisid-falso" in linked.credential)

        // O Firefox gira o cookie e grava o novo no perfil.
        DriverManager.getConnection("jdbc:sqlite:${File(profile, "cookies.sqlite").absolutePath}").use { c ->
            c.createStatement().use { it.execute("update moz_cookies set value = 'apisid-novo' where name = '__Secure-3PAPISID'") }
        }
        val renewed = assertNotNull(accounts.refreshLinked(linked))
        assertTrue("__Secure-3PAPISID=apisid-novo" in renewed.credential)
        assertTrue("apisid-falso" !in renewed.credential)
        assertEquals("Fulano", renewed.displayName)
        assertEquals("firefox:firefox", renewed.source)

        // Conta que não veio do Firefox (ou sem origem) não é renovada.
        assertNull(accounts.refreshLinked(linked.copy(source = null)))
        assertNull(accounts.refreshLinked(linked.copy(source = "outra-coisa")))
    }

    @Test
    fun sem_login_no_perfil_nao_cria_conta() {
        val profile = fakeFirefoxProfile(tempDir(), "vazio.default", withLogin = false)
        val accounts = DesktopAccounts(tempDir())
        val cookies = FirefoxCookies.readProfile(profile)
        Platform.entries.forEach { assertNull(accounts.accountFromCookies(it, cookies), it.name) }
    }

    @Test
    fun escolhe_o_perfil_certo_entre_firefox_e_developer_edition() {
        val root = tempDir()
        val normal = fakeFirefoxProfile(root, "aaa.default")
        val dev = fakeFirefoxProfile(root, "bbb.dev-edition-default")
        assertEquals(normal.name, FirefoxCookies.findProfile(firefox, root)?.name)
        assertEquals(dev.name, FirefoxCookies.findProfile(firefoxDev, root)?.name)
        assertNull(FirefoxCookies.findProfile(firefox, tempDir()))
    }

    @Test
    fun detecta_navegadores_sem_quebrar() {
        val installed = Browsers.installed()
        val default = Browsers.defaultId(installed)
        assertTrue(default == null || installed.any { it.id == default })
    }

    /** Usa o Chrome (ou Edge) de verdade, sem janela, com um perfil temporário. */
    @Test
    fun le_cookies_de_um_chromium_pelo_protocolo_de_depuracao() = runBlocking {
        val browser = Browsers.installed().firstOrNull { it.engine == BrowserEngine.Chromium } ?: return@runBlocking
        val accounts = DesktopAccounts(tempDir())
        ChromiumSession.open(browser.exe, tempDir(), "about:blank", headless = true).use { session ->
            session.setCookies(
                listOf(
                    mapOf("name" to "__Secure-3PAPISID", "value" to "apisid-chromium", "domain" to ".youtube.com", "path" to "/", "secure" to "true"),
                    mapOf("name" to "LOGIN_INFO", "value" to "info-chromium", "domain" to ".youtube.com", "path" to "/", "secure" to "true"),
                    mapOf("name" to "SAPISID", "value" to "sapisid-chromium", "domain" to ".google.com", "path" to "/", "secure" to "true"),
                    mapOf("name" to "auth-token", "value" to "token-chromium", "domain" to ".twitch.tv", "path" to "/", "secure" to "true", "httpOnly" to "true"),
                ),
            )
            val cookies = session.cookies()
            assertTrue(cookies.any { it.name == "auth-token" && it.value == "token-chromium" })

            val youtube = assertNotNull(accounts.accountFromCookies(Platform.YouTube, cookies))
            assertTrue("__Secure-3PAPISID=apisid-chromium" in youtube.credential)
            assertEquals("token-chromium", accounts.accountFromCookies(Platform.Twitch, cookies)?.credential)
            assertNull(accounts.accountFromCookies(Platform.Kick, cookies))
        }
    }

    @Test
    fun limpa_cookies_que_fazem_o_youtube_recusar_o_pedido() {
        // Como no perfil real: ~100 cookies "ST-…" de 1,5 KB, que sozinhos passam de 100 KB (o YouTube responde 413 acima de ~16 KB).
        val noise = (1..95).joinToString("; ") { "ST-abc$it=" + "x".repeat(1_500) }
        val header = "SID=sid-bom; HSID=hsid-bom; __Secure-3PAPISID=apisid-bom; LOGIN_INFO=info-bom; PREF=f6=40000000; $noise; grande=" + "y".repeat(2_000)
        assertTrue(header.length > 100_000)

        val clean = app.apex.source.CookieHygiene.cleanHeader(header)
        assertTrue(clean.length < 1_000, "ficou com ${clean.length} bytes")
        listOf("SID=sid-bom", "HSID=hsid-bom", "__Secure-3PAPISID=apisid-bom", "LOGIN_INFO=info-bom", "PREF=f6=40000000").forEach { assertTrue(it in clean, it) }
        assertTrue("ST-" !in clean && "grande=" !in clean)

        // Também no teto: mesmo sem "ST-", se passar do limite saem os maiores que não são de login.
        val many = (1..200).joinToString("; ") { "c$it=" + "z".repeat(300) } + "; SID=sid-bom"
        val capped = app.apex.source.CookieHygiene.cleanHeader(many, maxBytes = 5_000)
        assertTrue(capped.length <= 5_000 && "SID=sid-bom" in capped)

        // E o InnerTube já recebe o cabeçalho limpo (contas salvas antes da correção se curam sozinhas).
        val tube = app.apex.source.InnerTube().also { it.cookieHeader = header }
        assertTrue(tube.loggedIn || "SAPISID" !in header)
        assertTrue((tube.cookieHeader?.length ?: 0) < 1_000)
    }
}
