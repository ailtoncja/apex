package app.apex.server

import app.apex.shared.AuthResponse
import app.apex.shared.ErrorResponse
import app.apex.shared.ExportDto
import app.apex.shared.LoginRequest
import app.apex.shared.RegisterRequest
import app.apex.shared.SyncChange
import app.apex.shared.SyncCollections
import app.apex.shared.SyncRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** O que o servidor precisa para ficar aberto ao público: termos, limites, páginas, exportação, exclusão e limpeza. */
class PublicReleaseTest {
    private val base = ServerConfig(
        port = 0, database = null, jwtSecret = "segredo-de-teste-com-mais-de-trinta-e-dois-caracteres",
        publicUrl = "https://apex.test", resendApiKey = null, mailFrom = "Apex <teste@apex.test>",
        trustProxy = false, devDataDir = Files.createTempDirectory("apex-public").toFile(),
        contactEmail = "contato@apex.test", operatorName = "Equipe de Teste", downloadUrl = "https://apex.test/baixar",
        registerLimitPerHour = 10_000, mailLimitPerHour = 10_000,
    )

    private val db get() = ApiTest.db

    private fun run(config: ServerConfig = base, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { apexModule(config, db, LogMailer(), syncOverlapSeconds = 0) }
        block(createClient { install(ContentNegotiation) { json() } })
    }

    private fun email() = "publico-${UUID.randomUUID()}@apex.test"

