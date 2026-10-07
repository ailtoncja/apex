package app.apex

import app.apex.cloud.CloudAccount
import app.apex.cloud.CloudException
import app.apex.data.FileStore
import app.apex.data.KeyValueStore
import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.Binaries
import app.apex.source.Checksums
import app.apex.source.CookieHygiene
import app.apex.source.CookieJar
import app.apex.source.ExtractionException
import app.apex.source.Http
import app.apex.source.YtDlpExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verificações de segurança do app no Windows. */
class SecurityClientTest {
    private fun tempDir() = Files.createTempDirectory("apex-sec-client").toFile()

    // ---------------------------------------------------------------- links

    @Test
    fun so_abre_links_http_e_https() {
        listOf("https://www.youtube.com/watch?v=abc", "http://exemplo.com/x", " https://kick.com/fulano ").forEach { assertTrue(isSafeWebUrl(it), it) }
        listOf(
            "file:///C:/Windows/System32/calc.exe", "javascript:alert(1)", "ms-msdt:/id PCWDiagnostic", "search-ms:query=x", "ftp://exemplo.com",
            "calc.exe", "\\\\servidor\\compartilhamento\\x.exe", "https://", "", "data:text/html,<script>1</script>", "vbscript:x",
        ).forEach { assertFalse(isSafeWebUrl(it), it) }
    }

    // ---------------------------------------------------------------- yt-dlp

    @Test
    fun endereco_de_video_nunca_vira_opcao_do_yt_dlp() = runBlocking {
        val extractor = YtDlpExtractor(Binaries(tempDir()))
        // Dados sincronizados são "de fora": um endereço começando com "-" viraria uma opção do yt-dlp (como --exec, que roda comandos).
        listOf("--exec calc.exe", "-J", "--exec=calc", "file:///C:/x", "http://exemplo.com/v", "https://exemplo.com/v --exec calc", "", "https://a\r\nb").forEach { url ->
            val error = assertFailsWith<ExtractionException>(url) { extractor.resolve(Media(Platform.YouTube, "x", "titulo", url = url)) }
            assertEquals("Endereço de vídeo inválido.", error.message, url)
        }
    }

    @Test
    fun cookies_do_yt_dlp_so_existem_durante_a_execucao() {
        val dir = tempDir()
        val noise = (1..50).joinToString("; ") { "ST-x$it=" + "y".repeat(1500) }
        val file = assertNotNull(CookieJar.write("SID=sid; __Secure-3PAPISID=api; LOGIN_INFO=li; $noise; ruim=a\tb; outro=c\nd", dir))
        val text = file.readText()
        assertTrue(text.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(".youtube.com\tTRUE\t/\tTRUE" in text && "\tSID\tsid" in text && "\t__Secure-3PAPISID\tapi" in text)
        assertFalse("ST-x" in text, "cookies inúteis não vão para o arquivo")
        assertFalse("ruim" in text || "outro" in text, "cookie com tabulação ou quebra de linha é descartado")
        assertTrue(text.length < 1_000)
        CookieJar.discard(file)
        assertFalse(file.exists())

        // Sobras de uma execução que travou são limpas.
        val stale = Files.createTempFile(dir.toPath(), "apex-ck", ".txt").toFile().also { it.writeText("sobra") }
        val other = File(dir, "outro-arquivo.txt").also { it.writeText("não mexer") }
        CookieJar.deleteStale(dir)
        assertFalse(stale.exists())
        assertTrue(other.exists())
        assertEquals(null, CookieJar.write("", dir))
    }

    // ---------------------------------------------------------------- downloads

    @Test
    fun confere_hash_dos_programas_baixados() {
        val file = Files.createTempFile("apex-hash", ".bin").toFile().also { it.writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.sha256(file))

        val sums = """
            0000000000000000000000000000000000000000000000000000000000000001  yt-dlp
            66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a  yt-dlp.exe
            00000000000000000000000000000000000000000000000000000000000000ff *yt-dlp_x86.exe
        """.trimIndent()
        assertEquals("66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a", Checksums.fromSumsFile(sums, "yt-dlp.exe"))
        assertEquals("00000000000000000000000000000000000000000000000000000000000000ff", Checksums.fromSumsFile(sums, "yt-dlp_x86.exe"))
        assertEquals(null, Checksums.fromSumsFile(sums, "outro.exe"))
        // O Deno publica o hash dentro de um texto do PowerShell.
        val deno = "\nAlgorithm : SHA256\nHash      : A0C3101B4158D1DFB7D6A78A7BF0F3DE80C96BB423C152BEEC8BEB22786F2238\nPath      : x\n"
        assertEquals("A0C3101B4158D1DFB7D6A78A7BF0F3DE80C96BB423C152BEEC8BEB22786F2238", Checksums.firstHash(deno))
        assertEquals(null, Checksums.firstHash("sem hash aqui"))
    }

    // ---------------------------------------------------------------- arquivos de login

    @Test
    fun logins_vao_cifrados_para_o_disco() {
        val dir = tempDir()
        val store = FileStore(dir)
        store.write("accounts", """{"YouTube":{"credential":"SEGREDO-DA-SESSAO"}}""")
        store.write("cloud", """{"refreshToken":"TOKEN-SECRETO"}""")
        for (name in listOf("accounts", "cloud")) {
            val raw = File(dir, "$name.json").readText()
            assertTrue(raw.startsWith("dpapi:"), "$name deveria estar cifrado")
            assertFalse("SEGREDO" in raw || "TOKEN-SECRETO" in raw, "$name tem texto puro")
        }
        assertEquals("""{"YouTube":{"credential":"SEGREDO-DA-SESSAO"}}""", store.read("accounts"))
        // Dados que não são segredo continuam legíveis.
        store.write("settings", """{"volume":50}""")
        assertEquals("""{"volume":50}""", File(dir, "settings.json").readText())
    }

    // ---------------------------------------------------------------- servidor do Apex

    private class MemoryStore : KeyValueStore {
        private val map = ConcurrentHashMap<String, String>()
        override fun read(name: String): String? = map[name]
        override fun write(name: String, text: String) {
            map[name] = text
        }
    }

    @Test
    fun senha_so_viaja_por_https() = runBlocking {
        fun account(url: String): CloudAccount {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val data = UserData(MemoryStore(), scope).also { d -> d.updateSettings { it.copy(serverUrl = url) } }
            return CloudAccount(scope, data, Http.client)
        }
        for (url in listOf("http://exemplo.com", "http://192.168.0.10:8080", "ftp://exemplo.com", "exemplo.com", "http://localhost.exemplo.com", "http://localhost@invasor.com", "http://127.0.0.1@invasor.com:8080/x")) {
            val error = assertFailsWith<CloudException>(url) { account(url).signIn("a@b.com", "senha-qualquer-1") }
            assertEquals("insecure_server", error.code, url)
        }
        // No próprio computador, http é permitido (aí falha por não ter servidor, não por segurança).
        for (url in listOf("http://localhost:1", "http://127.0.0.1:1", "http://[::1]:1")) {
            val error = assertFailsWith<CloudException>(url) { account(url).signIn("a@b.com", "senha-qualquer-1") }
            assertEquals("offline", error.code, url)
        }
    }

    @Test
    fun limpeza_de_cookies_mantem_so_o_que_autentica() {
        val clean = CookieHygiene.cleanHeader("SID=a; ST-1=" + "x".repeat(900) + "; PREF=ok; grande=" + "y".repeat(900))
        assertEquals("SID=a; PREF=ok", clean)
    }
}
