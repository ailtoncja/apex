package app.apex.cloud

import app.apex.data.KeyValueStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.server.Database
import app.apex.server.LogMailer
import app.apex.server.ServerConfig
import app.apex.server.startServer
import app.apex.source.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Teste de ponta a ponta: servidor de verdade (PostgreSQL embutido) e dois "aparelhos" sincronizando. */
class SyncE2ETest {
    private class MemoryStore : KeyValueStore {
        private val map = ConcurrentHashMap<String, String>()
        override fun read(name: String): String? = map[name]
        override fun write(name: String, text: String) {
            map[name] = text
        }
    }

    private class Device(serverUrl: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(MemoryStore(), scope).also { d -> d.updateSettings { it.copy(serverUrl = serverUrl) } }
        val account = CloudAccount(scope, data, Http.client)

        suspend fun sync() = account.engine.syncNow()
    }

    companion object {
        private val config = ServerConfig(
            port = 0, database = null, jwtSecret = "segredo-do-teste-de-ponta-a-ponta-123456", publicUrl = "http://apex.test",
            resendApiKey = null, mailFrom = "Apex <t@apex.test>", trustProxy = false,
            devDataDir = Files.createTempDirectory("apex-e2e").toFile(), registerLimitPerHour = 10_000, mailLimitPerHour = 10_000,
        )
        private val db = Database.open(config)
        private val server = startServer(config, db, LogMailer(), "127.0.0.1", 0, syncOverlapSeconds = 0).also { it.start(wait = false) }
        val url: String = runBlocking { "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}" }

        init {
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.stop(100, 500) }; db.close() })
        }
    }

    private val password = "senha-de-teste-1234"
    private fun email() = "e2e-${UUID.randomUUID()}@apex.test"
    private fun video(id: String) = Media(Platform.YouTube, id, "Vídeo $id", url = "https://www.youtube.com/watch?v=$id")
    private fun channel(id: String, platform: Platform = Platform.YouTube) = Channel(platform, id, "Canal $id")

    @Test
    fun dois_aparelhos_ficam_iguais() = runBlocking {
        val a = Device(url)
        val b = Device(url)
        val email = email()

        a.data.toggleSubscription(channel("UC1"))
        a.data.toggleSubscription(channel("gaules", Platform.Twitch))
        a.data.toggleWatchLater(video("v1"))
        a.data.createPlaylist("Minha lista", video("v2"))
        a.data.setReaction(video("v3"), 1)
        a.data.setReaction(video("v9"), -1)
        a.data.recordHistory(video("v4"), 12_000, 100_000)
        a.data.updateSettings { it.copy(defaultQuality = 720, hideMature = false) }

        a.account.signUp(email, password, "Apex E2E", true)
        a.sync()

        b.account.signIn(email, password)
        b.sync()

        assertEquals(setOf("YouTube:UC1", "Twitch:gaules"), b.data.subscriptions.value.map { it.key }.toSet())
        assertEquals(listOf("YouTube:v1"), b.data.watchLater.value.map { it.key })
        assertEquals(listOf("Minha lista"), b.data.playlists.value.map { it.name })
        assertEquals(listOf("v2"), b.data.playlists.value.single().items.map { it.id })
        assertEquals(listOf("YouTube:v3"), b.data.liked.value.map { it.key })
        assertEquals(-1, b.data.reactions.value["YouTube:v9"])
        assertEquals(12_000L, b.data.history.value.single().positionMs)
        assertEquals(720, b.data.settings.value.defaultQuality)
        assertEquals(false, b.data.settings.value.hideMature)

        // Mudanças no B (incluindo apagar) chegam ao A.
        b.data.toggleSubscription(channel("gaules", Platform.Twitch))
        b.data.toggleSubscription(channel("UC2"))
        b.data.deletePlaylist(b.data.playlists.value.single().id)
        b.sync()
        a.sync()
        assertEquals(setOf("YouTube:UC1", "YouTube:UC2"), a.data.subscriptions.value.map { it.key }.toSet())
        assertTrue(a.data.playlists.value.isEmpty())
    }

    @Test
    fun vale_a_edicao_mais_recente() = runBlocking {
        val a = Device(url)
        val b = Device(url)
        val email = email()
        a.account.signUp(email, password, null, true)
        a.sync()
        b.account.signIn(email, password)
        b.sync()

        a.data.updateSettings { it.copy(defaultQuality = 480) }
        a.account.engine.detectNow()
        delay(30)
        b.data.updateSettings { it.copy(defaultQuality = 1080) }
        b.account.engine.detectNow()
        // O B mexeu depois; mesmo que o A sincronize por último, a edição do B continua valendo.
        b.sync()
        a.sync()
        b.sync()
        assertEquals(1080, a.data.settings.value.defaultQuality)
        assertEquals(1080, b.data.settings.value.defaultQuality)
    }

    @Test
    fun sessao_vencida_e_renovada_sozinha() = runBlocking {
        val a = Device(url)
        a.account.signUp(email(), password, null, true)
        a.sync()
        val session = assertNotNull(a.data.cloudSession.value)
        a.data.setCloudSession(session.copy(accessToken = "token.invalido.aqui"))

        a.data.toggleSubscription(channel("UC77"))
        a.sync()

        assertIs<SyncStatus.Idle>(a.account.status.value)
        val renewed = assertNotNull(a.data.cloudSession.value)
        assertTrue(renewed.refreshToken != session.refreshToken)

        val b = Device(url)
        b.account.signIn(session.user.email, password)
        b.sync()
        assertEquals(listOf("YouTube:UC77"), b.data.subscriptions.value.map { it.key })
    }

    @Test
    fun sem_internet_guarda_e_envia_depois() = runBlocking {
        val a = Device(url)
        val email = email()
        a.account.signUp(email, password, null, true)
        a.sync()

        a.data.updateSettings { it.copy(serverUrl = "http://127.0.0.1:9") }
        a.data.toggleWatchLater(video("offline1"))
        // A sessão guarda o endereço do servidor, então o app continua falando com o servidor certo; derrubamos a sessão para simular.
        val real = assertNotNull(a.data.cloudSession.value)
        a.data.setCloudSession(real.copy(serverUrl = "http://127.0.0.1:9"))
        a.sync()
        assertIs<SyncStatus.Error>(a.account.status.value)

        a.data.setCloudSession(real)
        a.data.updateSettings { it.copy(serverUrl = url) }
        a.sync()
        assertIs<SyncStatus.Idle>(a.account.status.value)

        val b = Device(url)
        b.account.signIn(email, password)
        b.sync()
        assertEquals(listOf("YouTube:offline1"), b.data.watchLater.value.map { it.key })
    }

    @Test
    fun aparelho_com_dados_de_outra_conta_pergunta_antes() = runBlocking {
        val device = Device(url)
        val first = email()
        val second = email()

        device.data.toggleSubscription(channel("UCprimeira"))
        device.account.signUp(first, password, null, true)
        device.sync()

        val other = Device(url)
        other.account.signUp(second, password, null, true)
        other.sync()

        // Sai da primeira conta mantendo os dados e entra na segunda: o app precisa perguntar.
        device.account.signOut(wipeLocal = false)
        assertNull(device.data.cloudSession.value)
        device.account.signIn(second, password)
        assertNotNull(device.account.foreignData.value)

        device.account.resolveForeign(merge = false)
        device.sync()
        assertTrue(device.data.subscriptions.value.isEmpty())
        assertNull(device.account.foreignData.value)
    }

    @Test
    fun sair_apagando_limpa_o_aparelho() = runBlocking {
        val a = Device(url)
        a.data.toggleSubscription(channel("UCx"))
        a.account.signUp(email(), password, null, true)
        a.sync()
        a.account.signOut(wipeLocal = true)
        assertTrue(a.data.subscriptions.value.isEmpty())
        assertNull(a.data.cloudSession.value)
    }

    @Test
    fun senha_errada_nao_entra() = runBlocking {
        val a = Device(url)
        val email = email()
        a.account.signUp(email, password, null, true)
        val other = Device(url)
        val error = runCatching { other.account.signIn(email, "senha-errada-999") }.exceptionOrNull()
        assertIs<CloudException>(error)
        assertEquals("invalid_credentials", error.code)
        assertNull(other.data.cloudSession.value)
    }

    @Test
    fun cadastro_sem_aceitar_os_termos_e_baixar_os_dados() = runBlocking {
        val a = Device(url)
        val email = email()
        val refused = runCatching { a.account.signUp(email, password, null, false) }.exceptionOrNull()
        assertIs<CloudException>(refused)
        assertEquals("terms_required", refused.code)
        assertNull(a.data.cloudSession.value)

        a.data.toggleSubscription(channel("UCexport"))
        a.account.signUp(email, password, "Quem Exporta", true)
        a.sync()
        val export = a.account.exportData()
        assertTrue(export.contains(email), export)
        assertTrue(export.contains("UCexport"), export)
        assertTrue(export.contains("termsVersion"), export)
        assertTrue(a.account.pageUrl("/privacy").endsWith("/privacy"))

        // O servidor de teste só escreve e-mails no log: o app não pode prometer que enviou.
        val forgot = a.account.forgot(email)
        assertTrue(forgot.contains("não envia e-mails"), forgot)
    }
}
