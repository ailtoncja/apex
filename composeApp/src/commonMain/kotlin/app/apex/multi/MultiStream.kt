package app.apex.multi

import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.player.LoadState
import app.apex.player.PlaySource
import app.apex.player.PlayerController
import app.apex.player.pickDefaultQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uma das lives do Multi: toca num player só dela. */
class Tile(val media: Media, val player: PlayerController) {
    val key: String get() = media.key
    internal val _load = MutableStateFlow<LoadState>(LoadState.Idle)
    val load: StateFlow<LoadState> = _load.asStateFlow()
    internal var job: Job? = null
    /** A fila desta live: abrir, parar e soltar o player acontecem um de cada vez, na ordem pedida, e fora da thread da interface. */
    internal val lane = Mutex()
    /** O que a plataforma respondeu (título, espectadores); `null` enquanto não abriu. */
    internal val _resolved = MutableStateFlow<Resolved?>(null)
    val resolved: StateFlow<Resolved?> = _resolved.asStateFlow()
}

/**
 * Várias lives ao mesmo tempo, de qualquer plataforma misturada (como o multitwitch, o multikick e o multiyoutube): cada uma toca no seu
 * player, as escolhidas têm som (de início só a primeira; as outras ficam mudas), um chat de cada vez aparece ao lado. A lista fica guardada: fechar e abrir o app volta
 * com as mesmas lives. Os players só rodam enquanto a tela do Multi está aberta ([start]/[stop]).
 */
