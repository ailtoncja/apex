package app.apex.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** O que tocar. Vídeo e áudio podem vir separados (YouTube em alta qualidade). */
data class PlaySource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val userAgent: String? = null,
    val startMs: Long = 0,
    val live: Boolean = false,
    /** Numa live, começar mais perto do "ao vivo" (menos atraso, com um buffer menor). */
    val lowLatency: Boolean = true,
    /**
     * Quando preenchido, só o trecho [startMs]–[endMs] do arquivo é o vídeo (clipe do YouTube): a posição e a duração que o player
     * mostra são do trecho, e ele termina em [endMs].
     */
    val endMs: Long? = null,
)

data class PlayerState(
    val hasMedia: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val rate: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
)

/** Contrato do player: a interface é compartilhada, cada plataforma entrega o seu motor (libVLC no Windows). */
interface PlayerController {
    val state: StateFlow<PlayerState>

    fun play(source: PlaySource)
    fun togglePause()
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)
    fun setRate(rate: Float)

    /** Carrega uma legenda externa; `null` desliga. */
    fun setSubtitle(url: String?)

    fun stop()
    fun release()

    @Composable
    fun Video(modifier: Modifier)
}
