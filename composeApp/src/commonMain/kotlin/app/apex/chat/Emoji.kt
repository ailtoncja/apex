package app.apex.chat

/** Um emoji do Unicode para escrever no chat: o próprio caractere, o grupo dele (Rostos, Comida…), o nome em português e palavras para busca. */
data class EmojiEntry(val emoji: String, val group: String, val name: String, val keywords: List<String>)

/** O arquivo `emoji.tsv` que vai junto com o app (gerado do Unicode e dos nomes em português do CLDR). */
expect fun loadEmojiTsv(): String

/**
 * Os emojis do Unicode (todos, sem as variações de tom de pele), na ordem e nos grupos do teclado de emoji. Lidos uma vez, na primeira vez
 * que o seletor abre.
 */
object EmojiCatalog {
    val all: List<EmojiEntry> by lazy { parseEmojiTsv(runCatching { loadEmojiTsv() }.getOrDefault("")) }
    val groups: List<String> by lazy { all.map { it.group }.distinct() }
}

/** Lê o `emoji.tsv`: grupo, emoji, nome em português, nome em inglês e palavras-chave (separadas por `|`). */
fun parseEmojiTsv(text: String): List<EmojiEntry> = text.lineSequence().mapNotNull { line ->
    val cols = line.split('\t')
    if (cols.size < 4 || cols[1].isBlank()) return@mapNotNull null
    EmojiEntry(cols[1], cols[0], cols[2].ifBlank { cols[3] }, listOf(cols[3]) + cols.getOrNull(4).orEmpty().split('|').filter { it.isNotBlank() })
}.toList()

/**
 * Os emojis de [list] em que cada palavra de [query] aparece no nome ou nas palavras-chave (em português ou inglês), sem ligar para acentos nem
 * maiúsculas: "bandeira brasil" acha "bandeira: Brasil".
 */
fun searchEmoji(list: List<EmojiEntry>, query: String): List<EmojiEntry> {
    val words = foldAccents(query).split(' ', ',', ':').filter { it.isNotBlank() }
    if (words.isEmpty()) return list
    return list.filter { e ->
        val text = foldAccents(e.name + " " + e.keywords.joinToString(" "))
        words.all { text.contains(it) }
    }
}

private val ACCENTS = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'é' to 'e', 'ê' to 'e', 'è' to 'e', 'ë' to 'e', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
    'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o', 'ò' to 'o', 'ú' to 'u', 'û' to 'u', 'ü' to 'u', 'ù' to 'u', 'ç' to 'c', 'ñ' to 'n',
)

/** Minúsculas e sem acento ("Coração" → "coracao"), para a busca achar com ou sem acento. */
internal fun foldAccents(text: String): String = buildString(text.length) { text.lowercase().forEach { append(ACCENTS[it] ?: it) } }
