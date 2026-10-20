package app.apex.server

import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class UserRow(
    val id: UUID,
    val email: String,
    val emailVerified: Boolean,
    val passwordHash: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val createdAt: Instant,
    val termsAcceptedAt: Instant?,
    val termsVersion: String?,
)

object UserRepo {
    private const val COLUMNS = "id, email, email_verified, password_hash, display_name, avatar_url, created_at, terms_accepted_at, terms_version"

    private fun map(rs: java.sql.ResultSet) = UserRow(
        rs.uuid("id"), rs.getString("email"), rs.getBoolean("email_verified"),
        rs.getString("password_hash"), rs.getString("display_name"), rs.getString("avatar_url"),
        rs.instant("created_at"), rs.getObject("terms_accepted_at", OffsetDateTime::class.java)?.toInstant(), rs.getString("terms_version"),
    )

    fun create(c: Connection, email: String, passwordHash: String, displayName: String?, termsVersion: String): UserRow =
        c.queryOne(
            "insert into users (email, password_hash, display_name, terms_accepted_at, terms_version) values (?, ?, ?, now(), ?) returning $COLUMNS",
            email, passwordHash, displayName, termsVersion, map = ::map,
        )!!

    fun count(c: Connection): Long = c.queryOne("select count(*) as n from users") { it.getLong("n") } ?: 0

    fun findByEmail(c: Connection, email: String): UserRow? =
        c.queryOne("select $COLUMNS from users where lower(email) = lower(?)", email, map = ::map)

    fun findById(c: Connection, id: UUID): UserRow? =
        c.queryOne("select $COLUMNS from users where id = ?", id, map = ::map)

    fun markEmailVerified(c: Connection, id: UUID) {
        c.execute("update users set email_verified = true, updated_at = now() where id = ?", id)
    }

    fun updatePassword(c: Connection, id: UUID, hash: String) {
        c.execute("update users set password_hash = ?, updated_at = now() where id = ?", hash, id)
    }

    fun delete(c: Connection, id: UUID) {
        c.execute("delete from users where id = ?", id)
    }
}

sealed interface RotateResult {
    data object Invalid : RotateResult
    data object Reuse : RotateResult
    data class Ok(val userId: UUID, val session: NewSession) : RotateResult
}

/** Sessão recém-criada: o id (vai no token de acesso, como `sid`) e o token de renovação (só a pessoa tem; o banco guarda o hash). */
class NewSession(val id: UUID, val token: String)

/** Uma sessão como aparece na exportação de dados (sem o hash do token). */
class SessionRow(val device: String?, val createdAt: Instant, val expiresAt: Instant, val revokedAt: Instant?)

object TokenRepo {
    const val REFRESH_TTL_DAYS = 60L

    /** Cria uma sessão e devolve o token (só o hash vai para o banco). */
    fun issue(c: Connection, userId: UUID, device: String?): NewSession {
        val raw = Tokens.random()
        val id = c.queryOne(
            "insert into refresh_tokens (user_id, token_hash, device, expires_at) values (?, ?, ?, ?) returning id",
            userId, Tokens.sha256(raw), device?.take(80), Instant.now().plusSeconds(REFRESH_TTL_DAYS * 86_400),
        ) { it.uuid("id") }!!
        return NewSession(id, raw)
    }

    /** A sessão do token de acesso ainda vale (não saiu, não trocou a senha, não apagou a conta)? Busca pela chave primária. */
    fun isActive(c: Connection, sessionId: UUID, userId: UUID): Boolean =
        c.queryOne(
            "select 1 as ok from refresh_tokens where id = ? and user_id = ? and revoked_at is null and expires_at > now()", sessionId, userId,
        ) { true } ?: false

    /**
     * Troca um token de renovação por outro. Se alguém apresentar um token que já foi usado, é sinal de roubo:
     * todas as sessões da pessoa são encerradas.
     */
    fun rotate(c: Connection, raw: String): RotateResult {
        class Row(val id: UUID, val userId: UUID, val expiresAt: Instant, val revoked: Boolean, val device: String?)
        val row = c.queryOne(
            "select id, user_id, expires_at, revoked_at is not null as revoked, device from refresh_tokens where token_hash = ? for update",
            Tokens.sha256(raw),
        ) { rs -> Row(rs.uuid("id"), rs.uuid("user_id"), rs.instant("expires_at"), rs.getBoolean("revoked"), rs.getString("device")) }
            ?: return RotateResult.Invalid
        if (row.revoked) {
            c.execute("update refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null", row.userId)
            return RotateResult.Reuse
        }
        if (row.expiresAt.isBefore(Instant.now())) return RotateResult.Invalid
        c.execute("update refresh_tokens set revoked_at = now() where id = ?", row.id)
        return RotateResult.Ok(row.userId, issue(c, row.userId, row.device))
    }

    fun revoke(c: Connection, raw: String) {
        c.execute("update refresh_tokens set revoked_at = now() where token_hash = ? and revoked_at is null", Tokens.sha256(raw))
    }

    fun revokeAll(c: Connection, userId: UUID) {
        c.execute("update refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null", userId)
    }

