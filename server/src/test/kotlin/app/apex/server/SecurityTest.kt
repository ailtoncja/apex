package app.apex.server

import app.apex.shared.ErrorResponse
import app.apex.shared.ForgotRequest
import app.apex.shared.LoginRequest
import app.apex.shared.RegisterRequest
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Verificações de segurança do servidor: IP falsificado, trancar a conta dos outros, tokens adulterados, e-mails maliciosos, tempo de resposta. */
class SecurityTest {
    private val secret = "segredo-de-teste-com-mais-de-trinta-e-dois-caracteres"

    private val base = ServerConfig(
        port = 0, database = null, jwtSecret = secret, publicUrl = "https://apex.test", resendApiKey = null,
        mailFrom = "Apex <t@apex.test>", trustProxy = false, devDataDir = Files.createTempDirectory("apex-sec").toFile(),
        registerLimitPerHour = 10_000, mailLimitPerHour = 10_000,
    )

    private val db get() = ApiTest.db

    private fun run(config: ServerConfig = base, mailer: Mailer = LogMailer(), block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { apexModule(config, db, mailer, syncOverlapSeconds = 0) }
        block(createClient { install(ContentNegotiation) { json() } })
    }

    private fun email() = "seg-${UUID.randomUUID()}@apex.test"

