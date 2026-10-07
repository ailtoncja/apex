package app.apex.util

import app.apex.model.Channel
import app.apex.model.Media

/**
 * Baixa as imagens de uma lista assim que ela chega, antes de as telas pedirem: sem isso, cada miniatura só começa a baixar quando o cartão
 * aparece na rolagem (e o Kick e a Twitch levam quase 1 segundo para entregar cada uma pela primeira vez).
 * Quem sabe como carregar imagens (o Windows) coloca a função em [enqueue].
 */
object ImagePrefetch {
    @Volatile
    var enqueue: (List<String>) -> Unit = {}

    /** Endereços das imagens que as telas vão mostrar para estes itens (miniatura do vídeo e avatar do canal). */
    fun urlsOf(items: List<Any?>): List<String> = items.flatMap { item ->
        when (item) {
            is Media -> listOfNotNull(item.thumbnailUrl, item.channel?.avatarUrl)
            is Channel -> listOfNotNull(item.avatarUrl)
            else -> emptyList()
        }
    }.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()

    fun request(items: List<Any?>) {
        val urls = urlsOf(items)
        if (urls.isNotEmpty()) runCatching { enqueue(urls) }
    }
}
