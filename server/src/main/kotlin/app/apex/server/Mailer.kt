package app.apex.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class Mail(val to: String, val subject: String, val html: String, val text: String)

interface Mailer {
    suspend fun send(mail: Mail)
}

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