    private suspend fun HttpClient.register(email: String, accept: Boolean = true): HttpResponse =
        post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, "senha-forte-123", "Fulano", acceptTerms = accept))
        }

    private suspend fun HttpClient.signUp(email: String): AuthResponse {
        val response = register(email)
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    /** Texto sem repetição: o PostgreSQL comprime o que se repete, e a cota conta o tamanho já comprimido. */
    private fun randomText(size: Int) = buildString { repeat(size) { append(('a'..'z').random()) } }

    private fun change(key: String, size: Int = 10, random: Boolean = false) = SyncChange(
        SyncCollections.WATCH_LATER, key, buildJsonObject { put("v", if (random) randomText(size) else "x".repeat(size)) }, modifiedAt = System.currentTimeMillis(),
    )

    private suspend fun HttpClient.syncOf(token: String, vararg changes: SyncChange): HttpResponse =
        post("/v1/sync") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(SyncRequest(null, changes.toList())) }

    @Test
    fun cadastro_exige_o_aceite_dos_termos() = run { client ->
        val refused = client.register(email(), accept = false)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals("terms_required", refused.body<ErrorResponse>().error.code)

        val email = email()
        client.signUp(email)
        val saved = db.tx { c -> UserRepo.findByEmail(c, email) }!!
        assertNotNull(saved.termsAcceptedAt)
        assertEquals(Legal.VERSION, saved.termsVersion)
    }

    @Test
    fun limite_de_contas_novas_por_endereco() = run(base.copy(registerLimitPerHour = 2)) { client ->
        assertEquals(HttpStatusCode.Created, client.register(email()).status)
        assertEquals(HttpStatusCode.Created, client.register(email()).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.register(email()).status)
        // Entrar não conta nesse limite.
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email(), "qualquer-coisa-1")) }
        assertEquals(HttpStatusCode.Unauthorized, login.status)
    }

    @Test
    fun limite_de_pedidos_de_senha_por_endereco() = run(base.copy(mailLimitPerHour = 1)) { client ->
        val first = client.post("/v1/auth/forgot") { contentType(ContentType.Application.Json); setBody("""{"email":"a@apex.test"}""") }
        assertEquals(HttpStatusCode.Accepted, first.status)
        val second = client.post("/v1/auth/forgot") { contentType(ContentType.Application.Json); setBody("""{"email":"a@apex.test"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    @Test
    fun teto_de_usuarios_protege_o_banco() = run(base.copy(maxUsers = 0)) { client ->
        val response = client.register(email())
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("registrations_closed", response.body<ErrorResponse>().error.code)
    }

    @Test
    fun paginas_publicas_e_cabecalhos() = run { client ->
        val home = client.get("/")
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("https://apex.test/baixar"))
        assertEquals("nosniff", home.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", home.headers["Referrer-Policy"])
        assertEquals("DENY", home.headers["X-Frame-Options"])
        assertTrue(home.headers["Strict-Transport-Security"]!!.contains("max-age"))
        assertTrue(home.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))

        val privacy = client.get("/privacy").bodyAsText()
        assertTrue(privacy.contains("Política de Privacidade"))
        assertTrue(privacy.contains("contato@apex.test"))
        assertTrue(privacy.contains("Equipe de Teste"))
        assertTrue(privacy.contains(Legal.VERSION))

        val terms = client.get("/terms").bodyAsText()
        assertTrue(terms.contains("Termos de Uso"))
        assertTrue(terms.contains("contato@apex.test"))

        assertEquals(HttpStatusCode.OK, client.get("/account/delete").status)
        assertTrue(client.get("/robots.txt").bodyAsText().contains("Disallow: /reset"))

        // Respostas da API e links de e-mail nunca ficam em cache.
        assertEquals("no-store", client.get("/v1/me").headers[HttpHeaders.CacheControl])
        assertEquals("no-store", client.get("/reset?token=abc").headers[HttpHeaders.CacheControl])
    }

    @Test
    fun informa_o_que_o_servidor_consegue_fazer() = run { client ->
        val info = client.get("/v1/info").body<app.apex.shared.InfoResponse>()
        // Nos testes o e-mail só vai para o log, então o servidor diz que NÃO envia de verdade.
        assertFalse(info.mailEnabled)
        assertEquals(Legal.VERSION, info.termsVersion)
        assertEquals("contato@apex.test", info.contactEmail)
    }

    @Test
    fun sem_contato_a_pagina_avisa() = run(base.copy(contactEmail = null, operatorName = null)) { client ->
        val privacy = client.get("/privacy").bodyAsText()
        assertTrue(privacy.contains("ainda não foi configurado"))
        assertFalse(privacy.contains("mailto:"))
    }

    @Test
    fun baixar_meus_dados() = run { client ->
        val email = email()
        val auth = client.signUp(email)
        assertEquals(HttpStatusCode.OK, client.syncOf(auth.accessToken, change("a"), change("b")).status)
        // Um item apagado não entra no arquivo.
        val gone = SyncChange(SyncCollections.WATCH_LATER, "c", null, deleted = true, modifiedAt = System.currentTimeMillis())
        assertEquals(HttpStatusCode.OK, client.syncOf(auth.accessToken, gone).status)

        val response = client.get("/v1/me/export") { bearerAuth(auth.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains("apex-meus-dados.json"))
        val export = response.body<ExportDto>()
        assertEquals(email, export.user.email)
        assertEquals(setOf("a", "b"), export.items.map { it.key }.toSet())
        assertEquals(Legal.VERSION, export.termsVersion)
        assertNotNull(export.termsAcceptedAt)
        assertEquals(1, export.activeSessions)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me/export").status)
    }

    @Test
    fun excluir_a_conta_pelo_site() = run { client ->
        val email = email()
        client.signUp(email)
        fun form(password: String, confirm: Boolean) = Parameters.build {
            append("email", email); append("password", password); if (confirm) append("confirm", "yes")
        }.formUrlEncode()

        suspend fun send(body: String) = client.post("/account/delete") { contentType(ContentType.Application.FormUrlEncoded); setBody(body) }.bodyAsText()

        assertTrue(send(form("senha-errada-1", true)).contains("E-mail ou senha incorretos"))
        assertTrue(send(form("senha-forte-123", false)).contains("Marque a caixa"))
        assertEquals(HttpStatusCode.OK, client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-forte-123")) }.status)

        assertTrue(send(form("senha-forte-123", true)).contains("Conta excluída"))
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(email, "senha-forte-123")) }
        assertEquals(HttpStatusCode.Unauthorized, login.status)
        assertEquals(0, db.tx { c -> c.queryList("select 1 from users where email = ?", email) { 1 } }.size)
    }

    @Test
    fun cota_de_dados_por_conta() = run(base.copy(maxRowsPerUser = 5, maxBytesPerUser = 4_000)) { client ->
        val auth = client.signUp(email())
        // Dentro do limite.
        assertEquals(HttpStatusCode.OK, client.syncOf(auth.accessToken, change("1"), change("2")).status)
        // Linhas demais: tudo é desfeito, nem o item que caberia fica gravado.
        val rows = client.syncOf(auth.accessToken, change("3"), change("4"), change("5"), change("6"))
        assertEquals(HttpStatusCode.PayloadTooLarge, rows.status)
        assertEquals("quota_exceeded", rows.body<ErrorResponse>().error.code)
        val kept = client.post("/v1/sync") { bearerAuth(auth.accessToken); contentType(ContentType.Application.Json); setBody(SyncRequest()) }
        assertEquals(2, kept.body<app.apex.shared.SyncResponse>().items.size)
        // Bytes demais.
        val bytes = client.syncOf(auth.accessToken, change("grande", size = 20_000, random = true))
        assertEquals("quota_exceeded", bytes.body<ErrorResponse>().error.code)
    }

    @Test
    fun pedidos_enormes_sao_recusados() = run { client ->
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"email":"${"a".repeat(100_000)}","password":"x"}""") }
        assertEquals(HttpStatusCode.PayloadTooLarge, login.status)
        val auth = client.signUp(email())
        val sync = client.post("/v1/sync") { bearerAuth(auth.accessToken); contentType(ContentType.Application.Json); setBody("x".repeat(5 * 1024 * 1024)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, sync.status)
    }

    @Test
    fun limpeza_remove_o_que_venceu() {
        val email = email()
        val userId = db.tx { c ->
            val user = UserRepo.create(c, email, "hash", "Limpeza", Legal.VERSION)
            c.execute("insert into refresh_tokens (user_id, token_hash, expires_at) values (?, 'velho-' || ?::text, now() - interval '30 days')", user.id, user.id.toString())
            c.execute("insert into refresh_tokens (user_id, token_hash, expires_at) values (?, 'novo-' || ?::text, now() + interval '30 days')", user.id, user.id.toString())
            c.execute("insert into email_tokens (token_hash, user_id, purpose, expires_at) values ('vencido-' || ?::text, ?, 'reset', now() - interval '3 days')", user.id.toString(), user.id)
            c.execute("insert into sync_items (user_id, collection, key, modified_at, deleted, deleted_at) values (?, 'watch_later', 'antigo', 1, true, now() - interval '200 days')", user.id)
            c.execute("insert into sync_items (user_id, collection, key, modified_at, deleted, deleted_at) values (?, 'watch_later', 'recente', 1, true, now() - interval '5 days')", user.id)
            c.execute("insert into sync_items (user_id, collection, key, data, modified_at) values (?, 'watch_later', 'vivo', '{}'::jsonb, 1)", user.id)
            user.id
        }
        assertTrue(Maintenance.run(db) >= 3)
        db.tx { c ->
            assertEquals(1, c.queryList("select 1 from refresh_tokens where user_id = ?", userId) { 1 }.size)
            assertEquals(0, c.queryList("select 1 from email_tokens where user_id = ?", userId) { 1 }.size)
            val keys = c.queryList("select key from sync_items where user_id = ? order by key", userId) { it.getString("key") }
            assertEquals(listOf("recente", "vivo"), keys)
        }
    }
}
