package app.apex.source

/**
 * O YouTube responde "413" a pedidos com um cabeçalho de cookies grande demais (acima de uns 16 KB). O perfil do navegador usado no login
 * junta centenas de cookies `ST-…` (cerca de 1,5 KB cada, 100 KB no total) que não servem para autenticar. Aqui ficam só os que importam.
 */
object CookieHygiene {
    /** Cookies de login do Google/YouTube. */
    val AUTH = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
        "LOGIN_INFO", "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC", "SIDCC",
    )

    private const val MAX_HEADER_BYTES = 12_000

    /** `true` para o que só incha o cabeçalho: os `ST-…` e qualquer cookie enorme que não seja de login. */
    fun isNoise(name: String, valueLength: Int): Boolean = name !in AUTH && (name.startsWith("ST-") || valueLength > 800)

    /** Limpa um cabeçalho `Cookie` (`a=1; b=2`): tira o ruído e, se ainda passar do limite, os maiores que não são de login. */
    fun cleanHeader(header: String, maxBytes: Int = MAX_HEADER_BYTES): String {
        val pairs = header.split(';').map { it.trim() }
            .filter { it.contains('=') }
            .filterNot { isNoise(it.substringBefore('='), it.substringAfter('=').length) }
        val kept = pairs.toMutableList()
        var size = kept.sumOf { it.length + 2 }
        if (size > maxBytes) {
            for (p in pairs.filter { it.substringBefore('=') !in AUTH }.sortedByDescending { it.length }) {
                if (size <= maxBytes) break
                kept.remove(p)
                size -= p.length + 2
            }
        }
        return kept.joinToString("; ")
    }
}
