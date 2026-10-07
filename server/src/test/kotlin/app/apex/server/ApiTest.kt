package app.apex.server

import app.apex.shared.AuthResponse
import app.apex.shared.DeleteAccountRequest
import app.apex.shared.ErrorResponse
import app.apex.shared.ForgotRequest
import app.apex.shared.LoginRequest
import app.apex.shared.RefreshRequest
import app.apex.shared.RegisterRequest
import app.apex.shared.ResetRequest
import app.apex.shared.SyncChange
import app.apex.shared.SyncRequest
import app.apex.shared.SyncResponse
import app.apex.shared.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
    companion object {
        private val config = ServerConfig(
            port = 0, database = null, jwtSecret = "segredo-de-teste-com-mais-de-trinta-e-dois-caracteres",
            publicUrl = "http://apex.test", resendApiKey = null, mailFrom = "Apex <teste@apex.test>",
            trustProxy = false, devDataDir = Files.createTempDirectory("apex-test").toFile(),
            contactEmail = "contato@apex.test", operatorName = "Equipe de Teste", registerLimitPerHour = 10_000, mailLimitPerHour = 10_000,
        )
        val db: Database by lazy { Database.open(config).also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.close() }) } }
        val mailer = LogMailer()
    }

    private fun run(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { apexModule(config, db, mailer, syncOverlapSeconds = 0) }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client)
    }

    private fun email() = "teste-${UUID.randomUUID()}@apex.test"

    private suspend fun HttpClient.register(email: String, password: String = "senha-forte-123"): AuthResponse {
        val response = post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(email, password, "Fulano", acceptTerms = true)) }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.sync(token: String, since: String? = null, vararg changes: SyncChange): HttpResponse =
        post("/v1/sync") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(SyncRequest(since, changes.toList())) }

    private fun item(value: String): JsonObject = buildJsonObject { put("v", value) }

    private suspend fun waitForMail(to: String, subjectPart: String = ""): Mail {
        repeat(80) {
            mailer.outbox.lastOrNull { it.to == to && it.subject.contains(subjectPart) }?.let { return it }
            delay(100)
        }
        error("Nenhum e-mail chegou para $to")
    }

    private fun tokenFrom(mail: Mail) = Regex("token=([A-Za-z0-9_-]+)").find(mail.text)!!.groupValues[1]

    // ---------------------------------------------------------------------------------------

    @Test
    fun cadastro_login_e_perfil() = run { client ->
        val email = email()
        val registered = client.register(email)
        assertEquals(email, registered.user.email)
        assertFalse(registered.user.emailVerified)

        val me = client.get("/v1/me") { bearerAuth(registered.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(email, me.body<UserDto>().email)

        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email.uppercase(), "senha-forte-123")) }
        assertEquals(HttpStatusCode.OK, login.status)

        val wrong = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-errada")) }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("invalid_credentials", wrong.body<ErrorResponse>().error.code)

        val unknown = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email(), "qualquer-coisa-1")) }
        assertEquals("invalid_credentials", unknown.body<ErrorResponse>().error.code)

        val duplicate = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(email, "outra-senha-123", acceptTerms = true)) }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
    }

    @Test
    fun validacoes_de_cadastro() = run { client ->
        val weak = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(email(), "curta", acceptTerms = true)) }
        assertEquals(HttpStatusCode.BadRequest, weak.status)
        assertEquals("weak_password", weak.body<ErrorResponse>().error.code)

        val bad = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest("isso-nao-e-email", "senha-forte-123", acceptTerms = true)) }
        assertEquals("invalid_email", bad.body<ErrorResponse>().error.code)

        val garbage = client.post("/v1/auth/register") { contentType(ContentType.Application.Json); setBody("{isso nao e json") }
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
    }

    @Test
    fun rotas_protegidas_exigem_token() = run { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth("lixo.lixo.lixo") }.status)
        val sync = client.post("/v1/sync") { contentType(ContentType.Application.Json); setBody(SyncRequest()) }
        assertEquals(HttpStatusCode.Unauthorized, sync.status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun sincronizacao_ida_e_volta() = run { client ->
        val a = client.register(email())

        val first = client.sync(
            a.accessToken, null,
            SyncChange("subscriptions", "YouTube:UC1", item("canal"), modifiedAt = 1_000),
            SyncChange("history", "YouTube:abc", item("video"), modifiedAt = 1_000),
            SyncChange("watch_later", "YouTube:old", null, deleted = true, modifiedAt = 1_000),
        )
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(3, first.body<SyncResponse>().applied)

        // Outro aparelho da mesma conta entra do zero e recebe tudo.
        val fresh = client.sync(a.accessToken, null).body<SyncResponse>()
        assertEquals(3, fresh.items.size)
        val deleted = fresh.items.single { it.collection == "watch_later" }
        assertTrue(deleted.deleted)
        assertNull(deleted.data)
        assertEquals("canal", (fresh.items.single { it.collection == "subscriptions" }.data as JsonObject)["v"]?.let { (it as JsonPrimitive).content })

        // Com o cursor, não vem nada de novo...
        val again = client.sync(a.accessToken, fresh.cursor).body<SyncResponse>()
        assertTrue(again.items.isEmpty())

        // ...até aparecer uma mudança nova.
        client.sync(a.accessToken, fresh.cursor, SyncChange("settings", "app", item("tema"), modifiedAt = 2_000))
        val next = client.sync(a.accessToken, fresh.cursor).body<SyncResponse>()
        assertEquals(listOf("settings"), next.items.map { it.collection })
    }

    @Test
    fun conflito_vence_a_edicao_mais_recente() = run { client ->
        val a = client.register(email())
        client.sync(a.accessToken, null, SyncChange("playlists", "p1", item("novo"), modifiedAt = 5_000))

        val older = client.sync(a.accessToken, null, SyncChange("playlists", "p1", item("velho"), modifiedAt = 1_000)).body<SyncResponse>()
        assertEquals(0, older.applied)
        assertEquals("novo", ((older.items.single().data as JsonObject)["v"] as JsonPrimitive).content)

        val newer = client.sync(a.accessToken, null, SyncChange("playlists", "p1", item("mais novo"), modifiedAt = 9_000)).body<SyncResponse>()
        assertEquals(1, newer.applied)
        assertEquals("mais novo", ((newer.items.single().data as JsonObject)["v"] as JsonPrimitive).content)
    }

    @Test
    fun cada_conta_ve_so_os_proprios_dados() = run { client ->
        val a = client.register(email())
        val b = client.register(email())
        client.sync(a.accessToken, null, SyncChange("history", "YouTube:segredo", item("so da A"), modifiedAt = 1_000))
        assertTrue(client.sync(b.accessToken, null).body<SyncResponse>().items.isEmpty())
        // B escrever a mesma chave não mexe nos dados de A.
        client.sync(b.accessToken, null, SyncChange("history", "YouTube:segredo", item("da B"), modifiedAt = 9_000))
        val seenByA = client.sync(a.accessToken, null).body<SyncResponse>().items.single()
        assertEquals("so da A", ((seenByA.data as JsonObject)["v"] as JsonPrimitive).content)
    }

    @Test
    fun sincronizacao_recusa_dados_invalidos() = run { client ->
        val a = client.register(email())
        val badCollection = client.sync(a.accessToken, null, SyncChange("senhas", "k", item("x"), modifiedAt = 1))
        assertEquals("invalid_collection", badCollection.body<ErrorResponse>().error.code)
        val badKey = client.sync(a.accessToken, null, SyncChange("history", "", item("x"), modifiedAt = 1))
        assertEquals("invalid_key", badKey.body<ErrorResponse>().error.code)
        val badCursor = client.sync(a.accessToken, "ontem")
        assertEquals("invalid_cursor", badCursor.body<ErrorResponse>().error.code)
    }

    @Test
    fun renovar_sessao_gira_o_token_e_detecta_reuso() = run { client ->
        val session = client.register(email())

        val renewed = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(session.refreshToken)) }
        assertEquals(HttpStatusCode.OK, renewed.status)
        val second = renewed.body<AuthResponse>()
        assertNotEquals(session.refreshToken, second.refreshToken)

        // O token antigo já foi usado: reapresentá-lo é sinal de roubo e derruba todas as sessões.
        val stolen = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(session.refreshToken)) }
        assertEquals("session_revoked", stolen.body<ErrorResponse>().error.code)
        val killed = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(second.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, killed.status)
    }

    @Test
    fun sair_encerra_a_sessao() = run { client ->
        val session = client.register(email())
        val out = client.post("/v1/auth/logout") { contentType(ContentType.Application.Json); setBody(RefreshRequest(session.refreshToken)) }
        assertEquals(HttpStatusCode.NoContent, out.status)
        val again = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(session.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, again.status)
    }

    @Test
    fun esqueci_a_senha_e_redefinir() = run { client ->
        val email = email()
        val session = client.register(email, "senha-antiga-123")
        client.post("/v1/auth/forgot") { contentType(ContentType.Application.Json); setBody(ForgotRequest(email)) }
        val token = tokenFrom(waitForMail(email, "Redefinir"))

        val reset = client.post("/v1/auth/reset") { contentType(ContentType.Application.Json); setBody(ResetRequest(token, "senha-nova-456")) }
        assertEquals(HttpStatusCode.NoContent, reset.status)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-antiga-123")) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-nova-456")) }.status)

        // O link vale uma vez só e a troca de senha derruba as sessões antigas.
        val reuse = client.post("/v1/auth/reset") { contentType(ContentType.Application.Json); setBody(ResetRequest(token, "senha-outra-789")) }
        assertEquals(HttpStatusCode.BadRequest, reuse.status)
        val oldSession = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(session.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, oldSession.status)
    }

    @Test
    fun esqueci_a_senha_nao_revela_quem_tem_conta() = run { client ->
        val response = client.post("/v1/auth/forgot") { contentType(ContentType.Application.Json); setBody(ForgotRequest(email())) }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun confirmar_email() = run { client ->
        val email = email()
        client.register(email)
        val token = tokenFrom(waitForMail(email))
        val page = client.get("/verify?token=$token")
        assertEquals(HttpStatusCode.OK, page.status)
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-forte-123")) }
        assertTrue(login.body<AuthResponse>().user.emailVerified)
    }

    @Test
    fun apagar_conta_remove_tudo() = run { client ->
        val email = email()
        val session = client.register(email)
        client.sync(session.accessToken, null, SyncChange("history", "k", item("x"), modifiedAt = 1))

        val wrong = client.delete("/v1/me") { bearerAuth(session.accessToken); contentType(ContentType.Application.Json); setBody(DeleteAccountRequest("senha-errada")) }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)

        val ok = client.delete("/v1/me") { bearerAuth(session.accessToken); contentType(ContentType.Application.Json); setBody(DeleteAccountRequest("senha-forte-123")) }
        assertEquals(HttpStatusCode.NoContent, ok.status)

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-forte-123")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(RefreshRequest(session.refreshToken)) }.status)
        // O token de acesso antigo ainda é válido por minutos, mas já não tem dono.
        assertEquals(HttpStatusCode.Unauthorized, client.sync(session.accessToken, null, SyncChange("history", "k2", item("y"), modifiedAt = 1)).status)
    }

    @Test
    fun muitas_senhas_erradas_travam_o_login() = run { client ->
        val email = email()
        client.register(email)
        repeat(8) {
            client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "errada-$it-123")) }
        }
        val blocked = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-forte-123")) }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
    }

    @Test
    fun senhas_com_argon2() {
        val hash = Passwords.hash("minha senha")
        assertTrue(hash.startsWith("\$argon2id\$"))
        assertTrue(Passwords.verify("minha senha", hash))
        assertFalse(Passwords.verify("outra senha", hash))
        assertFalse(Passwords.verify("qualquer", null))
        assertNotEquals(hash, Passwords.hash("minha senha"))
    }

    @Test
    fun endereco_do_banco() {
        val neon = ServerConfig.parseDatabaseUrl("postgresql://joao:p%40ss@ep-abc.us-east-2.aws.neon.tech/neondb?sslmode=require")
        assertEquals("jdbc:postgresql://ep-abc.us-east-2.aws.neon.tech/neondb?sslmode=require", neon.jdbcUrl)
        assertEquals("joao", neon.user)
        assertEquals("p@ss", neon.password)
        // O endereço que o painel do Neon entrega hoje, com channel_binding.
        val withBinding = ServerConfig.parseDatabaseUrl("postgresql://neondb_owner:abc@ep-x.sa-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require")
        assertEquals("jdbc:postgresql://ep-x.sa-east-1.aws.neon.tech/neondb?sslmode=require", withBinding.jdbcUrl)
        val onlyBinding = ServerConfig.parseDatabaseUrl("postgresql://u:p@h/db?channel_binding=require")
        // Banco fora da própria máquina: sem sslmode na string, a conexão passa a ser criptografada por padrão.
        assertEquals("jdbc:postgresql://h/db?sslmode=require", onlyBinding.jdbcUrl)
        assertEquals("jdbc:postgresql://h/db?sslmode=require", ServerConfig.parseDatabaseUrl("postgres://u:p@h/db").jdbcUrl)
        assertEquals("jdbc:postgresql://h/db?sslmode=verify-full", ServerConfig.parseDatabaseUrl("postgres://u:p@h/db?sslmode=verify-full").jdbcUrl)
        val local = ServerConfig.parseDatabaseUrl("postgres://u:p@localhost:5432/apex")
        assertEquals("jdbc:postgresql://localhost:5432/apex", local.jdbcUrl)
        assertNotNull(local.user)
    }
}