    private suspend fun HttpClient.register(email: String, ip: String? = null): HttpResponse = post("/v1/auth/register") {
        ip?.let { header("X-Forwarded-For", it) }
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(email, "senha-forte-123", "Fulano", acceptTerms = true))
    }

    private suspend fun HttpClient.login(email: String, password: String, ip: String? = null): HttpResponse = post("/v1/auth/login") {
        ip?.let { header("X-Forwarded-For", it) }
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(email, password))
    }

    private suspend fun HttpClient.forgot(email: String, xff: String? = null): HttpResponse = post("/v1/auth/forgot") {
        xff?.let { header("X-Forwarded-For", it) }
        contentType(ContentType.Application.Json)
        setBody(ForgotRequest(email))
    }

    // ---------------------------------------------------------------- IP falsificado

    @Test
    fun cabecalho_de_ip_falsificado_nao_burla_o_limite() = run(base.copy(trustProxy = true, trustedProxyHops = 1, mailLimitPerHour = 2)) { client ->
        // O cliente inventa o começo da lista; o nosso proxy acrescenta o IP real no fim (203.0.113.7).
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "1.1.1.1, 203.0.113.7").status)
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "2.2.2.2, 203.0.113.7").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.forgot("a@apex.test", "3.3.3.3, 203.0.113.7").status)
        // Outra pessoa (outro IP real) não é afetada.
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "9.9.9.9, 198.51.100.4").status)
    }

    @Test
    fun na_render_valem_tres_entradas_e_o_cabecalho_do_cdn() = run(base.copy(trustProxy = true, trustedProxyHops = 3, mailLimitPerHour = 2)) { client ->
        // Como na Render: cliente, Cloudflare e rede interna. O cliente ainda tenta inventar entradas no começo.
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "1.1.1.1, 2.2.2.2, 203.0.113.7, 172.71.28.191, 10.24.0.151").status)
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "3.3.3.3, 203.0.113.7, 172.71.28.191, 10.24.0.151").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.forgot("a@apex.test", "203.0.113.7, 172.71.28.191, 10.24.0.151").status)
    }

    @Test
    fun cabecalho_do_cdn_tem_prioridade_e_sem_ele_cai_no_x_forwarded_for() =
        run(base.copy(trustProxy = true, trustedProxyHops = 3, clientIpHeader = "CF-Connecting-IP", mailLimitPerHour = 2)) { client ->
            suspend fun call(ip: String?, xff: String) = client.post("/v1/auth/forgot") {
                ip?.let { header("CF-Connecting-IP", it) }
                header("X-Forwarded-For", xff)
                contentType(ContentType.Application.Json)
                setBody(ForgotRequest("a@apex.test"))
            }.status
            // O X-Forwarded-For muda a cada pedido, mas o IP do CDN é o mesmo: conta como a mesma pessoa.
            assertEquals(HttpStatusCode.Accepted, call("198.51.100.20", "1.1.1.1, 9.9.9.9, 8.8.8.8"))
            assertEquals(HttpStatusCode.Accepted, call("198.51.100.20", "2.2.2.2, 9.9.9.9, 8.8.8.8"))
            assertEquals(HttpStatusCode.TooManyRequests, call("198.51.100.20", "3.3.3.3, 9.9.9.9, 8.8.8.8"))
            // Sem o cabeçalho do CDN, vale a terceira entrada a partir do fim.
            assertEquals(HttpStatusCode.Accepted, call(null, "7.7.7.7, 192.0.2.5, 9.9.9.9, 8.8.8.8"))
            assertEquals(HttpStatusCode.Accepted, call(null, "6.6.6.6, 192.0.2.5, 9.9.9.9, 8.8.8.8"))
            assertEquals(HttpStatusCode.TooManyRequests, call(null, "5.5.5.5, 192.0.2.5, 9.9.9.9, 8.8.8.8"))
        }

    @Test
    fun configuracao_do_ip_do_cliente() {
        val c = ServerConfig.fromEnv(mapOf("TRUST_PROXY" to "true", "TRUSTED_PROXY_HOPS" to "3", "CLIENT_IP_HEADER" to " CF-Connecting-IP "))
        assertEquals(3, c.trustedProxyHops)
        assertEquals("CF-Connecting-IP", c.clientIpHeader)
        // Valor estranho é ignorado; o número de proxies fica entre 1 e 5.
        val bad = ServerConfig.fromEnv(mapOf("TRUSTED_PROXY_HOPS" to "99", "CLIENT_IP_HEADER" to "X Y\r\nZ"))
        assertEquals(5, bad.trustedProxyHops)
        assertEquals(null, bad.clientIpHeader)
    }

    @Test
    fun sem_proxy_configurado_o_cabecalho_e_ignorado() = run(base.copy(trustProxy = false, mailLimitPerHour = 2)) { client ->
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "1.1.1.1").status)
        assertEquals(HttpStatusCode.Accepted, client.forgot("a@apex.test", "2.2.2.2").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.forgot("a@apex.test", "3.3.3.3").status)
    }

    // ---------------------------------------------------------------- trancar a conta dos outros

    @Test
    fun atacante_sozinho_nao_tranca_a_conta_da_vitima() = run(base.copy(trustProxy = true, trustedProxyHops = 1)) { client ->
        val victim = email()
        assertEquals(HttpStatusCode.Created, client.register(victim, "198.51.100.1").status)
        // O atacante erra a senha da vítima várias vezes, sempre do mesmo IP.
        repeat(6) { assertEquals(HttpStatusCode.Unauthorized, client.login(victim, "errada-$it-123", "203.0.113.50").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login(victim, "errada-final-123", "203.0.113.50").status)
        // A vítima, de outro IP, continua entrando.
        assertEquals(HttpStatusCode.OK, client.login(victim, "senha-forte-123", "198.51.100.1").status)
    }

    @Test
    fun ataque_espalhado_por_muitos_ips_tambem_e_contido() = run(base.copy(trustProxy = true, trustedProxyHops = 1)) { client ->
        val victim = email()
        assertEquals(HttpStatusCode.Created, client.register(victim, "198.51.100.1").status)
        repeat(25) { assertEquals(HttpStatusCode.Unauthorized, client.login(victim, "errada-$it-123", "203.0.113.${it + 1}").status) }
        // Passou do teto por e-mail: nem a senha certa de um IP novo entra até a janela passar.
        assertEquals(HttpStatusCode.TooManyRequests, client.login(victim, "senha-forte-123", "192.0.2.77").status)
    }

    @Test
    fun a_memoria_do_limitador_nao_cresce_sem_fim() {
        val throttle = LoginThrottle(maxFailures = 3)
        repeat(60_000) { throttle.failure("chave-$it") }
        // Passou do teto: o mapa foi limpo em vez de guardar tudo.
        assertTrue(throttle.size() <= 20_000, "guardou ${throttle.size()} chaves")
    }

    // ---------------------------------------------------------------- tokens

    private fun token(
        subject: String = UUID.randomUUID().toString(), algorithm: Algorithm = Algorithm.HMAC256(secret), issuer: String = JwtService.ISSUER,
        audience: String = JwtService.AUDIENCE, expires: Instant = Instant.now().plusSeconds(600),
    ): String = JWT.create().withIssuer(issuer).withAudience(audience).withSubject(subject).withExpiresAt(Date.from(expires)).sign(algorithm)

    @Test
    fun tokens_forjados_ou_adulterados_sao_recusados() = run { client ->
        val auth: app.apex.shared.AuthResponse = client.register(email()).body()
        // O token verdadeiro funciona...
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(auth.accessToken) }.status)

        val userId = auth.user.id
        val bad = mapOf(
            "assinado com outra chave" to token(userId, Algorithm.HMAC256("outra-chave-bem-longa-para-o-teste-0123456789")),
            "sem assinatura (alg none)" to token(userId, Algorithm.none()),
            "emissor errado" to token(userId, issuer = "invasor"),
            "público errado" to token(userId, audience = "outro-app"),
            "vencido" to token(userId, expires = Instant.now().minusSeconds(60)),
            "assinatura trocada" to auth.accessToken.substringBeforeLast('.') + "." + "AAAA" + auth.accessToken.substringAfterLast('.').drop(4),
            "payload trocado" to auth.accessToken.split('.').let { (h, _, s) -> "$h.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"${UUID.randomUUID()}"}""".toByteArray())}.$s" },
            "lixo" to "isto.nao.e-um-token",
        )
        for ((why, t) in bad) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(t) }.status, why)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync") { bearerAuth(t); contentType(ContentType.Application.Json); setBody("{}") }.status, why)
        }
        // Token de acesso não serve como token de renovação.
        val refresh = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"${auth.accessToken}"}""") }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
    }

    // ---------------------------------------------------------------- e-mails maliciosos

    @Test
    fun email_com_virgula_aspas_ou_lista_e_recusado() = run { client ->
        val bad = listOf(
            "a,b@apex.test", "a@apex.test,b@apex.test", "a;b@apex.test", "\"a b\"@apex.test", "a<b>@apex.test", "a b@apex.test",
            "a@apex.test\r\nBcc: x@y.com", "a@@apex.test", "@apex.test", "a@apex", "a@apex.t",
        )
        for (e in bad) {
            val r = client.register(e)
            assertEquals(HttpStatusCode.BadRequest, r.status, e)
            assertEquals("invalid_email", r.body<ErrorResponse>().error.code, e)
        }
        assertEquals(HttpStatusCode.Created, client.register("fulano+tag.${UUID.randomUUID().toString().take(8)}@exemplo.com.br").status)
    }

    // ---------------------------------------------------------------- texto que o banco recusa

    @Test
    fun caractere_nulo_e_texto_estranho_nunca_derrubam_o_servidor() = run { client ->
        // O que viaja no JSON é a sequência de escape (seis caracteres), que o servidor converte em um NUL de verdade.
        val nul = "\\u0000"
        suspend fun status(path: String, body: String, token: String? = null) =
            client.post(path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body) }.status

        // Login e "esqueci a senha": como qualquer e-mail inexistente, sem erro 500.
        assertEquals(HttpStatusCode.Unauthorized, status("/v1/auth/login", """{"email":"a${nul}b@apex.test","password":"x"}"""))
        assertEquals(HttpStatusCode.Unauthorized, status("/v1/auth/login", """{"email":"${"a".repeat(5_000)}@apex.test","password":"x"}"""))
        assertEquals(HttpStatusCode.Accepted, status("/v1/auth/forgot", """{"email":"a${nul}b@apex.test"}"""))

        // Cadastro com nome e aparelho cheios de caracteres de controle: aceita, mas limpa.
        val email = email()
        val registered = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"senha-forte-123","displayName":"Ful${nul}ano\u0007","acceptTerms":true}""")
        }
        assertEquals(HttpStatusCode.Created, registered.status)
        val auth: app.apex.shared.AuthResponse = registered.body()
        assertEquals("Fulano", auth.user.displayName)
        assertEquals(HttpStatusCode.OK, status("/v1/auth/login", """{"email":"$email","password":"senha-forte-123","device":"PC${nul}1"}"""))

        // Sincronização: chave com NUL é pedido inválido; dado com NUL dentro do JSON (que o jsonb recusa) também, nunca 500.
        val now = System.currentTimeMillis()
        assertEquals(HttpStatusCode.BadRequest, status("/v1/sync", """{"changes":[{"collection":"watch_later","key":"k${nul}1","data":{"v":"x"},"modifiedAt":$now}]}""", auth.accessToken))
        assertEquals(HttpStatusCode.BadRequest, status("/v1/sync", """{"changes":[{"collection":"watch_later","key":"k2","data":{"v":"a\u0000b"},"modifiedAt":$now}]}""", auth.accessToken))
        assertEquals(HttpStatusCode.OK, status("/v1/sync", """{"changes":[{"collection":"watch_later","key":"k3","data":{"v":"normal"},"modifiedAt":$now}]}""", auth.accessToken))
    }

    // ---------------------------------------------------------------- links de e-mail

    @Test
    fun so_o_link_de_senha_mais_novo_vale_e_cada_um_vale_uma_vez() {
        val mailer = LogMailer()
        run(mailer = mailer) { client ->
            val email = email()
            assertEquals(HttpStatusCode.Created, client.register(email).status)
            fun tokenOf(index: Int) = Regex("token=([A-Za-z0-9_-]+)").find(mailer.outbox.filter { it.to == email && it.subject.contains("senha") }[index].text)!!.groupValues[1]
            suspend fun waitFor(n: Int) = repeat(50) { if (mailer.outbox.count { it.to == email && it.subject.contains("senha") } < n) delay(100) }
            assertEquals(HttpStatusCode.Accepted, client.forgot(email).status); waitFor(1)
            assertEquals(HttpStatusCode.Accepted, client.forgot(email).status); waitFor(2)
            val old = tokenOf(0)
            val newest = tokenOf(1)
            suspend fun reset(token: String, password: String) = client.post("/v1/auth/reset") {
                contentType(ContentType.Application.Json); setBody("""{"token":"$token","newPassword":"$password"}""")
            }.status
            // O link antigo morreu quando o novo foi pedido.
            assertEquals(HttpStatusCode.BadRequest, reset(old, "senha-do-invasor-1"))
            assertEquals(HttpStatusCode.NoContent, reset(newest, "senha-nova-1234"))
            // E o novo também só serve uma vez.
            assertEquals(HttpStatusCode.BadRequest, reset(newest, "outra-senha-1234"))
            assertEquals(HttpStatusCode.OK, client.login(email, "senha-nova-1234").status)
            assertEquals(HttpStatusCode.Unauthorized, client.login(email, "senha-forte-123").status)
        }
    }

    // ---------------------------------------------------------------- tempo de resposta

    @Test
    fun esqueci_a_senha_nao_espera_o_email_nem_revela_quem_tem_conta() {
        val slow = object : Mailer {
            override suspend fun send(mail: Mail) {
                delay(2_500)
            }
        }
        run(mailer = slow) { client ->
            val email = email()
            assertEquals(HttpStatusCode.Created, client.register(email).status)
            val existing = measureTimeMillis { assertEquals(HttpStatusCode.Accepted, client.forgot(email).status) }
            val unknown = measureTimeMillis { assertEquals(HttpStatusCode.Accepted, client.forgot(email()).status) }
            // Um e-mail lento não atrasa a resposta de quem tem conta (senão o tempo entregaria quem tem).
            assertTrue(existing < 1_500, "demorou $existing ms para quem tem conta")
            assertTrue(kotlin.math.abs(existing - unknown) < 1_000, "tem conta: $existing ms; não tem: $unknown ms")
        }
    }

    // ---------------------------------------------------------------- erros e dados dos outros

    @Test
    fun erros_nao_vazam_detalhes_e_dados_nao_cruzam_entre_contas() = run { client ->
        val a: app.apex.shared.AuthResponse = client.register(email()).body()
        val b: app.apex.shared.AuthResponse = client.register(email()).body()
        val change = """{"changes":[{"collection":"watch_later","key":"segredo-de-a","data":{"v":"so-da-a"},"modifiedAt":${System.currentTimeMillis()}}]}"""
        assertEquals(HttpStatusCode.OK, client.post("/v1/sync") { bearerAuth(a.accessToken); contentType(ContentType.Application.Json); setBody(change) }.status)

        val seenByB = client.post("/v1/sync") { bearerAuth(b.accessToken); contentType(ContentType.Application.Json); setBody("{}") }
        assertFalse(seenByB.bodyText().contains("segredo-de-a"))
        val exportB = client.get("/v1/me/export") { bearerAuth(b.accessToken) }
        assertFalse(exportB.bodyText().contains("segredo-de-a"))
        assertNotEquals(a.user.id, b.user.id)

        // JSON quebrado e tipos errados: erro genérico, sem pilha nem nomes internos.
        val broken = client.post("/v1/sync") { bearerAuth(a.accessToken); contentType(ContentType.Application.Json); setBody("""{"changes":"não é lista"}""") }
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        assertFalse(broken.bodyText().contains("Exception") || broken.bodyText().contains("kotlinx"))
    }

    private suspend fun HttpResponse.bodyText(): String = bodyAsText()
}
