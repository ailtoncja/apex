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
    /** Quantos proxies de confiança (balanceador, CDN) ficam na frente do servidor; só o que eles acrescentam ao X-Forwarded-For vale. */
    val trustedProxyHops: Int = 1,
    /** Cabeçalho que o CDN da frente preenche com o IP do cliente (na Render, `CF-Connecting-IP`). Tem prioridade sobre o X-Forwarded-For. */
    val clientIpHeader: String? = null,
    /** Quem responde pelo serviço; aparece na Política de Privacidade e nos Termos. */
    val contactEmail: String? = null,
    val operatorName: String? = null,
    /** Link para baixar o app, mostrado na página inicial. */
    val downloadUrl: String? = null,
    /** Proteção do banco grátis: depois disso, novos cadastros são recusados até você aumentar o limite. */
    val maxUsers: Int = 2000,
    /** Contas novas por hora, por endereço (IP). */
    val registerLimitPerHour: Int = 20,
    /** Pedidos de "esqueci a senha" por hora, por endereço (cada um manda um e-mail). */
    val mailLimitPerHour: Int = 10,
    /** E-mail grátis sem domínio: endereço (https) e senha de um relay seu; veja docs/email-relay/. Só vale se não houver `resendApiKey`. */
    val mailWebhookUrl: String? = null,
    val mailWebhookSecret: String? = null,
    /** Limites de dados sincronizados por conta (linhas e bytes). */
    val maxRowsPerUser: Long = SyncService.MAX_ROWS_PER_USER,
    val maxBytesPerUser: Long = SyncService.MAX_BYTES_PER_USER,
    /** Em que endereço o servidor escuta. Por padrão só no próprio computador; no container (Render/Fly) o Dockerfile define `BIND_HOST=0.0.0.0`. */
    val bindHost: String = DEFAULT_BIND_HOST,
) {
    val isProduction: Boolean get() = database != null

    companion object {
        /** Onde ficam os downloads do app (a página de Releases do projeto). */
        const val DEFAULT_DOWNLOAD_URL = "https://github.com/ailtoncja/apex/releases/latest"

        const val DEFAULT_BIND_HOST = "127.0.0.1"

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

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
                mailWebhookUrl = env["MAIL_WEBHOOK_URL"]?.trim()?.takeIf { it.startsWith("https://") },
                mailWebhookSecret = env["MAIL_WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() },
                trustProxy = env["TRUST_PROXY"] == "true",
                trustedProxyHops = env["TRUSTED_PROXY_HOPS"]?.toIntOrNull()?.coerceIn(1, 5) ?: 1,
                clientIpHeader = env["CLIENT_IP_HEADER"]?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) },
                devDataDir = dataDir,
                contactEmail = env["CONTACT_EMAIL"]?.trim()?.takeIf { it.isNotBlank() },
                operatorName = env["OPERATOR_NAME"]?.trim()?.takeIf { it.isNotBlank() },
                downloadUrl = env["DOWNLOAD_URL"]?.trim()?.takeIf { it.startsWith("https://") } ?: DEFAULT_DOWNLOAD_URL,
                maxUsers = env["MAX_USERS"]?.toIntOrNull()?.coerceAtLeast(0) ?: 2000,
                registerLimitPerHour = env["REGISTER_LIMIT_PER_HOUR"]?.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                mailLimitPerHour = env["MAIL_LIMIT_PER_HOUR"]?.toIntOrNull()?.coerceAtLeast(1) ?: 10,
                bindHost = env["BIND_HOST"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BIND_HOST,
            )
        }

        /**
         * Aceita `postgres://usuario:senha@host/banco?sslmode=require&channel_binding=require` (o formato do Neon) ou um `jdbc:postgresql://…`
         * (que passa sem mudança, para quem precisa de algo diferente).
         *
         * Banco fora da própria máquina: a conexão é sempre criptografada **e** o certificado é conferido (`verify-full`, com os certificados
         * confiáveis do Java), qualquer que seja o `sslmode` da string. O `sslmode=require` do libpq/Neon não confere o certificado, e no
         * driver do Java aceitaria qualquer um (um intermediário leria a senha e os dados). O `channel_binding=require` do libpq vira o
         * `channelBinding=require` do driver do Java. No próprio computador (desenvolvimento, sem TLS) nada disso é imposto.
         */
        fun parseDatabaseUrl(url: String, env: Map<String, String> = emptyMap()): DbConnection {
            if (url.startsWith("jdbc:")) return DbConnection(url, env["DB_USER"], env["DB_PASSWORD"])
            val uri = URI(url.replaceFirst("postgresql://", "postgres://"))
            if (uri.scheme != "postgres") throw ConfigException("DATABASE_URL precisa começar com postgres:// ou jdbc:postgresql://")
            val (user, pass) = uri.userInfo?.split(":", limit = 2)?.let {
                URLDecoder.decode(it[0], "UTF-8") to it.getOrNull(1)?.let { p -> URLDecoder.decode(p, "UTF-8") }
            } ?: (null to null)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val local = uri.host in LOCAL_HOSTS
            val params = uri.rawQuery?.split('&').orEmpty().filter { it.isNotEmpty() }
            val channelBinding = params.any { it == "channel_binding=require" || it == "channelBinding=require" }
            val parts = params.filterNot { p ->
                val key = p.substringBefore('=')
                // O TLS do banco remoto é decidido aqui embaixo; o que a string pedir não rebaixa a segurança.
                key == "channel_binding" || key == "channelBinding" || (!local && key in setOf("sslmode", "sslfactory", "ssl"))
            }.toMutableList()
            if (!local) {
                parts += "sslmode=verify-full"
                parts += "sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
                // Sem TLS (só no próprio computador) não há canal para amarrar: lá o parâmetro é ignorado.
                if (channelBinding) parts += "channelBinding=require"
            }
            val query = parts.takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "?") ?: ""
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
