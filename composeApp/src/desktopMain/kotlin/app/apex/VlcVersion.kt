package app.apex

/** O VLC mais antigo sem as falhas de segurança conhecidas (as versões antes desta abrem arquivos de vídeo malfeitos de um jeito explorável). */
const val VLC_MIN_SAFE_VERSION = "3.0.21"

/** Os números do começo da versão que o libVLC informa (`"3.0.20 Vetinari"` → `[3, 0, 20]`); `null` se não começa com um número. */
internal fun vlcVersionNumbers(version: String): List<Int>? {
    val numbers = Regex("""^\s*(\d+(?:\.\d+)*)""").find(version)?.groupValues?.get(1) ?: return null
    return numbers.split('.').map { it.toIntOrNull() ?: return null }
}

/** O VLC [version] é mais antigo que [minimum]. Versão que não dá para ler não conta como antiga (não avisa à toa). */
fun isVlcOutdated(version: String, minimum: String = VLC_MIN_SAFE_VERSION): Boolean {
    val have = vlcVersionNumbers(version) ?: return false
    val need = vlcVersionNumbers(minimum) ?: return false
    for (i in 0 until maxOf(have.size, need.size)) {
        val a = have.getOrElse(i) { 0 }
        val b = need.getOrElse(i) { 0 }
        if (a != b) return a < b
    }
    return false
}

/** A versão do libVLC carregado (`"3.0.21 Vetinari"`), ou `null` se ele não carregou. */
internal fun loadedVlcVersion(): String? = runCatching { uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_get_version() }.getOrNull()
