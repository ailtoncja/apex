package app.apex.source

import app.apex.model.ChannelDetails
import app.apex.model.Comment
import app.apex.model.Media
import app.apex.model.Resolved

/** Estado do motor de extração (yt-dlp no Windows). */
sealed interface ExtractorState {
    data object Checking : ExtractorState
    data object Ready : ExtractorState
    data class Installing(val message: String) : ExtractorState
    data class Missing(val reason: String) : ExtractorState
}

class ExtractionException(message: String) : Exception(message)

/**
 * A live pedida não está no ar (Twitch e Kick). Não é falha: a tela mostra o canal offline com o caminho para os vídeos dele, em vez de
 * um erro com "Tentar de novo". [channel] traz a foto e o nome quando a plataforma respondeu.
 */
class ChannelOfflineException(val name: String, val channel: app.apex.model.Channel? = null) : IllegalStateException("O canal $name está offline.")

/** Resolve os streams e dados pesados do YouTube. Cada plataforma entrega a sua implementação. */
interface Extractor {
    val state: kotlinx.coroutines.flow.StateFlow<ExtractorState>

    suspend fun ensureReady(): ExtractorState

    suspend fun resolve(media: Media): Resolved

    suspend fun comments(media: Media, limit: Int = 30, newest: Boolean = false): List<Comment>

    suspend fun channelDetails(channelId: String): ChannelDetails

    suspend fun updateEngine(): String
}
