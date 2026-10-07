package app.apex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.apex.cloud.CloudAccount
import app.apex.data.Account
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.SupportKind
import app.apex.nav.Navigator
import app.apex.nav.Route
import app.apex.state.ScreenStates
import app.apex.player.PlaybackSession
import app.apex.player.PlayerController
import app.apex.source.Extractor
import app.apex.source.InnerTube
import app.apex.source.KickSource
import app.apex.source.SearchFilters
import app.apex.source.TwitchSource
import app.apex.source.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Tudo que depende do sistema operacional. No Windows: abrir links, tela cheia, copiar, login embutido. */
interface SystemServices {
    /** Atualização do próprio app. */
    val updater: app.apex.update.Updater

    val fullscreen: StateFlow<Boolean>
    fun setFullscreen(on: Boolean)
    fun openUrl(url: String)
    fun copyText(text: String)

    /** Guarda um texto como arquivo na pasta Downloads e devolve o caminho (ou `null` se não deu). */
    fun saveDownload(fileName: String, text: String): String?

    /** Navegadores instalados que o Apex sabe usar para entrar nas contas. */
    val installedBrowsers: List<BrowserOption>

    /** O navegador padrão do sistema, se for um dos de [installedBrowsers]. */
    val defaultBrowserId: String?

    /**
     * Abre o navegador escolhido para o usuário entrar na plataforma e devolve a conta
     * (ou `null` se ele desistiu). Cancelar a coroutine fecha o que foi aberto.
     */
    suspend fun browserLogin(platform: Platform, browserId: String, onStatus: (String) -> Unit): Account?

    fun signOut(platform: Platform)

    /** Relê o login no navegador de onde a conta veio (cookies que o navegador trocou) e devolve a conta atualizada, ou `null`. */
    suspend fun refreshAccount(account: Account): Account?
}

data class BrowserOption(val id: String, val label: String, val note: String)

class UiState {
    var theater by mutableStateOf(false)
    var miniPlayerHidden by mutableStateOf(false)
    var typing by mutableStateOf(false)
    var chatVisible by mutableStateOf(true)
    var toast by mutableStateOf<String?>(null)

    /** Vídeo que o usuário quer colocar numa playlist (abre o diálogo). */
    var playlistTarget by mutableStateOf<Media?>(null)
}

