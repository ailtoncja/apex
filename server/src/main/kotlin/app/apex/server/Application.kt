package app.apex.server

import app.apex.shared.DeleteAccountRequest
import app.apex.shared.ErrorBody
import app.apex.shared.ErrorResponse
import app.apex.shared.ForgotRequest
import app.apex.shared.InfoResponse
import app.apex.shared.LoginRequest
import app.apex.shared.RefreshRequest
import app.apex.shared.RegisterRequest
import app.apex.shared.ResetRequest
import app.apex.shared.SyncRequest
import app.apex.shared.VerifyRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("apex")

private val AUTH_LIMIT = RateLimitName("auth")
private val REGISTER_LIMIT = RateLimitName("register")
private val MAIL_LIMIT = RateLimitName("mail")
private val SYNC_LIMIT = RateLimitName("sync")

private const val MAX_BODY = 64L * 1024
private const val MAX_SYNC_BODY = 4L * 1024 * 1024

/** Cabeçalhos de segurança em todas as respostas; as páginas com links de e-mail nunca ficam em cache. */
private fun securityHeaders(hsts: Boolean) = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        val h = call.response.headers
        h.append("X-Content-Type-Options", "nosniff")
        h.append("Referrer-Policy", "no-referrer")
        h.append("X-Frame-Options", "DENY")
        h.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        if (hsts) h.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        val path = call.request.path()
        if (path == "/reset" || path == "/verify" || path.startsWith("/account") || path.startsWith("/v1")) h.append(HttpHeaders.CacheControl, "no-store")
    }
}

