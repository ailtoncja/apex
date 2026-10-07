package app.apex.state

import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform

/** Minúsculas e sem acentos: "Música" combina com "musica". */
fun normalizeText(text: String): String = buildString(text.length) {
    for (c in text.lowercase()) {
        append(
            when (c) {
                'á', 'à', 'â', 'ã', 'ä' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                'ú', 'ù', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                else -> c
            },
        )
    }
}

/** As palavras de uma busca, já normalizadas. */
fun searchWords(query: String): List<String> = normalizeText(query).split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }

/** `true` se TODAS as palavras aparecem em algum dos textos (título, canal, categoria…). Sem palavras, tudo combina. */
fun matchesWords(words: List<String>, vararg fields: String?): Boolean {
    if (words.isEmpty()) return true
    val normalized = fields.mapNotNull { it?.let(::normalizeText) }
    return words.all { w -> normalized.any { it.contains(w) } }
}

/** O que a busca achou entre as inscrições da pessoa: os canais (pelo nome) e os vídeos/lives (pelo título, canal ou categoria). */
class SubscriptionMatches(val channels: List<Channel>, val media: List<Media>) {
    val isEmpty: Boolean get() = channels.isEmpty() && media.isEmpty()
}

fun matchSubscriptions(query: String, subs: List<Channel>, items: List<Media>, platforms: Set<Platform> = emptySet()): SubscriptionMatches {
    val words = searchWords(query)
    if (words.isEmpty()) return SubscriptionMatches(emptyList(), emptyList())
    val channels = subs.channelsIn(platforms).filter { matchesWords(words, it.name, it.handle, it.id) }
    // Só vídeos de canais que a pessoa segue (o feed e as lives já vêm disso, mas o canal pode ter sido deixado de seguir depois).
    val followed = subs.map { it.key }.toSet()
    val media = items.mediaIn(platforms).filter { m ->
        (m.channel == null || m.channel.key in followed) && matchesWords(words, m.title, m.channel?.name, m.category)
    }.distinctBy { it.key }
    return SubscriptionMatches(channels, media)
}
