package app.apex.chat

import app.apex.model.ChatMessage
import app.apex.model.Media
import app.apex.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * O chat de outra plataforma do mesmo streamer, ao vivo agora. [chatId] é o que o chat daquela plataforma precisa: o login na Twitch, o slug na
 * Kick, o id do vídeo ao vivo no YouTube. [channelKey] é a chave do canal (`Plataforma:id`). [confirmed] = a pessoa juntou (ou vinculou à mão);
 * senão é só uma sugestão, achada pelo nome.
 */
data class LinkedChat(val platform: Platform, val channelKey: String, val name: String, val chatId: String, val channelId: String?, val confirmed: Boolean)

/** A chave de um canal para os vínculos: a plataforma e o id (o login da Twitch e o slug da Kick em minúsculas; o UC… do YouTube como é). */
fun channelKey(platform: Platform, id: String): String = "${platform.name}:${if (platform == Platform.YouTube) id else id.lowercase()}"

/** A chave do canal de uma live (o da Twitch e da Kick é o próprio id da live; o do YouTube, o canal dela). `null` se não se sabe. */
fun liveChannelKey(media: Media): String? = when (media.platform) {
    Platform.Twitch, Platform.Kick -> channelKey(media.platform, media.id)
    Platform.YouTube -> media.channel?.id?.takeIf { it.startsWith("UC") }?.let { channelKey(Platform.YouTube, it) }
}

/** Os nomes pelos quais o mesmo streamer costuma estar nas outras plataformas: o login, o slug, o @ do YouTube e o nome sem espaços. */
fun sameNameCandidates(media: Media): List<String> = listOfNotNull(
    media.id.takeIf { media.platform != Platform.YouTube },
    media.channel?.handle?.removePrefix("@"),
    media.channel?.name?.replace(" ", ""),
).map { it.lowercase() }.filter { NAME.matches(it) }.distinct()

private val NAME = Regex("[a-z0-9_.-]{2,40}")

/** Junta [a] e [b] no mesmo grupo de [links] (cada grupo é o mesmo streamer); grupos que passam a ter algo em comum viram um só. */
fun linkChannels(links: List<List<String>>, a: String, b: String): List<List<String>> {
    val touching = links.filter { a in it || b in it }
    val merged = (touching.flatten() + a + b).distinct()
    return links.filterNot { it in touching } + listOf(merged)
}

/** Tira [key] do grupo dele (o grupo que fica com um canal só some). */
fun unlinkChannel(links: List<List<String>>, key: String): List<List<String>> =
    links.map { g -> g - key }.filter { it.size > 1 }

/** A chave do par para a lista de recusados (a ordem não importa). */
fun rejectedPair(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")

/**
 * Procura os chats das outras plataformas do mesmo streamer de [media] que estão ao vivo agora: os vinculados ([links]) e, para as plataformas sem
 * vínculo, os de mesmo nome ([sameNameCandidates]) que não foram recusados ([rejected]). [live] diz, para uma plataforma e um nome/id, se há
 * live ali agora (e devolve o chat dela); cada consulta tem no máximo [timeoutMs].
 */
suspend fun findLinkedChats(
    media: Media,
    links: List<List<String>>,
    rejected: List<String>,
    live: suspend (Platform, String) -> LinkedChat?,
    timeoutMs: Long = 5_000,
): List<LinkedChat> = coroutineScope {
    val own = liveChannelKey(media) ?: return@coroutineScope emptyList()
    val group = links.firstOrNull { own in it }.orEmpty() - own
    val others = Platform.entries - media.platform
    others.map { platform ->
        async {
            val linked = group.firstOrNull { it.startsWith("${platform.name}:") }
            if (linked != null) {
                return@async withTimeoutOrNull(timeoutMs) { runCatching { live(platform, linked.substringAfter(':')) }.getOrNull() }?.copy(confirmed = true)
            }
            for (name in sameNameCandidates(media)) {
                val found = withTimeoutOrNull(timeoutMs) { runCatching { live(platform, name) }.getOrNull() } ?: continue
                if (rejectedPair(own, found.channelKey) in rejected) continue
                return@async found.copy(confirmed = false)
            }
            null
        }
    }.mapNotNull { it.await() }
}

/**
 * Vários chats (um por plataforma) numa coluna só: as mensagens entram na ordem em que chegam, cada uma marcada com a plataforma de onde veio.
 * Cada chat continua sendo lido pelo cliente dele ([ChatClient]); este só junta.
 */
class UnifiedChat(private val scope: CoroutineScope, val clients: List<ChatClient>) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        clients.forEach { client ->
            client.start()
            jobs += scope.launch {
                val seen = HashSet<String>()
                client.messages.collect { list ->
                    val fresh = list.filter { seen.add(it.id) }.map { if (it.platform == null) it.copy(platform = client.platform) else it }
                    if (fresh.isNotEmpty()) _messages.update { (it + fresh).takeLast(MAX) }
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        clients.forEach { it.stop() }
    }

    companion object {
        const val MAX = 400
    }
}