    /** Aparelhos com sessão aberta (não revogada e não vencida). */
    fun activeSessions(c: Connection, userId: UUID): Int =
        c.queryOne(
            "select count(*) as n from refresh_tokens where user_id = ? and revoked_at is null and expires_at > now()", userId,
        ) { it.getInt("n") } ?: 0

    /**
     * Todas as sessões guardadas (inclusive as encerradas, que ficam até 7 dias antes da limpeza), da mais nova para a mais antiga.
     * Cada renovação cria uma linha nova, então o `createdAt` da sessão aberta é a última vez que o aparelho renovou o acesso.
     */
    fun sessions(c: Connection, userId: UUID): List<SessionRow> =
        c.queryList(
            "select device, created_at, expires_at, revoked_at from refresh_tokens where user_id = ? order by created_at desc", userId,
        ) { rs ->
            SessionRow(rs.getString("device"), rs.instant("created_at"), rs.instant("expires_at"), rs.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant())
        }

    // ---- links de e-mail (confirmar e redefinir senha)

    fun createEmailToken(c: Connection, userId: UUID, purpose: String, ttlSeconds: Long): String {
        // Só o link mais novo vale: os anteriores (que podem estar numa caixa de e-mail velha ou vazada) deixam de funcionar.
        expireEmailTokens(c, userId, purpose)
        val raw = Tokens.random()
        c.execute(
            "insert into email_tokens (token_hash, user_id, purpose, expires_at) values (?, ?, ?, ?)",
            Tokens.sha256(raw), userId, purpose, Instant.now().plusSeconds(ttlSeconds),
        )
        return raw
    }

    fun expireEmailTokens(c: Connection, userId: UUID, purpose: String) {
        c.execute("update email_tokens set used_at = now() where user_id = ? and purpose = ? and used_at is null", userId, purpose)
    }

    /** O link ainda vale (sem gastá-lo)? Serve para não calcular o Argon2 de quem chega com um link inválido. */
    fun isEmailTokenValid(c: Connection, raw: String, purpose: String): Boolean =
        c.queryOne(
            "select 1 as ok from email_tokens where token_hash = ? and purpose = ? and used_at is null and expires_at > now()",
            Tokens.sha256(raw), purpose,
        ) { true } ?: false

    /** Usa o link (uma vez só). Devolve a quem ele pertence, ou `null` se for inválido, vencido ou já usado. */
    fun consumeEmailToken(c: Connection, raw: String, purpose: String): UUID? =
        c.queryOne(
            "update email_tokens set used_at = now() where token_hash = ? and purpose = ? and used_at is null and expires_at > now() returning user_id",
            Tokens.sha256(raw), purpose,
        ) { it.uuid("user_id") }
}

class StoredItem(
    val collection: String,
    val key: String,
    val data: String?,
    val modifiedAt: Long,
    val deleted: Boolean,
    val updatedAt: Instant,
)

object SyncRepo {
    private const val UPSERT = """
        insert into sync_items (user_id, collection, key, data, modified_at, deleted, deleted_at, updated_at)
        values (?, ?, ?, ?, ?, ?, case when ? then now() end, clock_timestamp())
        on conflict (user_id, collection, key) do update set
            data = excluded.data, modified_at = excluded.modified_at, deleted = excluded.deleted,
            deleted_at = excluded.deleted_at, updated_at = clock_timestamp()
        where excluded.modified_at >= sync_items.modified_at
    """

    /** Grava uma mudança. Devolve `false` se o servidor já tinha uma versão mais nova. */
    fun upsert(c: Connection, userId: UUID, collection: String, key: String, data: String?, modifiedAt: Long, deleted: Boolean): Boolean =
        c.execute(UPSERT, userId, collection, key, data?.let { Json(it) }, modifiedAt, deleted, deleted) > 0

    /** Quantas linhas (inclusive as de itens apagados) e quantos bytes de dados a pessoa já tem guardados. */
    fun usage(c: Connection, userId: UUID): Pair<Long, Long> =
        c.queryOne(
            "select count(*) as n, coalesce(sum(pg_column_size(data)), 0) as bytes from sync_items where user_id = ?", userId,
        ) { it.getLong("n") to it.getLong("bytes") } ?: (0L to 0L)

    fun now(c: Connection): Instant =
        c.queryOne("select clock_timestamp() as t") { it.instant("t") }!!

    fun pull(c: Connection, userId: UUID, since: Instant?, limit: Int): List<StoredItem> {
        val map = { rs: java.sql.ResultSet ->
            StoredItem(
                rs.getString("collection"), rs.getString("key"), rs.getString("data"),
                rs.getLong("modified_at"), rs.getBoolean("deleted"), rs.instant("updated_at"),
            )
        }
        val columns = "collection, key, data::text as data, modified_at, deleted, updated_at"
        return if (since == null) {
            c.queryList("select $columns from sync_items where user_id = ? order by updated_at, collection, key limit ?", userId, limit, map = map)
        } else {
            c.queryList(
                "select $columns from sync_items where user_id = ? and updated_at > ? order by updated_at, collection, key limit ?",
                userId, since, limit, map = map,
            )
        }
    }
}
