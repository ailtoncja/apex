package app.apex.data

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Base64

/** Guarda cada coleção como um arquivo JSON em `%APPDATA%\Apex`. Os logins ficam cifrados com a conta do Windows (DPAPI). */
class FileStore(val dir: File) : KeyValueStore {
    init {
        dir.mkdirs()
    }

    private fun file(name: String) = File(dir, "$name.json")

    override fun read(name: String): String? {
        // Editores do Windows costumam gravar um marcador (BOM) no começo do arquivo.
        val text = runCatching { file(name).takeIf { it.isFile }?.readText()?.removePrefix("﻿") }.getOrNull() ?: return null
        return if (name in SECRET && text.startsWith(PREFIX)) unprotect(text.removePrefix(PREFIX)) else text
    }

    override fun write(name: String, text: String) {
        val protectedText = if (name in SECRET) protect(text) else null
        val content = if (protectedText != null) PREFIX + protectedText else text
        val target = file(name)
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    /** `null` se a cifragem do Windows falhar (aí o arquivo fica sem o prefixo e continua legível pelo app). */
    private fun protect(text: String): String? =
        runCatching { Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(text.toByteArray())) }.getOrNull()

    private fun unprotect(b64: String): String? =
        runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(b64))) }.getOrNull()

    companion object {
        private val SECRET = setOf("accounts", "cloud")
        private const val PREFIX = "dpapi:"

        fun defaultDir(): File {
            System.getenv("APEX_DATA")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return File(base, "Apex")
        }
    }
}
