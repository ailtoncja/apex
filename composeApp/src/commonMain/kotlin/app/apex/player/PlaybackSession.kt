package app.apex.player

import app.apex.util.Timing
import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.model.SubtitleTrack
import app.apex.source.Extractor
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data class Ready(val resolved: Resolved) : LoadState
    data class Error(val message: String) : LoadState
}

/** O que está tocando agora: resolve o endereço, escolhe a qualidade, guarda histórico e encadeia o próximo. */
class PlaybackSession(
    private val scope: CoroutineScope,
    val player: PlayerController,
    private val extractor: Extractor,
    private val twitch: TwitchSource,
    private val kick: KickSource,
    private val data: UserData,
    /** A abertura rápida dos vídeos do YouTube (sem o yt-dlp); devolve `null` quando não dá e aí vale o yt-dlp. */
    private val fastYoutube: (suspend (Media) -> Resolved?)? = null,
) {
    private val _current = MutableStateFlow<Media?>(null)
    val current: StateFlow<Media?> = _current.asStateFlow()

    private val _load = MutableStateFlow<LoadState>(LoadState.Idle)
    val load: StateFlow<LoadState> = _load.asStateFlow()

    private val _quality = MutableStateFlow<Quality?>(null)
    val quality: StateFlow<Quality?> = _quality.asStateFlow()

    private val _subtitle = MutableStateFlow<SubtitleTrack?>(null)
    val subtitle: StateFlow<SubtitleTrack?> = _subtitle.asStateFlow()

    /** "A seguir": o que toca quando o vídeo acaba. */
    private val _upNext = MutableStateFlow<List<Media>>(emptyList())
    val upNext: StateFlow<List<Media>> = _upNext.asStateFlow()

    private var job: Job? = null
    private var played: List<Media> = emptyList()

    /** O preparo dos vídeos do YouTube (o mais lento da abertura) fica guardado e pode ser feito antes do clique. */
    private val resolved = ResolveCache(scope, { media -> resolveNow(media) })

    private suspend fun resolveNow(media: Media): Resolved = when (media.platform) {
        Platform.YouTube -> resolveYoutube(media)
        Platform.Twitch -> twitch.resolve(media)
        Platform.Kick -> kick.resolve(media)
    }

    /**
     * A abertura rápida do YouTube dá o começo do vídeo na hora, mas só entrega o primeiro minuto de cada arquivo: o resto vem dos endereços
     * completos do yt-dlp. Por isso o yt-dlp já parte junto com ela (e não depois) e o resultado dele vai junto, em [Resolved.pendingFull].
     * Quando a abertura rápida não serve (live, vídeo com login…), vale só o yt-dlp, como sempre foi.
     */
    private suspend fun resolveYoutube(media: Media): Resolved {
        val fast = fastYoutube ?: return extractor.resolve(media)
        val full = CompletableDeferred<Result<Resolved>>()
        scope.launch { full.complete(runCatching { extractor.resolve(media) }) }
        val lite = runCatching { fast(media) }.getOrNull()
        return lite?.copy(pendingFull = full) ?: full.await().getOrThrow()
    }

    /** O que passa pelo preparo guardado: tudo do YouTube e as lives da Twitch e da Kick (VOD e clipe têm endereço com validade própria). */
    private fun cacheable(media: Media) = media.platform == Platform.YouTube || media.isLive

    /** O endereço de [media] para quem toca fora desta sessão (o Multi): usa o preparo guardado quando há. */
    suspend fun resolve(media: Media): Resolved = if (cacheable(media)) resolved.get(media).await() else resolveNow(media)

    /** Prepara um vídeo ou live antes de a pessoa abrir (ao passar o mouse, ou o próximo da fila), para abrir na hora. */
    fun prefetch(media: Media) {
        if (cacheable(media)) resolved.prefetch(media)
    }

    /** Esquece os vídeos preparados (os endereços dependem do login do YouTube). */
    fun clearPrepared() = resolved.clear()

    init {
        scope.launch {
            player.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                val live = (_load.value as? LoadState.Ready)?.resolved?.isLive == true
                if (ended && !live && data.settings.value.autoplayNext) next()
            }
        }
        scope.launch {
            while (true) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val media = _current.value ?: return
        val s = player.state.value
        if (s.hasMedia && s.positionMs > 0) data.recordHistory(media, s.positionMs, s.durationMs)
    }

    fun open(media: Media, upNext: List<Media>? = null) {
        Timing.start("abrir ${media.platform} ${media.id.take(30)}")
        saveProgress()
        _current.value?.let { prev -> if (prev.key != media.key) played = (played + prev).takeLast(30) }
        if (upNext != null) _upNext.value = upNext
        _current.value = media
        _load.value = LoadState.Loading
        _quality.value = null
        _subtitle.value = null
        player.stop()
        job?.cancel()
        job = scope.launch {
            try {
                val resolved = if (cacheable(media)) this@PlaybackSession.resolved.get(media).await() else resolveNow(media)
                Timing.mark("endereço resolvido")
                if (resolved.qualities.isEmpty()) error("Nenhuma qualidade de vídeo disponível para este endereço.")
                _current.value = resolved.media
                _load.value = LoadState.Ready(resolved)
                val settings = data.settings.value
                // A posição salva tem de ser lida ANTES de registrar a abertura no histórico, senão ela já viria zerada.
                val start = startPosition(resolved, settings.rememberPosition)
                data.touchHistory(resolved.media)
                val chosen = pickDefaultQuality(resolved.qualities, settings.defaultQuality)
                startPlayback(resolved, chosen, start)
                // Velocidade só faz sentido em vídeo gravado; numa live ela atrasaria ou adiantaria o tempo real.
                player.setRate(if (resolved.isLive) 1f else settings.defaultRate)
                if (settings.subtitlesOnByDefault) {
                    resolved.subtitles.firstOrNull { it.lang.startsWith(settings.subtitleLang) && !it.auto }
                        ?.let { setSubtitleTrack(it) }
                }
                prepareNext(resolved)
                if (!resolved.complete) complete(resolved)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _load.value = LoadState.Error(e.message ?: "Não foi possível abrir este vídeo.")
            }
        }
    }

    /**
     * O vídeo abriu pela via rápida e já toca: o yt-dlp traz agora o que ela não tem (legendas, curtidas…) e isso entra na página sem mexer no
     * que está tocando (os endereços do vídeo continuam os da via rápida; o resto do arquivo, depois do primeiro minuto, a ponte do player
     * busca nos endereços completos do yt-dlp). Se o yt-dlp falhar, a via rápida sozinha só toca o primeiro minuto: o erro aparece já, como
     * aparecia antes da abertura rápida (por exemplo, o pedido de entrar na conta).
     */
    private fun complete(lite: Resolved) {
        scope.launch {
            val result = lite.pendingFull?.await() ?: runCatching { extractor.resolve(lite.media) }
            if (_current.value?.key != lite.media.key) return@launch
            val full = result.getOrNull()
            if (full == null) {
                val failure = result.exceptionOrNull()
                if (lite.rangeBridge && failure !is kotlinx.coroutines.CancellationException) {
                    player.stop()
                    _load.value = LoadState.Error(failure?.message ?: "Não foi possível abrir este vídeo.")
                }
                return@launch
            }
            val merged = lite.copy(
                description = full.description ?: lite.description,
                likes = full.likes ?: lite.likes,
                subscribers = full.subscribers ?: lite.subscribers,
                uploadDate = full.uploadDate ?: lite.uploadDate,
                chapters = full.chapters.ifEmpty { lite.chapters },
                subtitles = full.subtitles,
                tags = full.tags.ifEmpty { lite.tags },
                complete = true,
            )
            _load.value = LoadState.Ready(merged)
            val settings = data.settings.value
            if (settings.subtitlesOnByDefault && _subtitle.value == null) {
                merged.subtitles.firstOrNull { it.lang.startsWith(settings.subtitleLang) && !it.auto }?.let { setSubtitleTrack(it) }
            }
            if (lite.rangeBridge) ensureCovered(merged, full)
        }
    }

    /**
     * O arquivo que toca pela via rápida precisa ter o par dele no yt-dlp (mesmo formato e tamanho): é dele que a ponte continua depois do
     * primeiro minuto. Se não tiver (raro), troca agora para a qualidade mais próxima do yt-dlp, na posição em que está.
     */
    private fun ensureCovered(resolved: Resolved, full: Resolved) {
        val q = _quality.value ?: return
        val access = FullAccess(full.fileUrls, full.userAgent)
        if (access.match(q.videoUrl) != null && (q.audioUrl == null || access.match(q.audioUrl) != null)) return
        val target = full.qualities.minByOrNull { kotlin.math.abs(it.height - q.height) } ?: return
        Timing.mark("sem par no yt-dlp: troca para ${target.label}")
        val swapped = resolved.copy(qualities = full.qualities, userAgent = full.userAgent, rangeBridge = false, pendingFull = null)
        _load.value = LoadState.Ready(swapped)
        val rate = player.state.value.rate
        startPlayback(swapped, target, player.state.value.positionMs + (resolved.media.clipStartMs ?: 0))
        player.setRate(rate)
    }

    /** Com a reprodução automática ligada, prepara o próximo vídeo da fila enquanto este toca: quando acabar, o seguinte abre na hora. */
    private fun prepareNext(current: Resolved) {
        if (current.isLive || !data.settings.value.autoplayNext) return
        scope.launch {
            delay(NEXT_PREPARE_DELAY_MS) // deixa este vídeo começar bem antes de gastar rede e processador com o outro
            if (_current.value?.key != current.media.key) return@launch
            _upNext.value.firstOrNull { it.key != current.media.key }?.let { prefetch(it) }
        }
    }

    /** Onde começar: clipe do YouTube no início do trecho, clipe da Twitch/Kick do começo, vídeo e VOD de onde a pessoa parou. */
    private fun startPosition(resolved: Resolved, rememberPosition: Boolean): Long = when {
        resolved.isLive -> 0
        resolved.media.clipStartMs != null -> resolved.media.clipStartMs
        resolved.media.isClip || !rememberPosition -> 0
        else -> data.resumePosition(resolved.media)
    }


    private fun startPlayback(resolved: Resolved, quality: Quality, startMs: Long) {
        Timing.mark("player.play (${quality.label}, volume ${data.settings.value.volume})")
        _quality.value = quality
        player.setVolume(data.settings.value.volume)
        player.play(
            PlaySource(
                videoUrl = quality.videoUrl,
                audioUrl = quality.audioUrl,
                userAgent = resolved.userAgent,
                startMs = startMs,
                live = resolved.isLive,
                lowLatency = data.settings.value.lowLatencyLive,
                endMs = resolved.media.clipEndMs,
                videoHeight = quality.height,
                audioOnly = quality.audioOnly,
                bridgeRanges = resolved.rangeBridge,
                fullAccess = if (resolved.rangeBridge) resolved.pendingFull?.let { pending ->
                    scope.async { pending.await().getOrNull()?.let { FullAccess(it.fileUrls, it.userAgent) } }
                } else null,
            ),
        )
    }

    fun setQuality(q: Quality) {
        val resolved = (_load.value as? LoadState.Ready)?.resolved ?: return
        // No clipe do YouTube a posição do player é a do trecho; o arquivo conta desde o início do vídeo.
        val position = if (resolved.isLive) 0 else player.state.value.positionMs + (resolved.media.clipStartMs ?: 0)
        val rate = player.state.value.rate
        startPlayback(resolved, q, position)
        player.setRate(rate)
        _subtitle.value?.let { setSubtitleTrack(it) }
    }

    fun setSubtitleTrack(track: SubtitleTrack?) {
        _subtitle.value = track
        player.setSubtitle(track?.url)
    }

    fun setUpNext(list: List<Media>) {
        _upNext.value = list
    }

    fun next() {
        val queue = _upNext.value
        val target = queue.firstOrNull { it.key != _current.value?.key } ?: return
        open(target, queue.filter { it.key != target.key })
    }

    fun previous() {
        val prev = played.lastOrNull() ?: return
        played = played.dropLast(1)
        val cur = _current.value
        _upNext.value = if (cur != null) listOf(cur) + _upNext.value else _upNext.value
        open(prev)
        played = played.dropLast(1)
    }

    val hasPrevious: Boolean get() = played.isNotEmpty()

    private companion object {
        const val NEXT_PREPARE_DELAY_MS = 8_000L
    }

    fun close() {
        saveProgress()
        job?.cancel()
        player.stop()
        _current.value = null
        _load.value = LoadState.Idle
        _quality.value = null
        _subtitle.value = null
    }
}

/**
 * A qualidade que abre sozinha: a melhor até o limite (1080p quando o ajuste é "automática");
 * se todas passam do limite, a menor; se nenhuma diz a altura, a primeira. Nunca "somente áudio" (a tela ficaria preta com o som tocando):
 * ela só abre se for a única.
 */
internal fun pickDefaultQuality(list: List<Quality>, cap: Int): Quality {
    // Numa live também abre direto na melhor qualidade até o limite, e não no "Automático" da plataforma: o automático leva mais de 1 s a mais
    // para começar (medido na Twitch) e, no YouTube, começa em 144p e só depois sobe. O "Automático" continua no menu de qualidade.
    val limit = if (cap <= 0) 1080 else cap
    val video = list.filter { !it.audioOnly }
    if (video.isEmpty()) return list.first()
    video.filter { it.height in 1..limit }.maxByOrNull { it.height }?.let { return it }
    return video.filter { it.height > 0 }.minByOrNull { it.height } ?: video.first()
}
