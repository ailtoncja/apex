package app.apex.server

import app.apex.shared.DeleteAccountRequest
import app.apex.shared.ErrorBody
import app.apex.shared.ErrorResponse
import app.apex.shared.ForgotRequest
import app.apex.shared.LoginRequest
import app.apex.shared.RefreshRequest
import app.apex.shared.RegisterRequest
import app.apex.shared.ResetRequest
import app.apex.shared.SyncRequest
import app.apex.shared.VerifyRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("apex")

private val AUTH_LIMIT = RateLimitName("auth")
private val SYNC_LIMIT = RateLimitName("sync")

fun Application.apexModule(config: ServerConfig, db: Database, mailer: Mailer, syncOverlapSeconds: Long = 5) {
    val jwt = JwtService(config.jwtSecret)
    val auth = AuthService(db, jwt, mailer, config)
    val sync = SyncService(db, syncOverlapSeconds)

    if (config.trustProxy) install(XForwardedHeaders)
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorResponse(ErrorBody(e.code, e.message ?: "Erro"))) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorBody("bad_request", "Pedido inválido.")))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorBody("bad_request", "Pedido inválido.")))
        }
        exception<Throwable> { call, e ->
            log.error("Erro inesperado em {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorBody("internal", "Algo deu errado do nosso lado.")))
        }
    }
    install(RateLimit) {
        register(AUTH_LIMIT) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(SYNC_LIMIT) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<JWTPrincipal>()?.subject ?: call.request.origin.remoteHost }
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "apex"
            verifier(jwt.verifier)
            validate { credential -> if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorBody("unauthorized", "Entre de novo.")))
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        route("/v1") {
            rateLimit(AUTH_LIMIT) {
                post("/auth/register") {
                    val req = call.receive<RegisterRequest>()
                    call.respond(HttpStatusCode.Created, auth.register(req, call.request.userAgent()?.take(80)))
                }
                post("/auth/login") {
                    val req = call.receive<LoginRequest>()
                    call.respond(auth.login(req.copy(device = req.device ?: call.request.userAgent()?.take(80))))
                }
                post("/auth/refresh") { call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken)) }
                post("/auth/logout") {
                    auth.logout(call.receive<RefreshRequest>().refreshToken)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/auth/forgot") {
                    auth.forgot(call.receive<ForgotRequest>().email)
                    // Sempre a mesma resposta, exista a conta ou não.
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/auth/reset") {
                    val req = call.receive<ResetRequest>()
                    auth.reset(req.token, req.newPassword)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/auth/verify") {
                    auth.verifyEmail(call.receive<VerifyRequest>().token)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            authenticate("auth-jwt") {
                get("/me") { call.respond(auth.me(call.userId())) }
                delete("/me") {
                    auth.deleteAccount(call.userId(), call.receive<DeleteAccountRequest>())
                    call.respond(HttpStatusCode.NoContent)
                }
                rateLimit(SYNC_LIMIT) {
                    post("/sync") { call.respond(sync.sync(call.userId(), call.receive<SyncRequest>())) }
                }
            }
        }

        // Páginas simples para quem clica nos links dos e-mails.
        get("/verify") {
            val token = call.request.queryParameters["token"].orEmpty()
            val message = try {
                auth.verifyEmail(token)
                WebPages.message("E-mail confirmado", "Tudo certo! Pode voltar para o Apex.")
            } catch (e: ApiException) {
                WebPages.message("Link inválido", e.message ?: "Este link não vale mais.")
            }
            call.respondText(message, ContentType.Text.Html)
        }
        get("/reset") {
            call.respondText(WebPages.resetForm(call.request.queryParameters["token"].orEmpty()), ContentType.Text.Html)
        }
        post("/reset") {
            val form = call.receiveParameters()
            val token = form["token"].orEmpty()
            val password = form["password"].orEmpty()
            val page = try {
                auth.reset(token, password)
                WebPages.message("Senha alterada", "Agora é só entrar no Apex com a nova senha.")
            } catch (e: ApiException) {
                WebPages.resetForm(token, e.message)
            }
            call.respondText(page, ContentType.Text.Html)
        }
    }
}

private fun ApplicationCall.userId(): UUID =
    UUID.fromString(principal<JWTPrincipal>()?.subject ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Entre de novo."))

object WebPages {
    private fun shell(title: String, content: String) = """
        <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title — Apex</title>
        <style>
          body{margin:0;background:#0a0a0f;color:#f2f2f7;font-family:Segoe UI,Arial,sans-serif;display:grid;place-items:center;min-height:100vh}
          main{background:#13131a;border-radius:18px;padding:32px;max-width:400px;width:calc(100% - 48px)}
          .logo{font-weight:800;letter-spacing:3px}.logo b{color:#ff3b30}
          p{color:#9494aa;line-height:1.5} input{width:100%;box-sizing:border-box;padding:12px 14px;border-radius:12px;border:1px solid #2c2c3b;
          background:#0a0a0f;color:#fff;font-size:15px;margin:8px 0 14px} button{width:100%;padding:12px;border:0;border-radius:999px;
          background:#ff3b30;color:#fff;font-weight:600;font-size:15px;cursor:pointer} .err{color:#ff6b61}
        </style></head><body><main><div class="logo"><b>●</b> APEX</div>$content</main></body></html>
    """.trimIndent()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun message(title: String, text: String) = shell(title, "<h2>${esc(title)}</h2><p>${esc(text)}</p>")

    fun resetForm(token: String, error: String? = null) = shell(
        "Nova senha",
        """<h2>Escolha uma nova senha</h2>
           ${if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""}
           <form method="post" action="/reset">
             <input type="hidden" name="token" value="${esc(token)}">
             <input type="password" name="password" placeholder="Nova senha (mínimo 8 caracteres)" minlength="8" maxlength="128" required autofocus>
             <button type="submit">Salvar senha</button>
           </form>""",
    )
}
