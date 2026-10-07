package app.apex.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Senhas com Argon2id (parâmetros mínimos recomendados pela OWASP). */
object Passwords {
    private const val MEMORY_KB = 19_456
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val HASH_BYTES = 32
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val unb64 = Base64.getDecoder()

    /** Hash de mentira para gastar o mesmo tempo quando o e-mail não existe (não revela quem tem conta). */
    private val dummy: String by lazy { hash("senha-que-ninguem-usa") }

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val out = derive(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(out)}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val target = stored ?: dummy
        val parts = target.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val salt = runCatching { unb64.decode(parts[4]) }.getOrNull() ?: return false
        val expected = runCatching { unb64.decode(parts[5]) }.getOrNull() ?: return false
        val actual = derive(password, salt, m, t, p, expected.size)
        return MessageDigest.isEqual(expected, actual) && stored != null
    }

    private fun derive(password: String, salt: ByteArray, memoryKb: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().also { it.init(params) }
        val out = ByteArray(length)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }
}

object Tokens {
    private val random = SecureRandom()

    /** Token aleatório de 256 bits, seguro para ir em URL. */
    fun random(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** O banco guarda só o hash: quem ler o banco não consegue usar o token. */
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

class JwtService(secret: String) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()

    fun issue(userId: UUID, now: Instant = Instant.now()): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(userId.toString())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(ACCESS_TTL_SEC)))
            .sign(algorithm)

    companion object {
        const val ISSUER = "apex"
        const val AUDIENCE = "apex-app"
        const val ACCESS_TTL_SEC = 15 * 60L
    }
}
