package app.apex

import app.apex.chat.TwitchChat
import app.apex.cloud.CloudAccount
import app.apex.cloud.CloudException
import app.apex.data.FileStore
import app.apex.data.KeyValueStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.VOD_PREFIX
import app.apex.shared.SyncCollections
import app.apex.source.AppJson
import app.apex.source.Binaries
import app.apex.source.Checksums
import app.apex.source.CookieHygiene
import app.apex.source.CookieJar
import app.apex.source.ExtractionException
import app.apex.source.Http
import app.apex.source.KickSource
import app.apex.source.YtDlpExtractor
import app.apex.source.isYouTubeChannelId
import app.apex.source.stripLineBreaks
import app.apex.source.twitchChatLogin
import app.apex.source.ytDlpUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun comentarios_e_canal_tambem_nunca_viram_opcao_do_yt_dlp() = runBlocking {
        val extractor = YtDlpExtractor(Binaries(tempDir()))
        for (url in listOf("--exec calc.exe", "-J", "https://exemplo.com/v", "https://www.youtube.com@invasor.com/v", "https://www.youtube.com/v x")) {
            val error = assertFailsWith<ExtractionException>(url) { extractor.comments(Media(Platform.YouTube, "x", "titulo", url = url), 10, false) }
            assertEquals("Endereço de vídeo inválido.", error.message, url)
        }
        for (id in listOf("--exec", "UC123", "UCabcdefghijklmnopqrstu/../x", "UCabcdefghijklmnopqrst v")) {
            val error = assertFailsWith<ExtractionException>(id) { extractor.channelDetails(id) }
            assertEquals("Canal inválido.", error.message, id)
        }
        // O validador único: só https do YouTube, sem espaço nem controle.
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", ytDlpUrl(" https://www.youtube.com/watch?v=abcdefghijk "))
        assertEquals("https://youtu.be/abcdefghijk", ytDlpUrl("https://youtu.be/abcdefghijk"))
        listOf("-https://www.youtube.com/x", "http://www.youtube.com/x", "https://youtube.com.invasor.com/x", "https://www.youtube.com:8080/x", "https://a\tb")
            .forEach { assertNull(ytDlpUrl(it), it) }
        assertTrue(isYouTubeChannelId("UC" + "a".repeat(22)))
    }

    // ---------------------------------------------------------------- dados sincronizados

    @Test
    fun dados_que_chegam_da_nuvem_ou_do_disco_sao_conferidos() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(MemoryStore(), scope)
        fun media(id: String, url: String, thumb: String? = null, avatar: String? = null, platform: Platform = Platform.YouTube) =
            Media(platform, id, "t", channel = Channel(platform, "UC" + "a".repeat(22), "Canal", avatarUrl = avatar), thumbnailUrl = thumb, url = url)
        fun push(m: Media) = data.applyRemote(SyncCollections.WATCH_LATER, m.key, AppJson.encodeToJsonElement(Media.serializer(), m), false)

        push(media("ruim1", "--exec calc.exe"))
        push(media("ruim2", "https://invasor.com/watch?v=x"))
        push(media("ruim3", "http://www.youtube.com/watch?v=x"))
        push(media("-J", "https://www.youtube.com/watch?v=x"))
        push(media("a b", "https://www.youtube.com/watch?v=x"))
        push(media("twitch", "https://www.youtube.com/watch?v=x", platform = Platform.Twitch))
        assertTrue(data.watchLater.value.isEmpty(), "nada malicioso entra: ${data.watchLater.value}")

        push(media("bom", "https://www.youtube.com/watch?v=bom", thumb = "file:///C:/x.png", avatar = "javascript:alert(1)"))
        push(media("live", "https://kick.com/fulano", thumb = "https://images.kick.com/t.webp", platform = Platform.Kick))
        val ok = data.watchLater.value.associateBy { it.id }
        assertEquals(setOf("bom", "live"), ok.keys)
        assertNull(ok.getValue("bom").thumbnailUrl, "imagem que não é https some, o item fica")
        assertNull(ok.getValue("bom").channel?.avatarUrl)
        assertEquals("https://images.kick.com/t.webp", ok.getValue("live").thumbnailUrl)

        // Inscrição com id que vira opção é descartada.
        val bad = Channel(Platform.Twitch, "-x", "X")
        data.applyRemote(SyncCollections.SUBSCRIPTIONS, bad.key, AppJson.encodeToJsonElement(Channel.serializer(), bad), false)
        assertTrue(data.subscriptions.value.isEmpty())

        // Curtida com mídia maliciosa: o voto fica, a mídia não entra na lista.
        val evil = media("ev", "--exec calc")
        data.applyRemote(SyncCollections.REACTIONS, evil.key, buildJsonObject { put("value", 1); put("media", AppJson.encodeToJsonElement(Media.serializer(), evil)) }, false)
        assertTrue(data.liked.value.isEmpty())

        // O que já estava no disco passa pela mesma conferência ao abrir.
        val store = MemoryStore()
        store.write("watchlater", AppJson.encodeToString(ListSerializer(Media.serializer()), listOf(media("ruim", "--exec calc"), media("bom", "https://www.youtube.com/watch?v=bom"))))
        assertEquals(listOf("bom"), UserData(store, scope).watchLater.value.map { it.id })
    }

    // ---------------------------------------------------------------- Kick

    @Test
    fun token_da_kick_so_vai_para_a_kick() = runBlocking {
        val seen = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            seen += request
            when (request.url.host) {
                "kick.com" -> respond("""{"uuid":"u","source":"https://stream.kick.cloudfront.net/master.m3u8"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("#EXTM3U\n", HttpStatusCode.OK)
            }
        })
        val kick = KickSource(client).also { it.sessionToken = "123|segredo" }
        for (url in listOf("https://kick.com/api/v1/user", "https://www.kick.com/x", "https://web.kick.com/api/v1/livestreams")) {
            assertEquals("Bearer 123|segredo", kick.headersFor(url)[HttpHeaders.Authorization], url)
        }
        for (url in listOf("https://stream.kick.cloudfront.net/x.m3u8", "https://kick.com.invasor.com/x", "https://kick.com@invasor.com/x", "http://kick.com/x", "https://fa723fc1b171.us-west-2.playback.live-video.net/x")) {
            assertNull(kick.headersFor(url)[HttpHeaders.Authorization], url)
        }
        // De ponta a ponta: o VOD vem da API (com o token) e a lista de vídeo da CDN (sem ele).
        kick.resolve(Media(Platform.Kick, VOD_PREFIX + "abc", "VOD", url = "https://kick.com/canal/videos/abc"))
        val api = seen.single { it.url.host == "kick.com" }
        val cdn = seen.single { it.url.host == "stream.kick.cloudfront.net" }
        assertEquals("Bearer 123|segredo", api.headers[HttpHeaders.Authorization])
        assertNull(cdn.headers[HttpHeaders.Authorization], "a CDN nunca recebe o token")
    }

    // ---------------------------------------------------------------- chat da Twitch

    @Test
    fun login_da_twitch_nao_esconde_comandos_irc() = runBlocking {
        assertEquals("gaules", twitchChatLogin("Gaules"))
        assertEquals("canal_1", twitchChatLogin("canal_1"))
        listOf("canal\r\nJOIN #outro", "canal :x", "ab", "a".repeat(26), "#canal", "can-al", "", "canal\u0000").forEach { assertNull(twitchChatLogin(it), it) }
        assertEquals("oi  PRIVMSG #outro :spam", stripLineBreaks("oi\r\nPRIVMSG #outro :spam"))

        // Com um login inválido, o chat nem conecta (e não fica tentando de novo).
        val chat = TwitchChat(
            CoroutineScope(SupervisorJob() + Dispatchers.Default), "canal\r\nPRIVMSG #outro :spam", HttpClient(MockEngine { respond("") }),
            token = { "tok" }, accountLogin = { "eu" },
        )
        chat.start()
        withTimeout(3_000) { while (chat.status.value != "Canal inválido para o chat da Twitch") delay(10) }
        assertFalse(chat.send("oi"))
        chat.stop()
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