class MultiStream(
    private val scope: CoroutineScope,
    private val data: UserData,
    private val newPlayer: () -> PlayerController,
    private val resolve: suspend (Media) -> Resolved,
    /** Chamado quando o Multi começa a tocar (o app para o player principal, para não tocarem dois sons). */
    private val beforeStart: () -> Unit = {},
) {
    private val _tiles = MutableStateFlow<List<Tile>>(emptyList())
    val tiles: StateFlow<List<Tile>> = _tiles.asStateFlow()

    /** As lives com som (as outras ficam mudas); vazio = todas mudas. */
    private val _audio = MutableStateFlow<Set<String>>(emptySet())
    val audio: StateFlow<Set<String>> = _audio.asStateFlow()

    /** A live cujo chat está aberto. */
    private val _chat = MutableStateFlow<String?>(null)
    val chat: StateFlow<String?> = _chat.asStateFlow()

    /** A live em destaque (grande, com as outras numa coluna ao lado); `null` = grade com todas do mesmo tamanho. */
    private val _focus = MutableStateFlow<String?>(null)
    val focus: StateFlow<String?> = _focus.asStateFlow()

    /** Os players estão rodando (a tela do Multi está aberta). */
    @Volatile private var active = false
    private var restored = false

    val medias: List<Media> get() = _tiles.value.map { it.media }
    val isFull: Boolean get() = _tiles.value.size >= MAX

    /** Põe [media] no Multi. `false` quando já está lá ou quando não cabe mais. Tocando, abre na hora. */
    fun add(media: Media): Boolean {
        restoreOnce()
        if (_tiles.value.any { it.key == media.key } || isFull) return false
        val tile = Tile(media, newPlayer())
        _tiles.update { it + tile }
        if (_audio.value.isEmpty()) _audio.value = setOf(tile.key)
        if (_chat.value == null) _chat.value = tile.key
        app.apex.util.Timing.mark("multi: entrou ${tile.key} (som em ${_audio.value}, ${_tiles.value.size} lives)")
        save()
        if (active) {
            play(tile)
            // As outras mudam de tamanho de grade: a qualidade delas acompanha, sem reabrir (só na próxima abertura).
        }
        return true
    }

    fun remove(key: String) {
        val tile = _tiles.value.firstOrNull { it.key == key } ?: return
        _tiles.update { it - tile }
        tile.job?.cancel()
        // Parar e soltar o VLC demora (e trava a interface se for feito nela): vai para outra thread, na fila da própria live.
        scope.launch(Dispatchers.IO) { tile.lane.withLock { tile.player.stop(); tile.player.release() } }
        val remaining = _tiles.value
        // Saiu a única com som: a primeira que sobrou herda o som.
        if (key in _audio.value) setAudio((_audio.value - key).ifEmpty { setOfNotNull(remaining.firstOrNull()?.key) })
        if (_chat.value == key) _chat.value = remaining.firstOrNull()?.key
        if (_focus.value == key) _focus.value = null
        save()
    }

    fun clear() {
        _tiles.value.toList().forEach { remove(it.key) }
    }

    /** As lives com som passam a ser [keys] (vazio = todas mudas). */
    fun setAudio(keys: Set<String>) {
        app.apex.util.Timing.mark("multi: som em $keys")
        _audio.value = keys
        _tiles.value.forEach { it.player.setMuted(it.key !in keys) }
    }

    /** Liga ou desliga o som de [key], sem mexer nas outras: dá para ouvir mais de uma ao mesmo tempo. */
    fun toggleAudio(key: String) = setAudio(if (key in _audio.value) _audio.value - key else _audio.value + key)

    fun setChat(key: String) {
        if (_tiles.value.any { it.key == key }) _chat.value = key
    }

    /** Destaca [key]; destacar de novo a mesma volta à grade. */
    fun toggleFocus(key: String) {
        _focus.value = if (_focus.value == key) null else key.takeIf { k -> _tiles.value.any { it.key == k } }
    }

    /** A tela do Multi abriu: toca tudo (e para o player principal). */
    fun start() {
        restoreOnce()
        if (active) return
        active = true
        app.apex.util.Timing.mark("multi: começou com ${_tiles.value.size} lives (som em ${_audio.value})")
        beforeStart()
        _tiles.value.forEach { play(it) }
    }

    /** A tela do Multi fechou: para tudo; os players ficam, para voltar rápido. */
    fun stop() {
        if (!active) return
        active = false
        _tiles.value.forEach { tile ->
            tile.job?.cancel()
            tile._load.value = LoadState.Idle
            scope.launch(Dispatchers.IO) { tile.lane.withLock { tile.player.stop() } }
        }
    }

    /** As lives guardadas da última vez voltam na primeira vez que o Multi é usado. */
    private fun restoreOnce() {
        if (restored) return
        restored = true
        val saved = data.multiStreams.value.take(MAX)
        if (saved.isEmpty()) return
        _tiles.value = saved.map { Tile(it, newPlayer()) }
        _audio.value = setOfNotNull(_tiles.value.firstOrNull()?.key)
        _chat.value = _tiles.value.firstOrNull()?.key
    }

    private fun save() = data.setMultiStreams(medias)

    private fun play(tile: Tile) {
        tile.job?.cancel()
        tile._load.value = LoadState.Loading
        tile.job = scope.launch {
            // Na fila da própria live: um "parar" pedido antes termina primeiro.
            tile.lane.withLock {
                try {
                    val resolved = resolve(tile.media)
                    tile._resolved.value = resolved
                    if (resolved.qualities.isEmpty()) error("Nenhuma qualidade disponível.")
                    val quality = pickTileQuality(resolved.qualities, _tiles.value.size)
                    val settings = data.settings.value
                    tile.player.setVolume(settings.volume)
                    tile.player.setMuted(tile.key !in _audio.value)
                    tile.player.play(
                        PlaySource(
                            videoUrl = quality.videoUrl, audioUrl = quality.audioUrl, userAgent = resolved.userAgent,
                            live = resolved.isLive, lowLatency = settings.lowLatencyLive, videoHeight = quality.height,
                            bridgeRanges = resolved.rangeBridge,
                        ),
                    )
                    tile._load.value = LoadState.Ready(resolved)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tile._load.value = LoadState.Error(e.message ?: "Não foi possível abrir esta live.")
                }
            }
        }
    }

    companion object {
        const val MAX = 9

        /** Quanto mais lives na tela, menor a qualidade de cada uma: a tela não tem espaço para mais e o processador agradece. */
        internal fun tileQualityCap(count: Int): Int = when {
            count <= 1 -> 1080
            count == 2 -> 720
            count <= 4 -> 480
            else -> 360
        }

        internal fun pickTileQuality(list: List<Quality>, count: Int): Quality = pickDefaultQuality(list, tileQualityCap(count))

        /** A grade: colunas × linhas para [count] lives (1, 2 lado a lado, 2×2, 3×2, 3×3). */
        fun gridFor(count: Int): Pair<Int, Int> = when {
            count <= 1 -> 1 to 1
            count == 2 -> 2 to 1
            count <= 4 -> 2 to 2
            count <= 6 -> 3 to 2
            else -> 3 to 3
        }
    }
}
