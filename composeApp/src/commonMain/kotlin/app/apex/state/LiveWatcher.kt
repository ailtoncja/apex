package app.apex.state

import app.apex.AppContainer
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.ChannelHit
import app.apex.util.ImagePrefetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Quem a pessoa segue está ao vivo agora? Confere sozinho, em segundo plano, e tudo no app (barra lateral, Início, Inscrições, Ao vivo)
 * lê daqui: quando um canal entra ao vivo ele aparece em segundos, sem precisar atualizar a tela, e o app avisa.
 *
 * Não existe aviso "em tempo real" gratuito das plataformas, então é conferência frequente: a da Twitch é uma consulta só para
 * até 35 canais (barata, a cada [TWITCH_MS]); a da Kick e a do YouTube são uma consulta por canal, com pausa maior quanto mais
 * canais a pessoa segue.
 */
class LiveWatcher(
    private val app: AppContainer,
    /** Como saber quem está ao vivo (chave do canal -> a live, ou `null`; canal ausente = a consulta falhou). Os testes trocam isto. */
    private val fetch: (suspend (Platform, List<Channel>) -> Map<String, Media?>)? = null,
) {
    private val parts: Map<Platform, MutableStateFlow<List<Media>>> = Platform.entries.associateWith { MutableStateFlow(emptyList()) }
    private val locks: Map<Platform, Mutex> = Platform.entries.associateWith { Mutex() }
    private val checkedAt = HashMap<Platform, Long>()

    /** As chaves que estavam ao vivo na última conferência de cada plataforma (`null` = ainda não conferiu: a primeira não avisa). */
    private val seen = HashMap<Platform, Set<String>>()

    /** Todas as lives dos canais que a pessoa segue (só de quem ainda segue), a de mais público primeiro. */
    val live: StateFlow<List<Media>> = combine(
        parts.getValue(Platform.Twitch), parts.getValue(Platform.Kick), parts.getValue(Platform.YouTube), app.data.subscriptions,
    ) { t, k, y, subs ->
        val followed = subs.map { it.key }.toSet()
        (t + k + y).filter { m -> m.channel?.key in followed }.sortedByDescending { it.viewCount ?: 0 }
    }.stateIn(app.scope, SharingStarted.Eagerly, emptyList())

    /** As chaves dos canais seguidos que estão ao vivo. */
    val liveKeys: StateFlow<Set<String>> = live.map { list -> list.mapNotNull { it.channel?.key }.toSet() }.stateIn(app.scope, SharingStarted.Eagerly, emptySet())

    @OptIn(FlowPreview::class)
    fun start() {
        for (p in Platform.entries) {
            app.scope.launch {
                delay(1_000)
                while (true) {
                    runCatching { check(p) }
                    delay(interval(p))
                }
            }
        }
        // Seguiu ou deixou de seguir alguém: confere na hora.
        app.scope.launch { app.data.subscriptions.drop(1).debounce(1_500).collect { refreshNow() } }
    }

    private fun interval(p: Platform): Long {
        val n = app.data.subscriptions.value.count { it.platform == p }
        return when (p) {
            Platform.Twitch -> TWITCH_MS
            Platform.Kick -> maxOf(KICK_MS, n * 900L)
            Platform.YouTube -> maxOf(YOUTUBE_MS, n * 2_500L)
        }
    }

    /** Confere agora (as plataformas pedidas; todas se nenhuma for dita). */
    fun refreshNow(vararg platforms: Platform) {
        val wanted = if (platforms.isEmpty()) Platform.entries else platforms.toList()
        app.scope.launch { wanted.map { p -> async { runCatching { check(p) } } }.awaitAll() }
    }

    /** Confere só o que está sem conferir há mais de [maxAgeMs] (ao abrir uma tela ou voltar para a janela). */
    fun refreshIfStale(maxAgeMs: Long = 8_000) {
        val now = System.currentTimeMillis()
        val stale = Platform.entries.filter { now - (checkedAt[it] ?: 0L) > maxAgeMs }
        if (stale.isNotEmpty()) refreshNow(*stale.toTypedArray())
    }

    internal suspend fun check(p: Platform) {
        val lock = locks.getValue(p)
        if (!lock.tryLock()) return // já tem uma conferência desta plataforma em andamento
        try {
            val channels = app.data.subscriptions.value.filter { it.platform == p }
            val previous = parts.getValue(p).value.associateBy { it.channel?.key }
            // chave do canal -> a live dele (ou null se não está ao vivo); canal ausente = a conferência dele falhou
            val fresh: Map<String, Media?> = fetch?.invoke(p, channels) ?: when (p) {
                Platform.Twitch -> twitchStatus(channels)
                Platform.Kick -> perChannel(channels) { ch -> app.kick.channel(ch.id)?.hit?.live }
                Platform.YouTube -> perChannel(channels.take(YOUTUBE_MAX)) { ch -> app.youtube.channelLive(ch.id)?.let { m -> m.copy(channel = m.channel ?: ch) } }
            }
            // Uma conferência que falha NUNCA tira o canal do ar: fica o que já se sabia (senão a live piscaria e o aviso repetiria).
            val now = channels.mapNotNull { ch -> if (ch.key in fresh) fresh[ch.key] else previous[ch.key] }
            checkedAt[p] = System.currentTimeMillis()
            val keys = now.mapNotNull { it.channel?.key }.toSet()
            val before = seen[p]
            seen[p] = keys
            parts.getValue(p).value = now
            ImagePrefetch.request(now)
            if (before != null) announce(now.filter { it.channel?.key !in before })
        } finally {
            lock.unlock()
        }
    }

    /** A Twitch responde por até 35 canais de uma vez; se não responder direito, devolve vazio (= tudo "falhou", nada muda). */
    private suspend fun twitchStatus(channels: List<Channel>): Map<String, Media?> {
        if (channels.isEmpty()) return emptyMap()
        val hits: List<ChannelHit> = app.twitch.channelsOrNull(channels.map { it.id }) ?: return emptyMap()
        val byLogin = hits.associateBy { it.channel.id.lowercase() }
        return channels.associate { ch -> ch.key to byLogin[ch.id.lowercase()]?.live }
    }

    /** Uma consulta por canal, poucas ao mesmo tempo; a que falhar fica de fora do resultado (e o canal mantém o estado anterior). */
    private suspend fun perChannel(channels: List<Channel>, fetch: suspend (Channel) -> Media?): Map<String, Media?> = coroutineScope {
        val out = HashMap<String, Media?>()
        for (chunk in channels.chunked(6)) {
            chunk.map { ch -> async { ch.key to runCatching { fetch(ch) } } }.awaitAll().forEach { (key, result) ->
                result.onSuccess { out[key] = it }
            }
        }
        out
    }

    /** Avisa quem acabou de entrar ao vivo (nada de avisar do canal que a pessoa já está assistindo). */
    private fun announce(started: List<Media>) {
        if (!app.data.settings.value.liveAlerts) return
        val watching = app.session.current.value?.channel?.key
        val names = started.filter { it.channel?.key != watching }.mapNotNull { it.channel?.name }.distinct()
        if (names.isEmpty()) return
        app.toast(liveAlertText(names))
    }

    companion object {
        const val TWITCH_MS = 15_000L
        const val KICK_MS = 25_000L
        const val YOUTUBE_MS = 90_000L
        private const val YOUTUBE_MAX = 40
    }
}

/** "Gaules está ao vivo", "A e B estão ao vivo", "A, B e mais 2 estão ao vivo". */
fun liveAlertText(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]} está ao vivo agora"
    2 -> "${names[0]} e ${names[1]} estão ao vivo agora"
    else -> "${names[0]}, ${names[1]} e mais ${names.size - 2} estão ao vivo agora"
}
