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

    /** Tela cheia do player: só o vídeo, como o botão de tela cheia da Twitch e do YouTube. */
    var playerFullscreen by mutableStateOf(false)

    /** F11: a janela sem barra de título e sem a barra de tarefas, com o app inteiro visível. */
    var windowFullscreen by mutableStateOf(false)
    var miniPlayerHidden by mutableStateOf(false)

    /** O chat ao lado das lives do Multi. */
    var multiChat by mutableStateOf(true)

    /** Sobe a cada clique em qualquer lugar da janela: as listas de sugestões fecham quando o clique cai fora delas. */
    var pressTick by androidx.compose.runtime.mutableIntStateOf(0)
    var typing by mutableStateOf(false)

    /** Pedidos de foco feitos pelos atalhos (cada aumento leva o cursor ao campo): "/" e Ctrl+K na pesquisa, Enter no chat da live. */
    var focusSearchTick by androidx.compose.runtime.mutableIntStateOf(0)
    var focusChatTick by androidx.compose.runtime.mutableIntStateOf(0)

    /** A lista de atalhos (tecla "?" ou F1). */
    var shortcutsOpen by mutableStateOf(false)
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
    /** Um player novo para cada live do Multi (no Windows, um VlcPlayerController a mais). */
    val newPlayer: () -> PlayerController,
    /** O cliente de rede do YouTube (os testes põem um de mentira). */
    youtubeHttp: io.ktor.client.HttpClient = app.apex.source.Http.client,
    /** Abrir os vídeos do YouTube pela via rápida (sem o yt-dlp). Os testes desligam, para não falarem com o YouTube de verdade. */
    fastYoutube: Boolean = true,
    /** O preparo das lives do Multi; `null` = o mesmo da sessão (os testes põem um de mentira). */
    tileResolve: (suspend (Media) -> app.apex.model.Resolved)? = null,
) {
    val tube = InnerTube(youtubeHttp)
    val youtube = YouTubeSource(tube)
    val twitch = TwitchSource()
    val kick = KickSource()

    val nav = Navigator()
    val ui = UiState()
    val session = PlaybackSession(scope, player, extractor, twitch, kick, data, if (fastYoutube) app.apex.source.YouTubeFast(tube)::resolve else null)
    /** Várias lives ao mesmo tempo. Ao começar, para o player principal: dois sons ao mesmo tempo não dá. */
    val multi = app.apex.multi.MultiStream(scope, data, newPlayer, tileResolve ?: { session.resolve(it) }, beforeStart = {
        if (session.current.value != null) session.close()
        ui.miniPlayerHidden = true
    })
    val cloud = CloudAccount(scope, data, app.apex.source.Http.client)
    /** Quem a pessoa segue está ao vivo: conferido sozinho, em segundo plano (antes das telas, que leem daqui). */
    val liveWatcher = app.apex.state.LiveWatcher(this)
    val screens = ScreenStates(this)

    /** Quem está logado no YouTube (para saber quando trocou de conta; o cookie em si muda toda hora). */
    private var lastYoutubeUser: String? = null

    private val _twitchTurbo = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    /** A conta da Twitch tem Turbo? `null` = sem conta ou ainda conferindo. */
    val twitchTurbo: StateFlow<Boolean?> = _twitchTurbo

    fun refreshTwitchTurbo() {
        scope.launch { _twitchTurbo.value = runCatching { twitch.hasTurbo() }.getOrNull() }
    }

    init {
        scope.launch {
            data.accounts.collect { accounts ->
                // Os endereços de vídeo dependem do login do YouTube: quem trocou de conta não pode herdar os preparados da outra.
                val youtubeUser = accounts[Platform.YouTube.name]?.displayName
                if (youtubeUser != lastYoutubeUser) { lastYoutubeUser = youtubeUser; session.clearPrepared() }
                tube.cookieHeader = accounts[Platform.YouTube.name]?.credential
                twitch.authToken = accounts[Platform.Twitch.name]?.credential
                // Troca de conta (ou entrada/saída): confere de novo se a Twitch dessa conta tem Turbo.
                if (accounts[Platform.Twitch.name] != null) refreshTwitchTurbo() else _twitchTurbo.value = null
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
        // A Twitch entra na mesma conferência: quem trocar de conta no navegador (por exemplo para a que tem Turbo) é acompanhado.
        scope.launch {
            while (true) {
                runCatching { refreshAccountFromBrowser(Platform.Twitch) }
                runCatching { refreshAccountFromBrowser(Platform.YouTube) }
                kotlinx.coroutines.delay(ACCOUNT_REFRESH_MS)
            }
        }
        cloud.start()
        liveWatcher.start()
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

    /** Copia uma playlist do YouTube (todos os vídeos) para as playlists do app. */
    fun copyRemotePlaylist(id: String, title: String) {
        toast("Copiando a playlist…")
        scope.launch {
            val items = runCatching { youtube.playlistAll(id) }.getOrNull()
            when {
                items == null -> toast("Não foi possível copiar a playlist.")
                items.isEmpty() -> toast("Esta playlist está vazia.")
                else -> {
                    val ids = data.importPlaylist(title, items)
                    toast(if (ids.size == 1) "Playlist copiada: ${items.size} vídeos" else "Playlist copiada em ${ids.size} partes (${items.size} vídeos)")
                }
            }
        }
    }

    /** Liga/desliga a tela cheia do player. Ao sair, volta ao que era antes (janela normal, ou F11 se estava ligado). */
    fun setPlayerFullscreen(on: Boolean) {
        ui.playerFullscreen = on
        system.setFullscreen(ui.playerFullscreen || ui.windowFullscreen)
    }

    /** F11. Dentro da tela cheia do player, o F11 sai de tudo. */
    fun toggleWindowFullscreen() {
        if (ui.playerFullscreen) {
            ui.playerFullscreen = false
            ui.windowFullscreen = false
        } else {
            ui.windowFullscreen = !ui.windowFullscreen
            if (ui.windowFullscreen) toast("Tela cheia: aperte F11 para sair")
        }
        system.setFullscreen(ui.playerFullscreen || ui.windowFullscreen)
    }

    fun openChannel(channel: Channel) {
        // Os clipes da conta do YouTube vêm sem o endereço do canal; ele aparece quando o clipe é aberto.
        if (channel.id.isBlank()) return toast("Abra o clipe para ir ao canal.")
        nav.push(Route.ChannelPage(channel))
    }

    /**
     * Entra direto na live do canal (a bolinha e a foto "ao vivo" levam para cá em vez de para a página do canal).
     * Usa a live que já se conhece ([known] ou a dos canais seguidos); sem ela, a Twitch e a Kick abrem pelo canal e o YouTube
     * procura a live do canal. Se ela já acabou, abre a página do canal.
     */
    fun openLive(channel: Channel, known: Media? = null) {
        val live = known?.takeIf { it.isLive }
            ?: liveWatcher.live.value.firstOrNull { it.channel?.key == channel.key && it.isLive }
        if (live != null) return openMedia(live.copy(channel = live.channel ?: channel))
        when (channel.platform) {
            Platform.Twitch -> openMedia(Media(Platform.Twitch, channel.id, channel.name, channel, isLive = true, url = "https://www.twitch.tv/${channel.id}"))
            Platform.Kick -> openMedia(Media(Platform.Kick, channel.id, channel.name, channel, isLive = true, url = "https://kick.com/${channel.id}"))
            Platform.YouTube -> {
                toast("Abrindo a live…")
                scope.launch {
                    val found = runCatching { youtube.channelLive(channel.id) }.getOrNull()
                    if (found != null) openMedia(found.copy(channel = found.channel ?: channel))
                    else {
                        toast("Esta live já acabou.")
                        openChannel(channel)
                    }
                }
            }
        }
    }

    /** Põe [medias] no Multi (as que couberem) e abre a tela dele. */
    fun openMulti(medias: List<Media> = emptyList()) {
        var full = false
        for (m in medias) if (!multi.add(m) && multi.isFull) full = true
        if (full) toast("O Multi comporta até ${app.apex.multi.MultiStream.MAX} lives.")
        nav.goRoot(Route.Multi)
    }

    /** Põe [media] no Multi, avisando o que aconteceu. `true` se entrou. */
    fun addToMulti(media: Media): Boolean {
        val name = media.channel?.name ?: media.id
        return when {
            multi.medias.any { it.key == media.key } -> { toast("$name já está no Multi"); false }
            multi.isFull -> { toast("O Multi comporta até ${app.apex.multi.MultiStream.MAX} lives."); false }
            else -> { multi.add(media); toast("$name entrou no Multi"); true }
        }
    }

    /**
     * O campo do Multi: um link (de qualquer plataforma, inclusive multitwitch/multikick e a página de um canal do YouTube) ou o nome de um
     * canal da [platform] (no YouTube, o @handle ou o nome; a live dele é procurada em segundo plano). `true` quando entrou ou está a caminho.
     */
    fun addToMulti(text: String, platform: Platform): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val medias = when (val target = app.apex.source.parseLink(t)) {
            is app.apex.source.LinkTarget.Play -> listOf(target.media)
            is app.apex.source.LinkTarget.Multi -> target.medias
            is app.apex.source.LinkTarget.YouTubeChannel -> { addYoutubeChannelLive(target.ref); return true }
            is app.apex.source.LinkTarget.YouTubeClip -> { toast("Clipes não entram no Multi."); return false }
            null -> when {
                platform == Platform.YouTube -> { addYoutubeChannelLive(t); return true }
                else -> listOf(app.apex.source.liveFromName(t, platform) ?: run { toast("Digite o nome de um canal da ${platform.label}, ou cole um link."); return false })
            }
        }
        return medias.count { addToMulti(it) } > 0
    }

    /**
     * Um canal seguido, escolhido nas sugestões do Multi: entra a live dele se está no ar; senão o próprio canal (na Twitch e na Kick o quadro
     * avisa que está offline; no YouTube a live é procurada).
     */
    fun addChannelToMulti(channel: Channel): Boolean {
        liveWatcher.live.value.firstOrNull { it.channel?.key == channel.key }?.let { return addToMulti(it) }
        return when (channel.platform) {
            Platform.YouTube -> addToMulti(channel.id, Platform.YouTube)
            else -> app.apex.source.liveFromName(channel.id, channel.platform)?.let { addToMulti(it.copy(title = channel.name, channel = channel)) } ?: false
        }
    }

    /** Procura a live de um canal do YouTube ([ref]: @handle, nome ou id) e põe no Multi; avisa se o canal não existe ou não está ao vivo. */
    private fun addYoutubeChannelLive(ref: String) {
        val shown = ref.trim()
        toast("Procurando a live de $shown no YouTube…")
        scope.launch {
            val id = runCatching { youtube.findChannelId(shown) }.getOrNull()
            if (id == null) return@launch toast("Não achei o canal \"$shown\" no YouTube.")
            val live = runCatching { youtube.channelLive(id) }.getOrNull()
            if (live == null) return@launch toast("$shown não está ao vivo no YouTube agora.")
            addToMulti(live)
        }
    }

    /** Se [text] é um link de vídeo, live, VOD ou clipe, abre direto e devolve `true`. */
    fun openLink(text: String): Boolean {
        when (val target = app.apex.source.parseLink(text) ?: return false) {
            is app.apex.source.LinkTarget.Play -> openMedia(target.media)
            is app.apex.source.LinkTarget.Multi -> openMulti(target.medias)
            is app.apex.source.LinkTarget.YouTubeChannel -> {
                scope.launch {
                    val id = runCatching { youtube.findChannelId(target.ref) }.getOrNull()
                    if (id == null) toast("Não achei o canal \"${target.ref}\" no YouTube.")
                    else openChannel(app.apex.model.Channel(Platform.YouTube, id, target.ref.removePrefix("@"), url = "https://www.youtube.com/channel/$id"))
                }
            }
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

    fun search(query: String, filters: SearchFilters = SearchFilters(type = app.apex.source.SearchType.Any)) {
        if (query.isBlank()) return
        if (openLink(query)) return
        data.addSearchHistory(query)
        nav.push(Route.Search(query.trim(), filters))
    }

    /** O volume máximo agora: 200% com o reforço ligado (Ajustes), 100% sem ele. */
    val maxVolume: Int get() = app.apex.player.maxVolume(data.settings.value.volumeBoost)

    /** Muda o volume e guarda nos ajustes: o próximo vídeo e as lives do Multi já abrem com ele. */
    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, maxVolume)
        app.apex.util.Timing.mark("volume: pedido $percent -> $v (estado ${player.state.value.volume}, máximo $maxVolume)")
        player.setVolume(v)
        data.updateSettings { it.copy(volume = v) }
    }

    /** Liga ou desliga o volume acima de 100%; desligando, um volume que passava de 100% desce para 100% na hora. */
    fun setVolumeBoost(on: Boolean) {
        data.updateSettings { it.copy(volumeBoost = on) }
        if (!on && data.settings.value.volume > app.apex.player.NORMAL_VOLUME) setVolume(app.apex.player.NORMAL_VOLUME)
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
        when (platform) {
            Platform.YouTube -> tube.cookieHeader = fresh.credential
            Platform.Twitch -> twitch.authToken = fresh.credential
            Platform.Kick -> kick.sessionToken = fresh.credential
        }
        // O navegador pode estar em outra conta agora: pega o nome e a foto dela.
        refreshAccountProfile(platform)
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
