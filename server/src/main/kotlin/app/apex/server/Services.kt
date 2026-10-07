package app.apex.server

import app.apex.shared.AuthResponse
import app.apex.shared.DeleteAccountRequest
import app.apex.shared.ExportDto
import app.apex.shared.LoginRequest
import app.apex.shared.RegisterRequest
import app.apex.shared.SyncChange
import app.apex.shared.SyncCollections
import app.apex.shared.SyncItem
import app.apex.shared.SyncRequest
import app.apex.shared.SyncResponse
import app.apex.shared.UserDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ApiException(val status: HttpStatusCode, val code: String, message: String) : Exception(message)

private fun fail(status: HttpStatusCode, code: String, message: String): Nothing = throw ApiException(status, code, message)

/** Barra quem erra a senha muitas vezes seguidas para o mesmo e-mail. (Memória local: com várias instâncias, use um cache compartilhado.) */
class LoginThrottle(private val maxFailures: Int = 8, private val windowMs: Long = 15 * 60_000L) {
    private class Entry(var count: Int, val start: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun check(key: String) {
        val e = entries[key] ?: return
        if (System.currentTimeMillis() - e.start > windowMs) entries.remove(key, e)
        else if (e.count >= maxFailures) fail(HttpStatusCode.TooManyRequests, "too_many_attempts", "Muitas tentativas. Espere alguns minutos e tente de novo.")
    }

    fun failure(key: String) {
        val now = System.currentTimeMillis()
        entries.compute(key) { _, old -> if (old == null || now - old.start > windowMs) Entry(1, now) else old.also { it.count++ } }
    }

    fun success(key: String) {
        entries.remove(key)
    }
}

class AuthService(
    private val db: Database,
    private val jwt: JwtService,
    private val mailer: Mailer,
    private val config: ServerConfig,
) {
    private val log = LoggerFactory.getLogger("auth")
    private val throttle = LoginThrottle()
    private val emailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    private fun normalizeEmail(raw: String): String {
        val email = raw.trim().lowercase()
        if (email.length > 254 || !emailRegex.matches(email)) fail(HttpStatusCode.BadRequest, "invalid_email", "Esse e-mail não parece válido.")
        return email
    }

    private fun checkPassword(password: String) {
        if (password.length < 8) fail(HttpStatusCode.BadRequest, "weak_password", "A senha precisa ter pelo menos 8 caracteres.")
        if (password.length > 128) fail(HttpStatusCode.BadRequest, "invalid_password", "A senha pode ter no máximo 128 caracteres.")
    }

    private suspend fun hash(password: String) = withContext(Dispatchers.Default) { Passwords.hash(password) }

    private fun UserRow.dto() = UserDto(id.toString(), email, displayName, avatarUrl, emailVerified)

    private suspend fun session(user: UserRow, device: String?): AuthResponse {
        val refresh = db.query { TokenRepo.issue(it, user.id, device) }
        return AuthResponse(jwt.issue(user.id), refresh, JwtService.ACCESS_TTL_SEC, user.dto())
    }

    /**
     * Espera o e-mail sair (até 10 s). Em hospedagens que limitam o processador depois da resposta (Cloud Run, por exemplo),
     * deixar o envio "em segundo plano" faria o e-mail nunca sair.
     */
    private suspend fun sendMail(mail: Mail) {
        val sent = withTimeoutOrNull(10_000) { runCatching { mailer.send(mail) }.onFailure { log.warn("Falha ao enviar e-mail: {}", it.message) } }
        if (sent == null) log.warn("O envio de e-mail para {} demorou demais e foi abandonado.", mail.to)
    }

    suspend fun register(req: RegisterRequest, device: String?): AuthResponse {
        if (!req.acceptTerms) fail(HttpStatusCode.BadRequest, "terms_required", "Aceite os Termos de Uso e a Política de Privacidade para criar a conta.")
        val email = normalizeEmail(req.email)
        checkPassword(req.password)
        if (db.query { UserRepo.count(it) } >= config.maxUsers) {
            fail(HttpStatusCode.ServiceUnavailable, "registrations_closed", "No momento não estamos aceitando contas novas. Tente de novo mais tarde.")
        }
        val name = req.displayName?.trim()?.take(60)?.ifBlank { null } ?: email.substringBefore('@')
        val hash = hash(req.password)
        val user = try {
            db.query { UserRepo.create(it, email, hash, name, Legal.VERSION) }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") fail(HttpStatusCode.Conflict, "email_taken", "Este e-mail já tem uma conta. Tente entrar.")
            throw e
        }
        val token = db.query { TokenRepo.createEmailToken(it, user.id, "verify", 24 * 3600) }
        sendMail(MailTemplates.verify(email, "${config.publicUrl}/verify?token=$token"))
        return session(user, device)
    }

    /** Confere e-mail e senha (com a barreira de tentativas) e devolve a pessoa. */
    private suspend fun authenticate(rawEmail: String, password: String): UserRow {
        val email = rawEmail.trim().lowercase()
        throttle.check(email)
        val user = db.query { UserRepo.findByEmail(it, email) }
        val ok = withContext(Dispatchers.Default) { Passwords.verify(password, user?.passwordHash) }
        if (user == null || !ok) {
            throttle.failure(email)
            fail(HttpStatusCode.Unauthorized, "invalid_credentials", "E-mail ou senha incorretos.")
        }
        throttle.success(email)
        return user
    }

    suspend fun login(req: LoginRequest): AuthResponse = session(authenticate(req.email, req.password), req.device)

    suspend fun refresh(raw: String): AuthResponse {
        val result = db.query { TokenRepo.rotate(it, raw) }
        return when (result) {
            RotateResult.Invalid -> fail(HttpStatusCode.Unauthorized, "invalid_refresh", "Sessão expirada. Entre de novo.")
            RotateResult.Reuse -> fail(HttpStatusCode.Unauthorized, "session_revoked", "Esta sessão foi encerrada por segurança. Entre de novo.")
            is RotateResult.Ok -> {
                val user = db.query { UserRepo.findById(it, result.userId) }
                    ?: fail(HttpStatusCode.Unauthorized, "invalid_refresh", "Sessão expirada. Entre de novo.")
                AuthResponse(jwt.issue(user.id), result.newToken, JwtService.ACCESS_TTL_SEC, user.dto())
            }
        }
    }

    suspend fun logout(raw: String) {
        db.query { TokenRepo.revoke(it, raw) }
    }

    suspend fun me(userId: UUID): UserDto =
        db.query { UserRepo.findById(it, userId) }?.dto() ?: fail(HttpStatusCode.Unauthorized, "unknown_user", "Conta não encontrada.")

    suspend fun forgot(rawEmail: String) {
        val email = rawEmail.trim().lowercase()
        val user = db.query { UserRepo.findByEmail(it, email) } ?: return
        val token = db.query { TokenRepo.createEmailToken(it, user.id, "reset", 3600) }
        sendMail(MailTemplates.reset(user.email, "${config.publicUrl}/reset?token=$token"))
    }

    suspend fun reset(token: String, newPassword: String) {
        checkPassword(newPassword)
        val hash = hash(newPassword)
        val ok = db.query { c ->
            val userId = TokenRepo.consumeEmailToken(c, token, "reset") ?: return@query false
            UserRepo.updatePassword(c, userId, hash)
            TokenRepo.revokeAll(c, userId)
            true
        }
        if (!ok) fail(HttpStatusCode.BadRequest, "invalid_token", "Este link venceu ou já foi usado. Peça um novo.")
    }

    suspend fun verifyEmail(token: String) {
        val ok = db.query { c ->
            val userId = TokenRepo.consumeEmailToken(c, token, "verify") ?: return@query false
            UserRepo.markEmailVerified(c, userId)
            true
        }
        if (!ok) fail(HttpStatusCode.BadRequest, "invalid_token", "Este link venceu ou já foi usado.")
    }

    /** Apagar a conta pela página do site, sem precisar do app. */
    suspend fun deleteAccount(email: String, password: String) {
        val user = authenticate(email, password)
        db.query { UserRepo.delete(it, user.id) }
        log.info("Conta apagada pelo site.")
    }

    suspend fun deleteAccount(userId: UUID, req: DeleteAccountRequest) {
        val user = db.query { UserRepo.findById(it, userId) } ?: fail(HttpStatusCode.Unauthorized, "unknown_user", "Conta não encontrada.")
        val ok = withContext(Dispatchers.Default) { Passwords.verify(req.password, user.passwordHash) }
        if (!ok) fail(HttpStatusCode.Forbidden, "wrong_password", "Senha incorreta.")
        db.query { UserRepo.delete(it, userId) }
    }
}

/** [overlapSeconds]: o cursor devolvido volta alguns segundos, para nunca perder uma gravação que terminou fora de ordem. */
class SyncService(
    private val db: Database,
    private val overlapSeconds: Long = 5,
    private val maxRows: Long = MAX_ROWS_PER_USER,
    private val maxBytes: Long = MAX_BYTES_PER_USER,
) {
    private val json = Json

    suspend fun sync(userId: UUID, req: SyncRequest): SyncResponse {
        if (req.changes.size > MAX_CHANGES) fail(HttpStatusCode.PayloadTooLarge, "too_many_changes", "No máximo $MAX_CHANGES mudanças por vez.")
        val since = req.since?.let {
            runCatching { Instant.parse(it) }.getOrNull() ?: fail(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor inválido.")
        }
        val maxFuture = System.currentTimeMillis() + 5 * 60_000
        val prepared = req.changes.map { validate(it, maxFuture) }

        return try {
            db.query { c ->
                var applied = 0
                for (change in prepared) {
                    if (SyncRepo.upsert(c, userId, change.collection, change.key, change.dataText, change.modifiedAt, change.deleted)) applied++
                }
                if (prepared.isNotEmpty()) {
                    // Estourou o limite: a transação toda é desfeita, nada fica pela metade.
                    val (rows, bytes) = SyncRepo.usage(c, userId)
                    if (rows > maxRows || bytes > maxBytes) {
                        fail(HttpStatusCode.PayloadTooLarge, "quota_exceeded", "Sua conta chegou ao limite de dados sincronizados. Apague itens antigos e tente de novo.")
                    }
                }
                val startedAt = SyncRepo.now(c)
                val rows = SyncRepo.pull(c, userId, since, PAGE + 1)
                val hasMore = rows.size > PAGE
                val page = rows.take(PAGE)
                val cursor = if (hasMore) page.last().updatedAt else startedAt.minusSeconds(overlapSeconds)
                SyncResponse(
                    cursor = cursor.toString(),
                    items = page.map {
                        SyncItem(
                            it.collection, it.key, it.data?.let { text -> json.parseToJsonElement(text) },
                            it.deleted, it.modifiedAt, it.updatedAt.toString(),
                        )
                    },
                    hasMore = hasMore,
                    applied = applied,
                )
            }
        } catch (e: SQLException) {
            // A conta foi apagada enquanto o aparelho ainda tinha um token válido.
            if (e.sqlState == "23503") fail(HttpStatusCode.Unauthorized, "unknown_user", "Conta não encontrada.")
            throw e
        }
    }

    /** Tudo o que o servidor guarda sobre a pessoa. */
    suspend fun export(userId: UUID): ExportDto = db.query { c ->
        val user = UserRepo.findById(c, userId) ?: fail(HttpStatusCode.Unauthorized, "unknown_user", "Conta não encontrada.")
        val items = SyncRepo.pull(c, userId, null, maxRows.toInt() + 1).filterNot { it.deleted }
        ExportDto(
            exportedAt = Instant.now().toString(),
            user = UserDto(user.id.toString(), user.email, user.displayName, user.avatarUrl, user.emailVerified),
            createdAt = user.createdAt.toString(),
            termsAcceptedAt = user.termsAcceptedAt?.toString(),
            termsVersion = user.termsVersion,
            activeSessions = TokenRepo.activeSessions(c, userId),
            items = items.map {
                SyncItem(it.collection, it.key, it.data?.let { text -> json.parseToJsonElement(text) }, it.deleted, it.modifiedAt, it.updatedAt.toString())
            },
        )
    }

    private class Prepared(val collection: String, val key: String, val dataText: String?, val modifiedAt: Long, val deleted: Boolean)

    private fun validate(c: SyncChange, maxFuture: Long): Prepared {
        if (c.collection !in SyncCollections.all) fail(HttpStatusCode.BadRequest, "invalid_collection", "Coleção desconhecida: ${c.collection}")
        if (c.key.isBlank() || c.key.length > 200) fail(HttpStatusCode.BadRequest, "invalid_key", "Chave inválida.")
        val text = if (c.deleted || c.data == null || c.data is JsonNull) null else c.data.toString()
        if (text != null && text.length > MAX_ITEM_CHARS) fail(HttpStatusCode.PayloadTooLarge, "item_too_large", "Item grande demais.")
        return Prepared(c.collection, c.key, text, minOf(c.modifiedAt, maxFuture), c.deleted)
    }

    companion object {
        const val MAX_CHANGES = 500
        const val PAGE = 500
        const val MAX_ITEM_CHARS = 60_000

        /** Limites por conta, para que uma pessoa sozinha não encha o banco (o plano grátis do Neon tem 0,5 GB). */
        const val MAX_ROWS_PER_USER = 50_000L
        const val MAX_BYTES_PER_USER = 10L * 1024 * 1024
    }
}

/** Limpeza periódica do que não serve mais. */
object Maintenance {
    private val log = LoggerFactory.getLogger("maintenance")

    /** Quanto tempo guardamos a marca de "item apagado" para os outros aparelhos ficarem sabendo. */
    const val TOMBSTONE_DAYS = 180

    fun run(db: Database): Int = db.tx { c ->
        val sessions = c.execute("delete from refresh_tokens where expires_at < now() - interval '7 days' or revoked_at < now() - interval '7 days'")
        val links = c.execute("delete from email_tokens where expires_at < now() - interval '1 day' or used_at < now() - interval '1 day'")
        val tombstones = c.execute("delete from sync_items where deleted and deleted_at < now() - interval '$TOMBSTONE_DAYS days'")
        if (sessions + links + tombstones > 0) log.info("Limpeza: {} sessões, {} links, {} itens apagados antigos.", sessions, links, tombstones)
        sessions + links + tombstones
    }
}