class ConfigTest {
    @Test
    fun arquivo_env() {
        val env = ServerConfig.parseDotEnv(
            "\uFEFF# comentário\nDATABASE_URL=postgresql://u:p@h/db?sslmode=require&channel_binding=require\nJWT_SECRET=\"segredo com aspas\"\n\nexport PORT=9000\nSEM_IGUAL\n",
        )
        assertEquals("postgresql://u:p@h/db?sslmode=require&channel_binding=require", env["DATABASE_URL"])
        assertEquals("segredo com aspas", env["JWT_SECRET"])
        assertEquals("9000", env["PORT"])
        assertEquals(3, env.size)
    }

    @Test
    fun producao_exige_segredo_forte() {
        val error = runCatching { ServerConfig.fromEnv(mapOf("DATABASE_URL" to "postgres://u:p@h/db", "JWT_SECRET" to "curto")) }.exceptionOrNull()
        assertTrue(error is ConfigException)
    }

    /** O caminho de produção: um PostgreSQL "externo" acessado pela DATABASE_URL, com as tabelas criadas sozinhas. */
    @Test
    fun conecta_por_database_url_e_cria_as_tabelas() {
        val pg = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start()
        try {
            val port = pg.port
            val config = ServerConfig.fromEnv(
                mapOf(
                    "DATABASE_URL" to "postgresql://postgres@localhost:$port/postgres?sslmode=disable&channel_binding=require",
                    "JWT_SECRET" to "segredo-de-producao-com-mais-de-trinta-e-dois-caracteres",
                ),
            )
            assertTrue(config.isProduction)
            Database.open(config).use { db ->
                val tables = db.tx { c -> c.queryList("select tablename from pg_tables where schemaname = 'public'") { it.getString(1) } }
                assertTrue(tables.containsAll(listOf("users", "refresh_tokens", "email_tokens", "sync_items")), tables.toString())
            }
        } finally {
            pg.close()
        }
    }
}