fun Application.apexModule(config: ServerConfig, db: Database, mailer: Mailer, syncOverlapSeconds: Long = 5) {
    val jwt = JwtService(config.jwtSecret)
    val auth = AuthService(db, jwt, mailer, config, this)
    val sync = SyncService(db, syncOverlapSeconds, config.maxRowsPerUser, config.maxBytesPerUser)
    val pages = WebPages(config)

    install(securityHeaders(hsts = config.publicUrl.startsWith("https://")))
    // Sem a query na linha do log: os links dos e-mails levam um token (?token=...).
    install(CallLogging) {
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
    install(RequestBodyLimit) {
        bodyLimit { call -> if (call.request.path().startsWith("/v1/sync")) MAX_SYNC_BODY else MAX_BODY }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorResponse(ErrorBody(e.code, e.message ?: "Erro"))) }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse(ErrorBody("too_large", "O pedido é grande demais.")))
        }
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
            requestKey { call -> call.clientIp(config) }
        }
        register(REGISTER_LIMIT) {
            rateLimiter(limit = config.registerLimitPerHour, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp(config) }
        }
        register(MAIL_LIMIT) {
            rateLimiter(limit = config.mailLimitPerHour, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp(config) }
        }
        register(SYNC_LIMIT) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<JWTPrincipal>()?.subject ?: call.clientIp(config) }
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
        // TEMPORÁRIO: descobrir quantos proxies a hospedagem coloca na frente (sai no próximo commit).
        get("/v1/_whoami") {
            val h = call.request.headers
            fun all(n: String) = (h.getAll(n) ?: emptyList()).joinToString(" | ")
            val lines = listOf(
                "direct=${call.request.local.remoteAddress}", "xff=${all("X-Forwarded-For")}", "cf=${all("CF-Connecting-IP")}",
                "true=${all("True-Client-IP")}", "real=${all("X-Real-IP")}", "fwd=${all("Forwarded")}",
            )
            call.respondText(lines.joinToString(System.lineSeparator()))
        }
        get("/") { call.respondText(pages.home(), ContentType.Text.Html) }
        get("/terms") { call.respondText(pages.terms(), ContentType.Text.Html) }
        get("/privacy") { call.respondText(pages.privacy(), ContentType.Text.Html) }
        get("/robots.txt") { call.respondText("User-agent: *\nDisallow: /reset\nDisallow: /verify\nDisallow: /account\nDisallow: /v1\n") }

        route("/v1") {
            get("/info") { call.respond(InfoResponse(mailer !is LogMailer, Legal.VERSION, config.contactEmail)) }

            rateLimit(AUTH_LIMIT) {
                rateLimit(REGISTER_LIMIT) {
                    post("/auth/register") {
                        val req = call.receive<RegisterRequest>()
                        call.respond(HttpStatusCode.Created, auth.register(req, call.request.userAgent()?.take(80)))
                    }
                }
                post("/auth/login") {
                    val req = call.receive<LoginRequest>()
                    call.respond(auth.login(req.copy(device = req.device ?: call.request.userAgent()?.take(80)), call.clientIp(config)))
                }
                post("/auth/refresh") { call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken)) }
                post("/auth/logout") {
                    auth.logout(call.receive<RefreshRequest>().refreshToken)
                    call.respond(HttpStatusCode.NoContent)
                }
                rateLimit(MAIL_LIMIT) {
                    post("/auth/forgot") {
                        auth.forgot(call.receive<ForgotRequest>().email)
                        // Sempre a mesma resposta, exista a conta ou não.
                        call.respond(HttpStatusCode.Accepted)
                    }
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
                get("/me/export") {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"apex-meus-dados.json\"")
                    call.respond(sync.export(call.userId()))
                }
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
                pages.message("E-mail confirmado", "Tudo certo! Pode voltar para o Apex.")
            } catch (e: ApiException) {
                pages.message("Link inválido", e.message ?: "Este link não vale mais.")
            }
            call.respondText(message, ContentType.Text.Html)
        }
        get("/reset") {
            call.respondText(pages.resetForm(call.request.queryParameters["token"].orEmpty()), ContentType.Text.Html)
        }
        post("/reset") {
            val form = call.receiveParameters()
            val token = form["token"].orEmpty()
            val password = form["password"].orEmpty()
            val page = try {
                auth.reset(token, password)
                pages.message("Senha alterada", "Agora é só entrar no Apex com a nova senha.")
            } catch (e: ApiException) {
                pages.resetForm(token, e.message)
            }
            call.respondText(page, ContentType.Text.Html)
        }
        get("/account/delete") { call.respondText(pages.deleteForm(), ContentType.Text.Html) }
        rateLimit(AUTH_LIMIT) {
            post("/account/delete") {
                val form = call.receiveParameters()
                val page = try {
                    if (form["confirm"] != "yes") throw ApiException(HttpStatusCode.BadRequest, "not_confirmed", "Marque a caixa para confirmar.")
                    auth.deleteAccount(form["email"].orEmpty(), form["password"].orEmpty(), call.clientIp(config))
                    pages.message("Conta excluída", "Pronto. A sua conta e os dados guardados no servidor foram apagados.")
                } catch (e: ApiException) {
                    pages.deleteForm(e.message)
                }
                call.respondText(page, ContentType.Text.Html)
            }
        }
    }

    // Limpa de tempos em tempos sessões vencidas, links já usados e marcas antigas de itens apagados.
    launch {
        delay(60.seconds)
        while (isActive) {
            runCatching { Maintenance.run(db) }.onFailure { log.warn("A limpeza falhou: {}", it.message) }
            delay(6.hours)
        }
    }
}

/**
 * IP de quem fez o pedido, para os limites de uso. Atrás de proxy o `X-Forwarded-For` traz o que o cliente mandou (que ele pode inventar)
 * seguido do que cada proxy nosso acrescentou; só vale o que os nossos proxies acrescentaram, contando [ServerConfig.trustedProxyHops]
 * entradas a partir do fim da lista. Sem proxy configurado, o cabeçalho é ignorado.
 */
fun ApplicationCall.clientIp(config: ServerConfig): String {
    val direct = request.local.remoteAddress
    if (!config.trustProxy) return direct
    val entries = request.headers.getAll("X-Forwarded-For").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return entries.getOrNull(entries.size - config.trustedProxyHops) ?: direct
}

private fun ApplicationCall.userId(): UUID =
    UUID.fromString(principal<JWTPrincipal>()?.subject ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Entre de novo."))
