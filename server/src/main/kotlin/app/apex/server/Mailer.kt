package app.apex.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class Mail(val to: String, val subject: String, val html: String, val text: String)

interface Mailer {
    suspend fun send(mail: Mail)
}

/** Escolhe como enviar e-mails: Resend (com domínio), um relay seu (grátis, sem domínio) ou só o log (desenvolvimento). */
fun mailerFor(config: ServerConfig): Mailer =
    config.resendApiKey?.let { ResendMailer(it, config.mailFrom) }
        ?: config.mailWebhookUrl?.let { url -> config.mailWebhookSecret?.let { WebhookMailer(url, it) } }
        ?: LogMailer()

/** Sem provedor de e-mail configurado (desenvolvimento): escreve o e-mail no log para você clicar no link. */
class LogMailer : Mailer {
    private val log = LoggerFactory.getLogger("mail")
    val outbox = java.util.concurrent.CopyOnWriteArrayList<Mail>()

    override suspend fun send(mail: Mail) {
        outbox += mail
        log.info("E-mail para {} — {}\n{}", mail.to, mail.subject, mail.text)
    }
}

/** Envio pelo Resend (https://resend.com); o plano grátis atende bem no começo. */
class ResendMailer(private val apiKey: String, private val from: String) : Mailer {
    private val log = LoggerFactory.getLogger("mail")
    private val client = HttpClient(CIO)

    override suspend fun send(mail: Mail) {
        val body = buildJsonObject {
            put("from", from)
            put("to", JsonArray(listOf(JsonPrimitive(mail.to))))
            put("subject", mail.subject)
            put("html", mail.html)
            put("text", mail.text)
        }
        val response = client.post("https://api.resend.com/emails") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) log.warn("Resend recusou o e-mail para {}: HTTP {}", mail.to, response.status.value)
    }
}

/**
 * Envio por um "relay" seu: um pequeno endereço HTTPS (Google Apps Script ou uma função da Vercel) que manda o e-mail pela sua conta do Gmail.
 * Serve para ter e-mail grátis sem domínio próprio. Veja docs/email-relay/. Recebe um JSON com `secret`, `to`, `subject`, `html` e `text`
 * e responde `{"ok":true}`.
 */
class WebhookMailer(private val url: String, private val secret: String) : Mailer {
    // O Apps Script responde ao POST com um redirecionamento (302) para a página do resultado; seguimos nós mesmos, com GET.
    private val client = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
    }

    override suspend fun send(mail: Mail) {
        val body = buildJsonObject {
            put("secret", secret)
            put("to", mail.to)
            put("subject", mail.subject)
            put("html", mail.html)
            put("text", mail.text)
        }
        var response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val location = response.headers[io.ktor.http.HttpHeaders.Location]
        if (response.status.value in 301..303 && location != null) {
            // O e-mail já foi enviado pelo POST; o GET só busca a resposta para sabermos se deu certo.
            response = client.get(io.ktor.http.URLBuilder(url).takeFrom(location).buildString())
        }
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        // Quem chama (AuthService) registra o erro no log e segue em frente.
        check(response.status.isSuccess() && text.replace(" ", "").contains("\"ok\":true")) {
            "O relay de e-mail recusou o envio: HTTP ${response.status.value} ${text.take(120)}"
        }
    }
}

object MailTemplates {
    private fun page(title: String, body: String, link: String, button: String) = """
        <div style="font-family:Segoe UI,Arial,sans-serif;background:#0a0a0f;color:#f2f2f7;padding:32px">
          <div style="max-width:480px;margin:auto;background:#13131a;border-radius:16px;padding:28px">
            <div style="font-weight:800;letter-spacing:3px;font-size:18px">● APEX</div>
            <h2 style="margin:24px 0 8px">$title</h2>
            <p style="color:#9494aa;line-height:1.5">$body</p>
            <p><a href="$link" style="display:inline-block;background:#ff3b30;color:#fff;text-decoration:none;
               padding:12px 22px;border-radius:999px;font-weight:600">$button</a></p>
            <p style="color:#626277;font-size:12px">Se o botão não funcionar, copie este endereço:<br>$link</p>
          </div>
        </div>
    """.trimIndent()

    fun verify(to: String, link: String) = Mail(
        to, "Confirme seu e-mail no Apex",
        page("Confirme seu e-mail", "Falta só um passo para ativar sua conta. O link vale por 24 horas.", link, "Confirmar e-mail"),
        "Confirme seu e-mail no Apex: $link (vale por 24 horas)",
    )

    fun reset(to: String, link: String) = Mail(
        to, "Redefinir sua senha do Apex",
        page("Redefinir senha", "Recebemos um pedido para trocar sua senha. O link vale por 1 hora. Se não foi você, ignore este e-mail.", link, "Escolher nova senha"),
        "Redefina sua senha do Apex: $link (vale por 1 hora). Se não foi você, ignore.",
    )
}
