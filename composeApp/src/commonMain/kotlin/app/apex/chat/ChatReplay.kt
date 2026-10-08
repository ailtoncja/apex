package app.apex.chat

import app.apex.AppContainer
import app.apex.model.ChatMessage
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.VOD_PREFIX
import app.apex.model.ReplayMessage
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import app.apex.source.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Replay do chat: o chat de uma transmissão que já acabou (VOD da Twitch e da Kick, live encerrada do YouTube) rodando junto com o vídeo.
 * Cada mensagem tem o ponto do vídeo em que apareceu; o controlador mostra só as que já "aconteceram" até a posição atual do player e,
 * se a pessoa pular no vídeo, recomeça a leitura dali.
 */

/** O que uma leitura do chat gravado devolveu. */
class ReplayPage(
    val messages: List<ReplayMessage>,
    /** Até onde (ponto do vídeo, em ms) esta leitura chegou: a próxima começa aqui. */
    val nextFromMs: Long,
    /** Só o YouTube: o código que continua a leitura (sem ele a leitura recomeça de [nextFromMs]). */
    val nextToken: String? = null,
    /** Não há mais nada depois (final do chat gravado). */
    val end: Boolean = false,
)

/** De onde vêm as mensagens do chat gravado (uma por plataforma). */
interface ReplaySource {
    /** Lê mensagens a partir de [fromMs] do vídeo; [token] continua a leitura anterior (`null` = começar de [fromMs]). */
    suspend fun load(fromMs: Long, token: String?): ReplayPage
}

class ChatReplay(
    private val scope: CoroutineScope,
    private val source: ReplaySource,
    /** A posição atual do vídeo, em ms. */
    private val position: () -> Long,
    private val tickMs: Long = 400,
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** As mensagens que já apareceram até a posição do vídeo (as últimas [MAX_SHOWN]). */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("Carregando o chat…")
    val status: StateFlow<String> = _status.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        val buffer = ArrayList<ReplayMessage>() // por ponto do vídeo
        val ids = HashSet<String>()
        var loadedFrom = -1L // de onde o que está guardado começa
        var loadedTo = -1L // até onde a leitura já chegou
        var token: String? = null
        var ended = false
        var failures = 0

        while (scope.isActive) {
            val p = position().coerceAtLeast(0)
            // Pulo: para antes do que está guardado ou para bem à frente da leitura. Recomeça um pouco antes, para ter o que mostrar.
            if (loadedTo < 0 || p < loadedFrom || p > loadedTo + FAR_AHEAD_MS) {
                buffer.clear(); ids.clear(); token = null; ended = false
                loadedFrom = (p - CONTEXT_MS).coerceAtLeast(0)
                loadedTo = loadedFrom
            }
            // Mantém a leitura um pouco à frente do vídeo.
            var reads = 0
            while (!ended && loadedTo < p + AHEAD_MS && reads++ < MAX_READS_PER_TICK) {
                val page = try {
                    source.load(loadedTo, token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    _status.value = "Sem conexão com o chat gravado. Tentando de novo…"
                    delay(minOf(10_000L, 1_500L * failures))
                    break
                }
                failures = 0
                for (m in page.messages) if (ids.add(m.message.id)) buffer += m
                buffer.sortBy { it.offsetMs }
                token = page.nextToken
                // Sempre avança, mesmo que a leitura não tenha trazido nada novo.
                loadedTo = maxOf(page.nextFromMs, loadedTo + MIN_STEP_MS)
                ended = page.end
            }
            // Guarda só os últimos minutos (voltar um pouco ainda não precisa reler).
            val keepFrom = maxOf(loadedFrom, p - KEEP_MS)
            if (keepFrom > loadedFrom) {
                buffer.removeAll { it.offsetMs < keepFrom }
                // Os ids saem junto: a mensagem pode voltar se a leitura reler esse trecho.
                ids.retainAll(buffer.map { it.message.id }.toSet())
                loadedFrom = keepFrom
            }
            val shown = buffer.filter { it.offsetMs <= p }.takeLast(MAX_SHOWN).map { it.message }
            _messages.value = shown
            if (failures == 0) _status.value = if (shown.isEmpty() && ended && buffer.isEmpty()) "Sem mensagens neste trecho" else "Chat gravado"
            delay(tickMs)
        }
    }

    companion object {
        const val MAX_SHOWN = 300
        const val CONTEXT_MS = 20_000L
        const val AHEAD_MS = 25_000L
        const val FAR_AHEAD_MS = 30_000L
        const val KEEP_MS = 120_000L
        const val MIN_STEP_MS = 1_000L
        const val MAX_READS_PER_TICK = 4
    }
}

// ------------------------------------------------------------------------------------------------

/** VOD da Twitch: pede por segundo do vídeo e segue pela última mensagem recebida. */
class TwitchReplaySource(private val twitch: TwitchSource, private val videoId: String) : ReplaySource {
    override suspend fun load(fromMs: Long, token: String?): ReplayPage {
        val page = twitch.videoComments(videoId, fromMs / 1000)
        val last = page.messages.maxOfOrNull { it.offsetMs }
        return ReplayPage(page.messages, nextFromMs = last ?: (fromMs + 5_000), end = !page.hasNext)
    }
}

/** VOD da Kick: cada leitura traz os 5 segundos seguintes. */
class KickReplaySource(private val kick: KickSource, private val channelId: String, private val vodStartMs: Long) : ReplaySource {
    override suspend fun load(fromMs: Long, token: String?): ReplayPage =
        ReplayPage(kick.chatReplay(channelId, fromMs, vodStartMs), nextFromMs = fromMs + KICK_WINDOW_MS)

    private companion object {
        const val KICK_WINDOW_MS = 5_000L
    }
}

/** Live encerrada do YouTube: a primeira leitura pula para o ponto pedido; as seguintes seguem o código de cada página. */
class YouTubeReplaySource(private val youtube: YouTubeSource, private val seed: String) : ReplaySource {
    override suspend fun load(fromMs: Long, token: String?): ReplayPage {
        val page = if (token == null) youtube.chatReplay(seed, fromMs) else youtube.chatReplay(token)
        val last = page.messages.maxOfOrNull { it.offsetMs }
        return ReplayPage(page.messages, nextFromMs = last ?: (fromMs + 5_000), nextToken = page.next, end = page.next == null)
    }
}

/**
 * O chat gravado deste vídeo, se existir: VOD da Twitch ou da Kick, ou live encerrada do YouTube que guardou o chat.
 * Devolve `null` para lives (que têm chat ao vivo), clipes e vídeos comuns.
 */
suspend fun AppContainer.replaySourceFor(media: Media): ReplaySource? = when {
    media.isLive || media.isClip -> null
    media.platform == Platform.Twitch && media.isVod -> TwitchReplaySource(twitch, media.id.removePrefix(VOD_PREFIX))
    media.platform == Platform.Kick && media.isVod -> {
        val start = media.publishedAt
        val slug = media.channel?.id
        val channelId = if (start != null && slug != null) runCatching { kick.channelId(slug) }.getOrNull() else null
        if (start != null && channelId != null) KickReplaySource(kick, channelId, start) else null
    }
    media.platform == Platform.YouTube -> runCatching { youtube.chatReplaySeed(media.id) }.getOrNull()?.let { YouTubeReplaySource(youtube, it) }
    else -> null
}
