package app.apex.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class Database(private val ds: HikariDataSource, private val embedded: EmbeddedPostgres? = null) : AutoCloseable {
    /** Roda [block] numa transação; se der erro, desfaz tudo. */
    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        }
    }

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) { tx(block) }

    override fun close() {
        ds.close()
        embedded?.close()
    }

    companion object {
        /** Conecta (ou sobe o banco embutido em desenvolvimento) e aplica as migrações. */
        fun open(config: ServerConfig): Database {
            val embedded: EmbeddedPostgres?
            val conn: DbConnection
            if (config.database != null) {
                embedded = null
                conn = config.database
            } else {
                val pg = startEmbedded(File(config.devDataDir, "pgdata"))
                embedded = pg
                conn = DbConnection(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
            }
            val hikari = HikariConfig().apply {
                jdbcUrl = conn.jdbcUrl
                username = conn.user
                password = conn.password
                maximumPoolSize = if (config.isProduction) 10 else 5
                poolName = "apex"
            }
            val ds = HikariDataSource(hikari)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Database(ds, embedded)
        }

        private fun startEmbedded(dir: File): EmbeddedPostgres {
            dir.mkdirs()
            return EmbeddedPostgres.builder()
                .setDataDirectory(dir)
                .setCleanDataDirectory(false)
                .start()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pequenas ajudas de JDBC

fun Connection.prepare(sql: String, vararg args: Any?): PreparedStatement {
    val ps = prepareStatement(sql)
    args.forEachIndexed { i, a ->
        val idx = i + 1
        when (a) {
            null -> ps.setNull(idx, Types.NULL)
            is Instant -> ps.setObject(idx, OffsetDateTime.ofInstant(a, ZoneOffset.UTC))
            is Json -> ps.setObject(idx, a.text, Types.OTHER)
            else -> ps.setObject(idx, a)
        }
    }
    return ps
}

/** Valor para uma coluna `jsonb`. */
class Json(val text: String)

fun <T> Connection.queryList(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *args).use { ps ->
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    queryList(sql, *args, map = map).firstOrNull()

fun Connection.execute(sql: String, vararg args: Any?): Int = prepare(sql, *args).use { it.executeUpdate() }

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
