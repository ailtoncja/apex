package app.apex.source

import java.io.File
import java.nio.file.Files

/** Cookies do YouTube no formato Netscape, que o yt-dlp lê. */
object CookieJar {
    /**
     * Escreve os cookies de [header] num arquivo temporário (na pasta [dir], que é só do usuário). Quem chamou apaga o arquivo assim que o
     * yt-dlp termina: o login não fica em texto puro no disco.
     */
    fun write(header: String, dir: File = File(System.getProperty("java.io.tmpdir"))): File? {
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = CookieHygiene.cleanHeader(header).split(';').map { it.trim() }.mapNotNull { pair ->
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            // Tabulação e quebra de linha dentro de um cookie bagunçariam as linhas do arquivo.
            if (name.isEmpty() || (name + value).any { it == '\t' || it == '\r' || it == '\n' }) null
            else ".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
        }
        if (lines.isEmpty()) return null
        dir.mkdirs()
        val file = Files.createTempFile(dir.toPath(), "apex-ck", ".txt").toFile()
        file.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        return file
    }

    /** Apaga o arquivo de cookies; se o Windows ainda o estiver segurando, tenta de novo e, no pior caso, apaga ao sair. */
    fun discard(file: File) {
        repeat(10) {
            if (!file.exists() || file.delete()) return
            Thread.sleep(200)
        }
        file.deleteOnExit()
    }

    /** Sobras de uma execução que travou ou de um fechamento forçado do app. */
    fun deleteStale(dir: File = File(System.getProperty("java.io.tmpdir"))) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("apex-ck") && f.name.endsWith(".txt") }?.forEach { runCatching { it.delete() } }
    }
}
