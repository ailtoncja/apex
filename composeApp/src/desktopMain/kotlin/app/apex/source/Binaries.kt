package app.apex.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Onde ficam o yt-dlp e o Deno (runtime JavaScript que o yt-dlp usa para o YouTube).
 * Os dois vêm dos lançamentos oficiais no GitHub; os endereços e a chave só mudam nos testes.
 */
class Binaries(
    val dir: File,
    private val ytdlpRepo: String = YTDLP_REPO,
    private val denoRepo: String = DENO_REPO,
    private val ytdlpKey: String = YtDlpSignature.PUBLIC_KEY,
    private val ytdlpFingerprint: String = YtDlpSignature.FINGERPRINT,
) {
    val ytdlp = File(dir, "yt-dlp.exe")
    val deno = File(dir, "deno.exe")

    /** Hash do zip do Deno que está instalado (para não baixar de novo a mesma versão). */
    private val denoStamp = File(dir, "deno.zip.sha256")

    val ready: Boolean get() = ytdlp.isFile

    // Segue os redirecionamentos do GitHub (os arquivos ficam em outro servidor), mas nunca de https para http.
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    private val noRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build()
    private val lock = Mutex()

    /**
     * Baixa o que faltar. O yt-dlp só é instalado se a lista `SHA2-256SUMS` tiver a assinatura OpenPGP da chave do yt-dlp
     * e o hash do arquivo bater com ela; o Deno, se o hash bater com o `.sha256sum` publicado junto.
     */
    suspend fun install(report: (String) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            prepareDir()
            if (!ytdlp.isFile) {
                report("Baixando o yt-dlp…")
                installYtDlp(latestTag(ytdlpRepo))
            }
            if (!deno.isFile) {
                report("Baixando o Deno…")
                installDeno(latestTag(denoRepo))
            }
        }
    }

    /** Atualiza o yt-dlp e o Deno para a versão mais nova, com a mesma conferência da instalação. Devolve o que aconteceu. */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            prepareDir()
            val yt = updateYtDlpLocked()
            val dn = runCatching { updateDenoLocked() }.getOrElse { "Não consegui atualizar o Deno: ${it.message}" }
            "$yt $dn"
        }
    }

    /** Baixa e instala a versão mais nova do yt-dlp (no lugar do `yt-dlp -U`, que não confere a assinatura). */
    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) { lock.withLock { prepareDir(); updateYtDlpLocked() } }

    /** Baixa e instala a versão mais nova do Deno, se for diferente da instalada. */
    suspend fun updateDeno(): String = withContext(Dispatchers.IO) { lock.withLock { prepareDir(); updateDenoLocked() } }

    private fun updateYtDlpLocked(): String {
        val tag = latestTag(ytdlpRepo)
        return if (installYtDlp(tag)) "yt-dlp atualizado para $tag." else "O yt-dlp já está na versão mais nova ($tag)."
    }

    private fun updateDenoLocked(): String {
        val tag = latestTag(denoRepo)
        return if (installDeno(tag)) "Deno atualizado para $tag." else "O Deno já está na versão mais nova ($tag)."
    }

    /** Instala o yt-dlp da versão [tag]; `false` se o instalado já for exatamente esse. */
    private fun installYtDlp(tag: String): Boolean {
        val base = "$ytdlpRepo/releases/download/$tag"
        val sums = bytes("$base/SHA2-256SUMS", 256 * 1024)
        val sig = bytes("$base/SHA2-256SUMS.sig", 64 * 1024)
        // Programa que o Apex vai executar: sem a assinatura do yt-dlp na lista de hashes, nada é instalado.
        check(YtDlpSignature.verify(sums, sig, ytdlpKey, ytdlpFingerprint)) { "A assinatura do yt-dlp ($tag) não confere; nada foi instalado." }
        val expected = Checksums.fromSumsFile(sums.toString(Charsets.UTF_8), "yt-dlp.exe") ?: error("Não achei o hash oficial do yt-dlp.exe.")
        if (ytdlp.isFile && Checksums.sha256(ytdlp).equals(expected, ignoreCase = true)) return false
        download("$base/yt-dlp.exe", ytdlp, expected)
        return true
    }

    /** Instala o Deno da versão [tag]; `false` se o instalado já for exatamente esse. */
    private fun installDeno(tag: String): Boolean {
        val zipUrl = "$denoRepo/releases/download/$tag/$DENO_ZIP"
        val expected = Checksums.firstHash(bytes("$zipUrl.sha256sum", 16 * 1024).toString(Charsets.UTF_8)) ?: error("Não achei o hash oficial do Deno.")
        if (deno.isFile && denoStamp.isFile && denoStamp.readText().trim().equals(expected, ignoreCase = true)) return false
        val zip = File(dir, "deno.zip")
        val tmp = File(dir, "deno.exe.part")
        try {
            download(zipUrl, zip, expected)
            var found = false
            ZipInputStream(zip.inputStream()).use { z ->
                generateSequence { z.nextEntry }.firstOrNull { it.name == "deno.exe" || it.name.endsWith("/deno.exe") }?.let {
                    tmp.outputStream().use { out -> z.copyTo(out) }
                    found = true
                }
            }
            check(found && tmp.length() > 0) { "O zip do Deno não tem o deno.exe." }
            replace(tmp, deno)
            denoStamp.writeText(expected.lowercase())
        } finally {
            zip.delete()
            tmp.delete()
        }
        return true
    }

    /** A versão mais nova publicada (o GitHub redireciona `releases/latest` para `releases/tag/<versão>`). */
    private fun latestTag(repo: String): String {
        val request = HttpRequest.newBuilder(URI.create("$repo/releases/latest")).method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(30)).build()
        val response = noRedirect.send(request, HttpResponse.BodyHandlers.discarding())
        val location = response.headers().firstValue("location").orElse("")
        check(response.statusCode() in 300..399 && "/releases/tag/" in location) { "Não consegui descobrir a versão mais nova (HTTP ${response.statusCode()})." }
        val tag = location.substringAfter("/releases/tag/").substringBefore('?').substringBefore('#')
        check(TAG.matches(tag)) { "Versão com nome inesperado: ${tag.take(40)}" }
        return tag
    }

    private fun bytes(url: String, limit: Int): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "Falha ao baixar ${url.substringAfterLast('/')} (HTTP ${response.statusCode()})" }
        check(response.body().size <= limit) { "Arquivo grande demais: ${url.substringAfterLast('/')}" }
        return response.body()
    }

    private fun download(url: String, target: File, expectedSha256: String) {
        val tmp = File(target.path + ".part")
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        check(response.statusCode() in 200..299) { tmp.delete(); "Falha ao baixar ${target.name} (HTTP ${response.statusCode()})" }
        // Programa que o Apex vai executar: se o hash não bater com o publicado, nada é instalado.
        if (!Checksums.sha256(tmp).equals(expectedSha256, ignoreCase = true)) {
            tmp.delete()
            error("O arquivo baixado (${target.name}) não bate com o hash oficial; a instalação foi cancelada.")
        }
        replace(tmp, target)
    }

    /** Põe [tmp] no lugar de [target]. Um .exe em uso não pode ser sobrescrito no Windows, mas pode ser renomeado: o velho sai do caminho. */
    private fun replace(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            val old = File(dir, "${target.name}.${System.nanoTime()}.old")
            if (target.exists() && !target.renameTo(old)) {
                tmp.delete()
                throw e
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Cria a pasta e apaga sobras: downloads pela metade e programas velhos que estavam em uso na última troca. */
    private fun prepareDir() {
        dir.mkdirs()
        dir.listFiles()?.forEach { f -> if (f.isFile && (f.name.endsWith(".part") || f.name.endsWith(".old"))) f.delete() }
    }

    /** Argumentos que apontam o runtime JavaScript: Deno do app, ou Node se existir no PATH. */
    fun jsRuntimeArgs(): List<String> = when {
        deno.isFile -> listOf("--js-runtimes", "deno:${deno.absolutePath}")
        else -> listOf("--js-runtimes", "node")
    }

    companion object {
        const val YTDLP_REPO = "https://github.com/yt-dlp/yt-dlp"
        const val DENO_REPO = "https://github.com/denoland/deno"
        const val DENO_ZIP = "deno-x86_64-pc-windows-msvc.zip"
        private val TAG = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/** Confere a assinatura OpenPGP que o yt-dlp publica junto de cada versão (`SHA2-256SUMS.sig`, sobre o `SHA2-256SUMS`). */
object YtDlpSignature {
    /** Impressão digital da chave que assina os lançamentos do yt-dlp (Simon Sawicki, "yt-dlp signing key"). */
    const val FINGERPRINT = "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"

    /** A chave pública, copiada de `public.key` no repositório yt-dlp/yt-dlp (fixa no app: um servidor adulterado não consegue trocá-la). */
    const val PUBLIC_KEY = """-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBGP78C4BEAD0rF9zjGPAt0thlt5C1ebzccAVX7Nb1v+eqQjk+WEZdTETVCg3
WAM5ngArlHdm/fZqzUgO+pAYrB60GKeg7ffUDf+S0XFKEZdeRLYeAaqqKhSibVal
DjvOBOztu3W607HLETQAqA7wTPuIt2WqmpL60NIcyr27LxqmgdN3mNvZ2iLO+bP0
nKR/C+PgE9H4ytywDa12zMx6PmZCnVOOOu6XZEFmdUxxdQ9fFDqd9LcBKY2LDOcS
Yo1saY0YWiZWHtzVoZu1kOzjnS5Fjq/yBHJLImDH7pNxHm7s/PnaurpmQFtDFruk
t+2lhDnpKUmGr/I/3IHqH/X+9nPoS4uiqQ5HpblB8BK+4WfpaiEg75LnvuOPfZIP
KYyXa/0A7QojMwgOrD88ozT+VCkKkkJ+ijXZ7gHNjmcBaUdKK7fDIEOYI63Lyc6Q
WkGQTigFffSUXWHDCO9aXNhP3ejqFWgGMtCUsrbkcJkWuWY7q5ARy/05HbSM3K4D
U9eqtnxmiV1WQ8nXuI9JgJQRvh5PTkny5LtxqzcmqvWO9TjHBbrs14BPEO9fcXxK
L/CFBbzXDSvvAgArdqqlMoncQ/yicTlfL6qzJ8EKFiqW14QMTdAn6SuuZTodXCTi
InwoT7WjjuFPKKdvfH1GP4bnqdzTnzLxCSDIEtfyfPsIX+9GI7Jkk/zZjQARAQAB
tDdTaW1vbiBTYXdpY2tpICh5dC1kbHAgc2lnbmluZyBrZXkpIDxjb250YWN0QGdy
dWI0ay54eXo+iQJOBBMBCgA4FiEErAy75oSNaoc0ZK9OV89lkztadYEFAmP78C4C
GwMFCwkIBwIGFQoJCAsCBBYCAwECHgECF4AACgkQV89lkztadYEVqQ//cW7TxhXg
7Xbh2EZQzXml0egn6j8QaV9KzGragMiShrlvTO2zXfLXqyizrFP4AspgjSn/4NrI
8mluom+Yi+qr7DXT4BjQqIM9y3AjwZPdywe912Lxcw52NNoPZCm24I9T7ySc8lmR
FQvZC0w4H/VTNj/2lgJ1dwMflpwvNRiWa5YzcFGlCUeDIPskLx9++AJE+xwU3LYm
jQQsPBqpHHiTBEJzMLl+rfd9Fg4N+QNzpFkTDW3EPerLuvJniSBBwZthqxeAtw4M
UiAXh6JvCc2hJkKCoygRfM281MeolvmsGNyQm+axlB0vyldiPP6BnaRgZlx+l6MU
cPqgHblb7RW5j9lfr6OYL7SceBIHNv0CFrt1OnkGo/tVMwcs8LH3Ae4a7UJlIceL
V54aRxSsZU7w4iX+PB79BWkEsQzwKrUuJVOeL4UDwWajp75OFaUqbS/slDDVXvK5
OIeuth3mA/adjdvgjPxhRQjA3l69rRWIJDrqBSHldmRsnX6cvXTDy8wSXZgy51lP
m4IVLHnCy9m4SaGGoAsfTZS0cC9FgjUIyTyrq9M67wOMpUxnuB0aRZgJE1DsI23E
qdvcSNVlO+39xM/KPWUEh6b83wMn88QeW+DCVGWACQq5N3YdPnAJa50617fGbY6I
gXIoRHXkDqe23PZ/jURYCv0sjVtjPoVC+bg=
=bJkn
-----END PGP PUBLIC KEY BLOCK-----
"""

    /**
     * `true` só se [signature] (assinatura OpenPGP separada, binária ou em texto ASCII) for de [data], feita pela chave
     * de [publicKey] cuja impressão digital é [fingerprint], com hash SHA-256 ou mais forte.
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: String = PUBLIC_KEY, fingerprint: String = FINGERPRINT): Boolean = runCatching {
        val rings = PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicKey.byteInputStream()), BcKeyFingerprintCalculator())
        val ring = rings.keyRings.asSequence().firstOrNull { hex(it.publicKey.fingerprint).equals(fingerprint, ignoreCase = true) }
            ?: return@runCatching false
        val sigs = BcPGPObjectFactory(PGPUtil.getDecoderStream(signature.inputStream())).nextObject() as? PGPSignatureList
            ?: return@runCatching false
        sigs.any { sig ->
            val key = ring.getPublicKey(sig.keyID) ?: return@any false
            if (sig.signatureType != PGPSignature.BINARY_DOCUMENT && sig.signatureType != PGPSignature.CANONICAL_TEXT_DOCUMENT) return@any false
            if (sig.hashAlgorithm !in STRONG_HASHES || key.hasRevocation()) return@any false
            sig.init(BcPGPContentVerifierBuilderProvider(), key)
            sig.update(data)
            sig.verify()
        }
    }.getOrDefault(false)

    private val STRONG_HASHES = setOf(
        HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512,
        HashAlgorithmTags.SHA3_256, HashAlgorithmTags.SHA3_512,
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/** Hashes SHA-256 dos programas baixados. */
object Checksums {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Bytes(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Lê um arquivo `SHA2-256SUMS` (linhas `hash  nome` ou `hash *nome`) e devolve o hash do arquivo [name]. */
    fun fromSumsFile(text: String, name: String): String? = text.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val hash = line.substringBefore(' ').takeIf { HASH.matches(it) } ?: return@mapNotNull null
            val file = line.substringAfter(' ').trim().removePrefix("*")
            hash.takeIf { file == name }
        }
        .firstOrNull()

    /** O primeiro hash de 64 dígitos hexadecimais de um texto (o Deno publica o hash dentro de um texto do PowerShell). */
    fun firstHash(text: String): String? = Regex("\\b[0-9a-fA-F]{64}\\b").find(text)?.value

    private val HASH = Regex("[0-9a-fA-F]{64}")
}
