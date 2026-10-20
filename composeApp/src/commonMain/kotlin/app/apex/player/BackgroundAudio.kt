package app.apex.player

import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Resolved
import app.apex.util.Timing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Música de fundo: um segundo player, só de som, que toca enquanto a pessoa assiste a outro vídeo (ou navega). Tem fila própria e volume
 * próprio (guardado nos ajustes), e não mexe no player principal: o vídeo da página continua com o som dele e os dois se misturam.
 *
 * Toca só o áudio quando a plataforma oferece (YouTube: o arquivo de áudio; Twitch e Kick: a qualidade "Somente áudio"); senão, a menor
 * qualidade com o vídeo desligado no VLC (ver [backgroundSource]). O player só existe enquanto há música: fechar solta ele.
 */
class BackgroundAudio(
    private val scope: CoroutineScope,
    private val data: UserData,
    private val newPlayer: () -> PlayerController,
    private val resolve: suspend (Media) -> Resolved,
    private val message: (String) -> Unit,
) {
    private val _queue = MutableStateFlow<List<Media>>(emptyList())
    /** A lista toda, com a que toca agora (na posição [index]). */
    val queue: StateFlow<List<Media>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _state = MutableStateFlow(PlayerState())
    /** O estado do player de fundo (tocando, posição, volume…); vazio sem música. */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _loop = MutableStateFlow(false)
    /** Repetir a lista: depois da última, volta para a primeira (com uma só, ela repete). */
    val loop: StateFlow<Boolean> = _loop.asStateFlow()

    val current: Media? get() = _queue.value.getOrNull(_index.value)
    val active: Boolean get() = _index.value >= 0

    private var player: PlayerController? = null

    /** O item da fila que está aberto no player (-1 = nenhum): depois de tirar o que tocava, o seguinte fica escolhido mas parado. */
    private var loadedIndex = -1
    private var watchers: List<Job> = emptyList()
    private var job: Job? = null

    /** Toca [media] agora (a fila vira só ela), a partir de [startMs] (passar o vídeo da página para o fundo continua de onde estava). */
    fun play(media: Media, startMs: Long = 0) {
        _queue.value = listOf(media)
        start(0, startMs)
    }

    /** Toca a lista toda, a partir de [startAt] (uma playlist). */
    fun playAll(list: List<Media>, startAt: Int = 0) {
        val unique = list.distinctBy { it.key }
        if (unique.isEmpty()) return
        _queue.value = unique
        start(startAt.coerceIn(0, unique.lastIndex), 0)
    }

    /** Põe [media] no fim da fila; sem nada tocando, já toca. */
    fun enqueue(media: Media) {
        if (!active) return play(media)
        if (_queue.value.any { it.key == media.key }) return message("Já está na fila da música de fundo")
        _queue.value = _queue.value + media
        message("Na fila da música de fundo")
    }

    fun next() {
        val i = _index.value + 1
        when {
            i < _queue.value.size -> start(i, 0)
            _loop.value && _queue.value.isNotEmpty() -> start(0, 0)
        }
    }

    fun previous() {
        // Como nos tocadores de música: no meio da faixa, volta ao começo dela; no começo, vai para a anterior.
        if (_state.value.positionMs > PREVIOUS_RESTARTS_MS || _index.value <= 0) player?.seekTo(0) else start(_index.value - 1, 0)
    }

    fun jump(i: Int) {
        if (i in _queue.value.indices) start(i, 0)
    }

    fun remove(key: String) {
        val list = _queue.value
        val i = list.indexOfFirst { it.key == key }
        if (i < 0) return
        if (i == _index.value) {
            if (list.size == 1) return close()
            _queue.value = list.filterIndexed { j, _ -> j != i }
            start(i.coerceAtMost(_queue.value.lastIndex), 0)
        } else {
            _queue.value = list.filterIndexed { j, _ -> j != i }
            if (i < _index.value) _index.value -= 1
        }
    }

    fun togglePause() {
        // O item escolhido ainda não foi aberto (o que tocava saiu para ser assistido): o play começa ele.
        if (active && loadedIndex != _index.value) return start(_index.value, 0)
        val p = player ?: return
        if (_state.value.ended) p.resume() else p.togglePause()
    }

    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, NORMAL_VOLUME)
        data.updateSettings { it.copy(backgroundVolume = v) }
        player?.setVolume(v)
        _state.value = _state.value.copy(volume = v)
    }

    fun setLoop(on: Boolean) {
        _loop.value = on
    }

    /**
     * Tira [key] do fundo para assistir: devolve o vídeo e o ponto do arquivo em que o som estava (só o que tocava tem ponto; 0 nos outros e
     * na live). Se era o que tocava, o som para na hora; sobrando outros na fila, o seguinte fica escolhido e parado (não toca junto com o
     * vídeo que a pessoa foi ver), até ela apertar o play da barra.
     */
    fun takeOut(key: String): Pair<Media, Long>? {
        val list = _queue.value
        val i = list.indexOfFirst { it.key == key }
        if (i < 0) return null
        val media = list[i]
        if (i != _index.value) {
            _queue.value = list.filterIndexed { j, _ -> j != i }
            if (i < _index.value) _index.value -= 1
            return media to 0L
        }
        val s = _state.value
        val position = if (media.isLive || loadedIndex != i) 0L else s.positionMs + if (media.clipEndMs != null) media.clipStartMs ?: 0 else 0
        val rest = list.filterIndexed { j, _ -> j != i }
        if (rest.isEmpty()) {
            close()
        } else {
            job?.cancel()
            player?.stop()
            loadedIndex = -1
            _queue.value = rest
            _index.value = i.coerceAtMost(rest.lastIndex)
            _state.value = PlayerState(hasMedia = true, volume = data.settings.value.backgroundVolume)
        }
        return media to position
    }

    /** Para a música e solta o player. */
    fun close() {
        job?.cancel()
        watchers.forEach { it.cancel() }
        watchers = emptyList()
        player?.release()
        player = null
        _queue.value = emptyList()
        _index.value = -1
        loadedIndex = -1
        _state.value = PlayerState()
    }

    private fun start(i: Int, startMs: Long) {
        val media = _queue.value.getOrNull(i) ?: return
        _index.value = i
        loadedIndex = i
        job?.cancel()
        _state.value = PlayerState(hasMedia = true, loading = true, volume = data.settings.value.backgroundVolume)
        val p = ensurePlayer()
        p.stop()
        job = scope.launch {
            val resolved = try {
                resolve(media)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message("Música de fundo: não deu para tocar “${media.title}”")
                Timing.mark("fundo: falhou ${media.key}: ${e.message}")
                if (_index.value == i && i + 1 < _queue.value.size) start(i + 1, 0) else _state.value = _state.value.copy(loading = false, error = e.message)
                return@launch
            }
            // Um link chega só com o endereço: a barra passa a mostrar o título e a capa de verdade.
            _queue.value = _queue.value.map { if (it.key == media.key) resolved.media else it }
            val full = resolved.pendingFull?.let { pending ->
                scope.async { pending.await().getOrNull()?.let { FullAccess(it.fileUrls, it.userAgent) } }
            }
            p.setVolume(data.settings.value.backgroundVolume)
            p.play(backgroundSource(resolved, startMs, full))
            Timing.mark("fundo: tocando ${media.key}")
        }
    }

    private fun ensurePlayer(): PlayerController = player ?: newPlayer().also { p ->
        player = p
        watchers = listOf(
            scope.launch { p.state.collect { s -> if (player === p) _state.value = s } },
            scope.launch { p.state.map { it.ended }.distinctUntilChanged().collect { ended -> if (ended && player === p && loadedIndex == _index.value) next() } },
        )
    }

    private companion object {
        const val PREVIOUS_RESTARTS_MS = 3_000L
    }
}

