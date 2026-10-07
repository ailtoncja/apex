package app.apex.server

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

class ConfigException(message: String) : Exception(message)

/** Conexão com o PostgreSQL já no formato do JDBC. */
data class DbConnection(val jdbcUrl: String, val user: String?, val password: String?)

data class ServerConfig(
    val port: Int,
    /** `null` = modo desenvolvimento, com um PostgreSQL embutido. */
    val database: DbConnection?,
    val jwtSecret: String,
    /** Endereço público do servidor; vai nos links dos e-mails. */
    val publicUrl: String,
    val resendApiKey: String?,
    val mailFrom: String,
    val trustProxy: Boolean,
    val devDataDir: File,
) {
    val isProduction: Boolean get() = database != null

    companion object {
        /** Variáveis do sistema; as que faltarem vêm de um arquivo `.env` (na pasta atual ou em `~/.apex-server`). */
        fun load(): ServerConfig {
            val system = System.getenv()
            val home = File(system["APEX_SERVER_DATA"] ?: File(System.getProperty("user.home"), ".apex-server").path)
            val files = listOfNotNull(system["APEX_SERVER_ENV"]?.let(::File), File(".env"), File("server/.env"), File(home, ".env"))
            val fromFile = files.firstOrNull { it.isFile }?.let { parseDotEnv(it.readText()) }.orEmpty()
            return fromEnv(fromFile + system)
        }

        /** Lê linhas `CHAVE=valor` (aceita aspas e comentários com `#`). */
        fun parseDotEnv(text: String): Map<String, String> = text.removePrefix("﻿").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { line ->
                val key = line.substringBefore('=').trim().removePrefix("export ").trim()
                key to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            }

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = File(env["APEX_SERVER_DATA"] ?: File(System.getProperty("user.home"), ".apex-server").path)
            val database = env["DATABASE_URL"]?.takeIf { it.isNotBlank() }?.let { parseDatabaseUrl(it, env) }
            val port = env["PORT"]?.toIntOrNull() ?: 8080

            val secret = env["JWT_SECRET"]?.takeIf { it.isNotBlank() }
            if (database != null && (secret == null || secret.length < 32)) {
                throw ConfigException("Em produção, defina JWT_SECRET com pelo menos 32 caracteres.")
            }
            return ServerConfig(
                port = port,
                database = database,
                jwtSecret = secret ?: devSecret(dataDir),
                publicUrl = (env["PUBLIC_URL"] ?: "http://localhost:$port").trimEnd('/'),
                resendApiKey = env["RESEND_API_KEY"]?.takeIf { it.isNotBlank() },
                mailFrom = env["MAIL_FROM"] ?: "Apex <onboarding@resend.dev>",
                trustProxy = env["TRUST_PROXY"] == "true",
                devDataDir = dataDir,
            )
        }

        /** Aceita `postgres://usuario:senha@host/banco?sslmode=require` (o formato do Neon) ou um `jdbc:postgresql://…`. */
        fun parseDatabaseUrl(url: String, env: Map<String, String> = emptyMap()): DbConnection {
            if (url.startsWith("jdbc:")) return DbConnection(url, env["DB_USER"], env["DB_PASSWORD"])
            val uri = URI(url.replaceFirst("postgresql://", "postgres://"))
            if (uri.scheme != "postgres") throw ConfigException("DATABASE_URL precisa começar com postgres:// ou jdbc:postgresql://")
            val (user, pass) = uri.userInfo?.split(":", limit = 2)?.let {
                URLDecoder.decode(it[0], "UTF-8") to it.getOrNull(1)?.let { p -> URLDecoder.decode(p, "UTF-8") }
            } ?: (null to null)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            // O Neon manda `channel_binding=require`, que o driver do Java não conhece; sem ele a conexão funciona igual.
            val query = uri.rawQuery?.split('&')?.filterNot { it.startsWith("channel_binding=") }?.takeIf { it.isNotEmpty() }
                ?.joinToString("&", prefix = "?") ?: ""
            return DbConnection("jdbc:postgresql://${uri.host}$port${uri.rawPath}$query", user, pass)
        }

        /** No desenvolvimento o segredo dos tokens é gerado uma vez e guardado. */
        private fun devSecret(dir: File): String {
            dir.mkdirs()
            val file = File(dir, "jwt.secret")
            if (file.isFile) return file.readText().trim()
            val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { file.writeText(it) }
        }
    }
}
