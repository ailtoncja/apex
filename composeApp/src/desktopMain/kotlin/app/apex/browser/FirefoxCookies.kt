package app.apex.browser

import app.apex.Cookie
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/** Lê os cookies do perfil do Firefox (ficam num SQLite sem cifra), sem precisar fechar o navegador. */
object FirefoxCookies {
    val DEFAULT_HOSTS = listOf("youtube.com", "google.com", "twitch.tv", "kick.com")

    /** Pasta do perfil que o Firefox usou por último, entre as pastas em [profilesRoot]. */
    fun findProfile(browser: InstalledBrowser, profilesRoot: File = defaultRoot()): File? {
        val all = profilesRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").isFile }.orEmpty().toList()
        val hint = browser.profileHint
        val matching = if (hint != null) all.filter { hint in it.name } else all.filterNot { "dev-edition" in it.name }
        return (matching.ifEmpty { all }).maxByOrNull { File(it, "cookies.sqlite").lastModified() }
    }

    fun defaultRoot(): File = File(System.getenv("APPDATA").orEmpty(), "Mozilla\\Firefox\\Profiles")

    fun read(browser: InstalledBrowser, profilesRoot: File = defaultRoot()): List<Cookie> {
        val profile = findProfile(browser, profilesRoot) ?: return emptyList()
        return readProfile(profile)
    }

    fun readProfile(profile: File, hosts: List<String> = DEFAULT_HOSTS): List<Cookie> {
        val tmp = Files.createTempDirectory("apex-ff").toFile()
        try {
            // Trabalha numa cópia: o Firefox mantém o banco aberto e as gravações recentes ficam no arquivo -wal.
            val db = File(profile, "cookies.sqlite")
            Files.copy(db.toPath(), File(tmp, "cookies.sqlite").toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(profile, "cookies.sqlite-wal").takeIf { it.isFile }
                ?.let { Files.copy(it.toPath(), File(tmp, "cookies.sqlite-wal").toPath(), StandardCopyOption.REPLACE_EXISTING) }

            val where = hosts.joinToString(" or ") { "host like '%$it'" }
            val out = mutableListOf<Cookie>()
            DriverManager.getConnection("jdbc:sqlite:${File(tmp, "cookies.sqlite").absolutePath}").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("select host, name, value, path, expiry, isSecure, isHttpOnly from moz_cookies where $where").use { rs ->
                        while (rs.next()) {
                            val host = rs.getString("host")
                            val sub = if (host.startsWith(".")) "TRUE" else "FALSE"
                            val prefix = if (rs.getInt("isHttpOnly") != 0) "#HttpOnly_" else ""
                            val line = "$prefix$host\t$sub\t${rs.getString("path")}\t${if (rs.getInt("isSecure") != 0) "TRUE" else "FALSE"}\t${rs.getLong("expiry")}\t${rs.getString("name")}\t${rs.getString("value")}"
                            out += Cookie(host, rs.getString("name"), rs.getString("value"), line)
                        }
                    }
                }
            }
            return out
        } finally {
            tmp.deleteRecursively()
        }
    }
}