class AppContainer(
    val scope: CoroutineScope,
    val system: SystemServices,
    val data: UserData,
    val extractor: Extractor,
    val player: PlayerController,
) {
    val tube = InnerTube()
    val youtube = YouTubeSource(tube)
    val twitch = TwitchSource()
    val kick = KickSource()

    val nav = Navigator()
    val ui = UiState()
    val session = PlaybackSession(scope, player, extractor, twitch, kick, data)
    val cloud = CloudAccount(scope, data, app.apex.source.Http.client)
    val screens = ScreenStates(this)

    init {
        scope.launch {
            data.accounts.collect { accounts ->
                tube.cookieHeader = accounts[Platform.YouTube.name]?.credential
                twitch.authToken = accounts[Platform.Twitch.name]?.credential
                kick.sessionToken = accounts[Platform.Kick.name]?.credential
            }
        }
        scope.launch { data.settings.collect { kick.hideMature = it.hideMature } }
        // Confere se saiu versão nova: depois de alguns segundos da abertura e a cada 6 horas.
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                runCatching { system.updater.check() }
                kotlinx.coroutines.delay(6 * 3_600_000L)
            }
        }
        // Subs e memberships mudam pouco: confere uma vez ao abrir e de tempos em tempos.
        scope.launch {
            kotlinx.coroutines.delay(15_000)
            while (true) {
                for (platform in listOf(Platform.Twitch, Platform.YouTube)) runCatching { refreshSupport(platform) }
                kotlinx.coroutines.delay(6 * 3_600_000L)
            }
        }
        // O navegador troca os cookies do YouTube de tempos em tempos; relemos o perfil dele para a sessão não cair.
        scope.launch {
            while (true) {
                runCatching { refreshAccountFromBrowser(Platform.YouTube) }
                kotlinx.coroutines.delay(ACCOUNT_REFRESH_MS)
            }
        }
        cloud.start()
        scope.launch {
            val result = runCatching { extractor.ensureReady() }.getOrNull()
            if (result is app.apex.source.ExtractorState.Missing) toast(result.reason)
        }
        scope.launch {
            var last: app.apex.source.ExtractorState? = null
            extractor.state.collect { s ->
                if (s is app.apex.source.ExtractorState.Installing) toast(s.message)
                last = s
            }
        }
    }

    fun openMedia(media: Media, upNext: List<Media>? = null) {
        ui.miniPlayerHidden = false
        session.open(media, upNext)
        nav.push(Route.Watch)
    }

    fun openChannel(channel: Channel) {
        // Os clipes da conta do YouTube vêm sem o endereço do canal; ele aparece quando o clipe é aberto.
        if (channel.id.isBlank()) return toast("Abra o clipe para ir ao canal.")
        nav.push(Route.ChannelPage(channel))
    }

    /** Se [text] é um link de vídeo, live, VOD ou clipe, abre direto e devolve `true`. */
    fun openLink(text: String): Boolean {
        when (val target = app.apex.source.parseLink(text) ?: return false) {
            is app.apex.source.LinkTarget.Play -> openMedia(target.media)
            is app.apex.source.LinkTarget.YouTubeClip -> {
                toast("Abrindo o clipe…")
                scope.launch {
                    val clip = runCatching { youtube.clip(target.clipId) }.getOrNull()
                    if (clip == null) toast("Não consegui abrir este clipe (apagado ou privado).") else openMedia(clip)
                }
            }
        }
        return true
    }

    fun search(query: String, filters: SearchFilters = SearchFilters()) {
        if (query.isBlank()) return
        if (openLink(query)) return
        data.addSearchHistory(query)
        nav.push(Route.Search(query.trim(), filters))
    }

    fun toast(message: String) {
        ui.toast = message
    }

    /** Renova a conta ligada ao navegador. Devolve `true` se os cookies mudaram. */
    suspend fun refreshAccountFromBrowser(platform: Platform): Boolean {
        val current = data.account(platform) ?: return false
        val fresh = system.refreshAccount(current) ?: return false
        if (fresh.credential == current.credential) return false
        data.setAccount(fresh, platform)
        // Já vale para o próximo pedido, sem esperar o observador das contas.
        if (platform == Platform.YouTube) tube.cookieHeader = fresh.credential
        return true
    }

    /** Completa nome e foto da conta logada. */
    fun refreshAccountProfile(platform: Platform) {
        val account = data.account(platform) ?: return
        scope.launch {
            val profile = runCatching {
                when (platform) {
                    Platform.YouTube -> youtube.accountInfo()
                    Platform.Twitch -> twitch.currentUser()
                    Platform.Kick -> kick.currentUser()
                }
            }.getOrNull() ?: return@launch
            data.setAccount(account.copy(displayName = profile.first.ifBlank { account.displayName }, avatarUrl = profile.second ?: account.avatarUrl), platform)
        }
    }

    fun signOut(platform: Platform) {
        system.signOut(platform)
        data.setAccount(null, platform)
    }

    /** Resultado de [importFollows]: quantos canais vieram e, se couber, um aviso. */
    class ImportResult(val count: Int, val note: String? = null) {
        val summary: String get() = "$count canais importados" + (note?.let { " ($it)" } ?: "")
    }

    /** Traz para o Apex os canais que a conta segue (e, na Twitch, em que é inscrita) na plataforma. */
    suspend fun importFollows(platform: Platform): ImportResult {
        var note: String? = null
        val channels = when (platform) {
            Platform.YouTube -> try {
                youtube.subscribedChannels()
            } catch (e: app.apex.source.SessionExpiredException) {
                // O navegador pode já ter cookies mais novos: renova e tenta de novo uma vez.
                if (!refreshAccountFromBrowser(Platform.YouTube)) throw e
                youtube.subscribedChannels()
            }
            Platform.Twitch -> {
                val follows = twitch.followedChannels()
                if (!follows.complete) {
                    note = "a Twitch só deixa ler 200 dos canais que você segue; se segue mais, os do meio da lista não vêm"
                }
                // Quem você é sub também entra, mesmo sem seguir.
                val subs = runCatching { twitch.subscribedChannels() }.getOrDefault(emptyList())
                (follows.channels + subs).map { it.channel }.distinctBy { it.id }
            }
            Platform.Kick -> kick.followedChannels()
        }
        data.addSubscriptions(channels)
        // Quem a pessoa paga (sub ou membro) fica separado dos demais canais.
        runCatching { refreshSupport(platform) }
        return ImportResult(channels.size, note)
    }

    /**
     * Atualiza quais canais a pessoa apoia pagando: subs da Twitch e memberships do YouTube (a Kick não informa).
     * Se a consulta falhar, o que já estava marcado continua como estava. Devolve quantos canais apoiados há depois.
     */
    suspend fun refreshSupport(platform: Platform): Int {
        when (platform) {
            Platform.Twitch -> {
                if (data.account(Platform.Twitch) == null) return 0
                val subs = twitch.subscribedChannels()
                data.setSupport(Platform.Twitch, subs.map { it.channel to SupportKind.Sub })
            }
            Platform.YouTube -> {
                if (!tube.loggedIn) return 0
                val known = data.subscriptions.value.filter { it.platform == Platform.YouTube }
                val members = youtube.activeMemberships(known)
                data.setSupport(Platform.YouTube, members.map { it to SupportKind.Member })
            }
            Platform.Kick -> return 0
        }
        return data.subscriptions.value.count { it.platform == platform && it.support != null }
    }

    /** Segue/deixa de seguir no app e, se houver conta do YouTube logada, também na conta. */
    fun setSubscribed(channel: Channel, on: Boolean) {
        val already = data.isSubscribed(channel)
        if (on != already) data.toggleSubscription(channel)
        if (channel.platform == Platform.YouTube && tube.loggedIn) {
            scope.launch { runCatching { youtube.setSubscribed(channel.id, on) } }
        }
    }

    /** Curtir (1), não curtir (-1) ou limpar (0). Também envia ao YouTube quando logado. */
    fun react(media: Media, value: Int) {
        data.setReaction(media, value)
        // Curtir um clipe não deve curtir o vídeo inteiro de onde ele saiu: fica só no app.
        if (media.platform == Platform.YouTube && tube.loggedIn && !media.isClip) {
            scope.launch { runCatching { youtube.rate(media.videoId, value) } }
        }
    }
}

private const val ACCOUNT_REFRESH_MS = 2 * 60_000L

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer não foi fornecido") }