/**
 * O que o player de fundo toca para [resolved]: só o áudio, quando dá. Twitch e Kick oferecem a qualidade "Somente áudio"; o YouTube tem o
 * arquivo de áudio separado (pela ponte da abertura rápida, quando ela foi usada, com o endereço completo [full] do yt-dlp para depois do
 * primeiro minuto). Sem nada disso (uma live do YouTube), a menor qualidade, com o VLC sem decodificar o vídeo ([PlaySource.audioOnly]).
 */
internal fun backgroundSource(resolved: Resolved, startMs: Long, full: kotlinx.coroutines.Deferred<FullAccess?>? = null): PlaySource {
    val media = resolved.media
    // Num clipe a posição já vem contada desde o começo do vídeo; sem posição, o clipe começa no começo dele.
    val start = if (startMs > 0) startMs else media.clipStartMs ?: 0
    val qualities = resolved.qualities
    val audioOnly = qualities.firstOrNull { it.audioOnly }
    val separate = qualities.filter { !it.audioOnly && it.audioUrl != null }.minByOrNull { it.height }
    val chosen = audioOnly ?: separate ?: qualities.filter { !it.audioOnly }.minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE } ?: qualities.first()
    val url = if (audioOnly == null && separate != null) separate.audioUrl!! else chosen.videoUrl
    return PlaySource(
        videoUrl = url,
        userAgent = resolved.userAgent,
        startMs = start,
        live = resolved.isLive,
        // O fundo prefere não travar a ficar perto do "ao vivo".
        lowLatency = false,
        endMs = media.clipEndMs,
        audioOnly = true,
        // Os endereços da abertura rápida (todos eles) só tocam pela ponte.
        bridgeRanges = resolved.rangeBridge,
        fullAccess = if (resolved.rangeBridge) full else null,
    )
}
