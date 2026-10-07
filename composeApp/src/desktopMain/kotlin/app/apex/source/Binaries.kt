package app.apex.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Onde ficam o yt-dlp e o Deno (runtime JavaScript que o yt-dlp usa para o YouTube). */
class Binaries(val dir: File) {
    val ytdlp = File(dir, "yt-dlp.exe")
    val deno = File(dir, "deno.exe")

    val ready: Boolean get() = ytdlp.isFile

    /** Baixa o que faltar dos lançamentos oficiais no GitHub e só instala se o hash SHA-256 bater com o publicado junto. */
    suspend fun install(report: (String) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        if (!ytdlp.isFile) {
            report("Baixando o yt-dlp…")
            val sums = text(client, "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS")
            val expected = Checksums.fromSumsFile(sums, "yt-dlp.exe") ?: error("Não achei o hash oficial do yt-dlp.exe.")
            download(client, "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe", ytdlp, expected)
        }
        if (!deno.isFile) {
            report("Baixando o Deno…")
            val zipUrl = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip"
            val expected = Checksums.firstHash(text(client, "$zipUrl.sha256sum")) ?: error("Não achei o hash oficial do Deno.")
            val zip = File(dir, "deno.zip")
            download(client, zipUrl, zip, expected)
            ZipInputStream(zip.inputStream()).use { z ->
                generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith("deno.exe") }?.let {
                    deno.outputStream().use { out -> z.copyTo(out) }
                }
            }
            zip.delete()
        }
    }

    private fun text(client: HttpClient, url: String): String {
        val response = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Falha ao baixar $url (HTTP ${response.statusCode()})" }
        return response.body()
    }

    private fun download(client: HttpClient, url: String, target: File, expectedSha256: String) {
        val tmp = File(target.path + ".part")
        val response = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        check(response.statusCode() in 200..299) { "Falha ao baixar ${target.name} (HTTP ${response.statusCode()})" }
        // Programa que o Apex vai executar: se o hash não bater com o publicado, nada é instalado.
        if (!Checksums.sha256(tmp).equals(expectedSha256, ignoreCase = true)) {
            tmp.delete()
            error("O arquivo baixado (${target.name}) não bate com o hash oficial; a instalação foi cancelada.")
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Argumentos que apontam o runtime JavaScript: Deno do app, ou Node se existir no PATH. */
    fun jsRuntimeArgs(): List<String> = when {
        deno.isFile -> listOf("--js-runtimes", "deno:${deno.absolutePath}")
        else -> listOf("--js-runtimes", "node")
    }
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
