package app.apex.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Deferred
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
    /** A altura do vídeo desta qualidade, quando se sabe (0 = não se sabe): o player usa para desenhar o quadro sem esticar. */
    val videoHeight: Int = 0,
    /** A qualidade escolhida é "somente áudio": não há imagem para esperar. */
    val audioOnly: Boolean = false,
    /**
     * Os endereços só aceitam pedidos de um trecho com começo e fim (os da abertura rápida do YouTube): o player passa por uma ponte local
     * que busca o arquivo em pedaços ([RangeProxy] no Windows).
     */
    val bridgeRanges: Boolean = false,
    /**
     * Com [bridgeRanges]: os endereços completos do yt-dlp, quando chegarem. Os da abertura rápida só entregam o primeiro minuto de cada arquivo;
     * depois dele a ponte busca o resto aqui (o `null` no fim quer dizer que o yt-dlp não conseguiu).
     */
    val fullAccess: Deferred<FullAccess?>? = null,
)

/** Os endereços completos dos arquivos de um vídeo do YouTube (do yt-dlp) e o User-Agent que eles pedem. */
data class FullAccess(val urls: List<String>, val userAgent: String?) {
    /**
     * O endereço completo do mesmo arquivo que [fast] (a abertura rápida): mesmo formato (`itag`) e mesmo tamanho (`clen`), o que garante os
     * mesmos bytes; `null` se não houver, e aí a ponte não pode continuar depois do primeiro minuto.
     */
    fun match(fast: String): String? {
        val itag = param(fast, "itag") ?: return null
        val clen = param(fast, "clen")
        return urls.firstOrNull { param(it, "itag") == itag && (clen == null || param(it, "clen") == clen) }
    }

    private fun param(url: String, name: String): String? =
        Regex("""[?&/]$name[=/](\d+)""").find(url)?.groupValues?.get(1)
}

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
    /** Numa live: quanto o vídeo ficou para trás desde que abriu (pausas e travadas somam); 0 = em dia ou não é live. */
    val behindLiveMs: Long = 0,
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

    /** Numa live: abre de novo no ponto mais novo da transmissão, descartando o atraso que se acumulou (pausa, travadas). */
    fun jumpToLive() {}

    /** Carrega uma legenda externa; `null` desliga. */
    fun setSubtitle(url: String?)

    fun stop()
    fun release()

    @Composable
    fun Video(modifier: Modifier)
}
