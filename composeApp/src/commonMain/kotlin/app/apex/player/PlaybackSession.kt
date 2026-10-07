package app.apex.player

import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.model.SubtitleTrack
import app.apex.source.Extractor
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
                val resolved = when (media.platform) {
                    Platform.YouTube -> extractor.resolve(media)
                    Platform.Twitch -> twitch.resolve(media)
                    Platform.Kick -> kick.resolve(media)
                }
                _current.value = resolved.media
                _load.value = LoadState.Ready(resolved)
                data.recordHistory(resolved.media, 0, 0)
                val settings = data.settings.value
                val start = if (resolved.isLive || !settings.rememberPosition) 0 else data.resumePosition(resolved.media)
                val chosen = pickDefault(resolved.qualities, settings.defaultQuality, resolved.isLive)
                startPlayback(resolved, chosen, start)
                player.setRate(settings.defaultRate)
                if (settings.subtitlesOnByDefault) {
                    resolved.subtitles.firstOrNull { it.lang.startsWith(settings.subtitleLang) && !it.auto }
                        ?.let { setSubtitleTrack(it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _load.value = LoadState.Error(e.message ?: "Não foi possível abrir este vídeo.")
            }
        }
    }

    private fun pickDefault(list: List<Quality>, cap: Int, live: Boolean): Quality {
        if (live) return list.first()
        val limit = if (cap <= 0) 1080 else cap
        return list.firstOrNull { it.height in 1..limit } ?: list.last()
    }

    private fun startPlayback(resolved: Resolved, quality: Quality, startMs: Long) {
        _quality.value = quality
        player.setVolume(data.settings.value.volume)
        player.play(
            PlaySource(
                videoUrl = quality.videoUrl,
                audioUrl = quality.audioUrl,
                userAgent = resolved.userAgent,
                startMs = startMs,
                live = resolved.isLive,
            ),
        )
    }

    fun setQuality(q: Quality) {
        val resolved = (_load.value as? LoadState.Ready)?.resolved ?: return
        val position = if (resolved.isLive) 0 else player.state.value.positionMs
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
