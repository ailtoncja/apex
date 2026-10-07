package app.apex.server

import app.apex.shared.RegisterRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** O envio de e-mail por relay (Apps Script ou Vercel), testado contra um relay de mentira. */
class MailerTest {
    private val received = CopyOnWriteArrayList<String>()
    private val resultFetched = CopyOnWriteArrayList<String>()

    private val relay = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        routing {
            // Como a Vercel: responde direto.
            post("/direct") {
                received += call.receiveText()
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
            // Como o Apps Script: o POST devolve um 302 para a página do resultado.
            post("/apps") {
                received += call.receiveText()
                call.response.header(HttpHeaders.Location, "/apps-result")
                call.respond(HttpStatusCode.Found)
            }
            get("/apps-result") {
                resultFetched += "x"
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
            post("/deny") {
                received += call.receiveText()
                call.respondText("""{"ok":false,"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            }
            post("/apps-deny") {
                call.response.header(HttpHeaders.Location, "/apps-deny-result")
                call.respond(HttpStatusCode.Found)
            }
            get("/apps-deny-result") {
                // O Apps Script responde 200 mesmo quando recusa.
                call.respondText("""{"ok":false,"error":"forbidden"}""", ContentType.Application.Json)
            }
        }
    }.also { it.start(wait = false) }

    private val base: String = runBlocking { "http://127.0.0.1:${relay.engine.resolvedConnectors().first().port}" }

    @AfterTest
    fun stopRelay() {
        relay.stop(0, 100)
    }

    private val mail = Mail("pessoa@exemplo.com", "Redefinir sua senha", "<b>oi</b>", "oi http://apex.test/reset?token=abc")

    @Test
    fun envia_direto_como_na_vercel() = runBlocking {
        WebhookMailer("$base/direct", "senha-do-relay").send(mail)
        val sent = Json.parseToJsonElement(received.single()).jsonObject
        assertEquals("senha-do-relay", sent["secret"]!!.jsonPrimitive.content)
        assertEquals("pessoa@exemplo.com", sent["to"]!!.jsonPrimitive.content)
        assertEquals("Redefinir sua senha", sent["subject"]!!.jsonPrimitive.content)
        assertTrue(sent["text"]!!.jsonPrimitive.content.contains("token=abc"))
        assertEquals("<b>oi</b>", sent["html"]!!.jsonPrimitive.content)
    }

    @Test
    fun segue_o_redirecionamento_como_no_apps_script() = runBlocking {
        WebhookMailer("$base/apps", "s").send(mail)
        assertEquals(1, received.size)
        assertEquals(1, resultFetched.size)
        Unit
    }

    @Test
    fun recusa_do_relay_vira_erro() = runBlocking {
        assertFailsWith<IllegalStateException> { WebhookMailer("$base/deny", "errada").send(mail) }
        assertFailsWith<IllegalStateException> { WebhookMailer("$base/apps-deny", "errada").send(mail) }
        Unit
    }

    @Test
    fun relay_fora_do_ar_vira_erro() = runBlocking {
        assertFailsWith<Exception> { WebhookMailer("http://127.0.0.1:1/x", "s").send(mail) }
        Unit
    }

    @Test
    fun escolha_do_envio_de_email() {
        val base = ServerConfig(
            port = 0, database = null, jwtSecret = "segredo-de-teste-com-mais-de-trinta-e-dois-caracteres", publicUrl = "http://apex.test",
            resendApiKey = null, mailFrom = "Apex <t@apex.test>", trustProxy = false, devDataDir = Files.createTempDirectory("apex-mail").toFile(),
        )
        assertIs<LogMailer>(mailerFor(base))
        assertIs<LogMailer>(mailerFor(base.copy(mailWebhookUrl = "https://script.google.com/x"))) // sem a senha, não vale
        assertIs<WebhookMailer>(mailerFor(base.copy(mailWebhookUrl = "https://script.google.com/x", mailWebhookSecret = "s")))
        assertIs<ResendMailer>(mailerFor(base.copy(resendApiKey = "re_x", mailWebhookUrl = "https://x.test", mailWebhookSecret = "s")))

        // Só endereços https valem; e a configuração lê as variáveis certas.
        val fromEnv = ServerConfig.fromEnv(mapOf("MAIL_WEBHOOK_URL" to "http://inseguro.test/x", "MAIL_WEBHOOK_SECRET" to "s"))
        assertEquals(null, fromEnv.mailWebhookUrl)
        val ok = ServerConfig.fromEnv(mapOf("MAIL_WEBHOOK_URL" to " https://script.google.com/macros/s/ID/exec ", "MAIL_WEBHOOK_SECRET" to "s"))
        assertEquals("https://script.google.com/macros/s/ID/exec", ok.mailWebhookUrl)
        assertEquals("s", ok.mailWebhookSecret)
    }

    /** Cadastro de verdade no servidor com o relay: o e-mail de confirmação chega ao relay com o link certo. */
    @Test
    fun cadastro_manda_o_email_de_confirmacao_pelo_relay() = testApplication {
        val config = ServerConfig(
            port = 0, database = null, jwtSecret = "segredo-de-teste-com-mais-de-trinta-e-dois-caracteres", publicUrl = "https://apex.test",
            resendApiKey = null, mailFrom = "Apex <t@apex.test>", trustProxy = false, devDataDir = Files.createTempDirectory("apex-mail2").toFile(),
            registerLimitPerHour = 10_000, mailLimitPerHour = 10_000,
        )
        application { apexModule(config, ApiTest.db, WebhookMailer("$base/direct", "senha-do-relay"), syncOverlapSeconds = 0) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val email = "relay-${UUID.randomUUID()}@apex.test"
        val response = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, "senha-forte-123", "Fulano", acceptTerms = true))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val sent = received.map { Json.parseToJsonElement(it).jsonObject }.single { it["to"]!!.jsonPrimitive.content == email }
        assertEquals("senha-do-relay", sent["secret"]!!.jsonPrimitive.content)
        assertTrue(sent["text"]!!.jsonPrimitive.content.contains("https://apex.test/verify?token="))
    }
}
