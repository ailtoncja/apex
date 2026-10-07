package app.apex.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream

/** Onde ficam o yt-dlp e o Deno (runtime JavaScript que o yt-dlp usa para o YouTube). */
class Binaries(val dir: File) {
    val ytdlp = File(dir, "yt-dlp.exe")
    val deno = File(dir, "deno.exe")

    val ready: Boolean get() = ytdlp.isFile

    /** Baixa o que faltar dos lançamentos oficiais no GitHub. */
    suspend fun install(report: (String) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        if (!ytdlp.isFile) {
            report("Baixando o yt-dlp…")
            download(client, "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe", ytdlp)
        }
        if (!deno.isFile) {
            report("Baixando o Deno…")
            val zip = File(dir, "deno.zip")
            download(client, "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip", zip)
            ZipInputStream(zip.inputStream()).use { z ->
                generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith("deno.exe") }?.let {
                    deno.outputStream().use { out -> z.copyTo(out) }
                }
            }
            zip.delete()
        }
    }

    private fun download(client: HttpClient, url: String, target: File) {
        val tmp = File(target.path + ".part")
        val response = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        check(response.statusCode() in 200..299) { "Falha ao baixar ${target.name} (HTTP ${response.statusCode()})" }
        tmp.renameTo(target)
    }

    /** Argumentos que apontam o runtime JavaScript: Deno do app, ou Node se existir no PATH. */
    fun jsRuntimeArgs(): List<String> = when {
        deno.isFile -> listOf("--js-runtimes", "deno:${deno.absolutePath}")
        else -> listOf("--js-runtimes", "node")
    }
}
