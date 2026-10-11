package app.apex

import app.apex.player.stepVolume
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.apex.nav.LibraryTab
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.player.stepSpeed
import app.apex.ui.shell.ShortcutsDialog
import app.apex.ui.shell.refreshCurrent
import app.apex.theme.ApexColors
import app.apex.theme.ApexTheme
import app.apex.ui.shell.RallyIntro
import app.apex.theme.Themes
import app.apex.ui.channel.ChannelScreen
import app.apex.ui.home.HomeScreen
import app.apex.ui.library.AddToPlaylistDialog
import app.apex.ui.library.LibraryScreen
import app.apex.ui.library.PlaylistScreen
import app.apex.ui.live.CategoryScreen
import app.apex.ui.live.LiveScreen
import app.apex.ui.multi.MultiScreen
import app.apex.ui.search.SearchScreen
import app.apex.ui.settings.SettingsScreen
import app.apex.ui.shell.MiniPlayer
import app.apex.ui.shell.Sidebar
import app.apex.ui.shell.TopBar
import app.apex.ui.shell.UpdateBanner
import app.apex.ui.subs.SubscriptionsScreen
import app.apex.ui.watch.WatchScreen
import kotlinx.coroutines.delay

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ApexApp() {
    val app = LocalApp.current
    val fullscreen = app.ui.playerFullscreen
    val settings by app.data.settings.collectAsState()
    // O tema escolhido nos ajustes: troca as cores do app todo na hora.
    ApexColors.palette = Themes.byId(settings.theme)
    val route = app.nav.current
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(app.ui.typing, route, fullscreen) { if (!app.ui.typing) runCatching { focus.requestFocus() } }
    // A tela cheia do player só existe na página do vídeo.
    LaunchedEffect(route) { if (route !is Route.Watch && app.ui.playerFullscreen) app.setPlayerFullscreen(false) }
    // Modo cinema: a faixa do player vai de ponta a ponta e toda preta, como no YouTube (sem a barra lateral e com o topo preto).
    val theaterBand = route is Route.Watch && app.ui.theater

    ApexTheme {
        Surface(color = ApexColors.Background, contentColor = ApexColors.OnSurface) {
            Box(
                Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { handleShortcut(app, it) }
                    // Os botões laterais do mouse (voltar e avançar), como no navegador.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) {
                                    app.ui.pressTick++
                                    when (event.button) {
                                        PointerButton.Back -> if (app.nav.canGoBack) app.nav.back()
                                        PointerButton.Forward -> app.nav.forward()
                                        else -> {}
                                    }
                                }
                            }
                        }
                    },
            ) {
                if (fullscreen && route is Route.Watch) {
                    WatchScreen(fullscreen = true)
                } else {
                    Row(Modifier.fillMaxSize()) {
                        if (!theaterBand) Sidebar(expanded = !settings.sidebarCollapsed)
                        Column(Modifier.weight(1f)) {
                            Box(if (theaterBand) Modifier.background(if (ApexColors.palette.isLight) ApexColors.Background else Color.Black) else Modifier) {
                                TopBar(onToggleSidebar = { app.data.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) } })
                            }
                            UpdateBanner()
                            Box(Modifier.weight(1f).fillMaxSize()) { Content(route) }
                        }
                    }
                    MiniPlayer()
                }
                ToastHost()
                // Ao trocar para um tema Subaru, a silhueta de um carro de rali acelera pela tela.
                RallyIntro(settings.theme)
                app.ui.playlistTarget?.let { AddToPlaylistDialog(it) { app.ui.playlistTarget = null } }
                if (app.ui.shortcutsOpen) ShortcutsDialog { app.ui.shortcutsOpen = false }
            }
        }
    }
}

@Composable
private fun Content(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        is Route.Search -> SearchScreen(route)
        Route.Watch -> WatchScreen(fullscreen = false)
        is Route.ChannelPage -> ChannelScreen(route.channel, route.tab)
        is Route.Live -> LiveScreen(route.filter)
        is Route.Category -> CategoryScreen(route.platform, route.category)
        Route.Subscriptions -> SubscriptionsScreen()
        is Route.Library -> LibraryScreen(route.tab)
        is Route.PlaylistPage -> PlaylistScreen(route.id)
        is Route.RemotePlaylist -> app.apex.ui.library.RemotePlaylistScreen(route.id, route.title, route.coverUrl, route.countText)
        Route.Settings -> SettingsScreen()
        Route.Multi -> MultiScreen()
    }
}

@Composable
private fun BoxScopeToast(message: String) {
    Text(
        message,
        Modifier.background(ApexColors.SurfaceHighest.copy(alpha = 0.95f), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
        color = ApexColors.OnSurface, style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ToastHost() {
    val app = LocalApp.current
    val message = app.ui.toast
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            if (app.ui.toast == message) app.ui.toast = null
        }
    }
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    if (message != null) last = message
    AnimatedVisibility(
        message != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
    ) { BoxScopeToast(last) }
}

/** Atalhos de teclado: valem quando há algo tocando e o usuário não está digitando. */
internal fun handleShortcut(app: AppContainer, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // Esc: sai da tela cheia do player (o F11 só sai com o F11, como no navegador); com o cursor na barra de pesquisa tira o cursor de
    // lá (e fecha as sugestões); na página de resultados volta para onde a pessoa estava.
    if (event.key == Key.Escape) {
        when {
            app.ui.shortcutsOpen -> app.ui.shortcutsOpen = false
            app.ui.playerFullscreen -> app.setPlayerFullscreen(false)
            app.ui.typing -> app.ui.typing = false
            app.nav.current is Route.Search -> if (app.nav.canGoBack) app.nav.back() else app.nav.goRoot(Route.Home)
            app.nav.current is Route.Watch && app.ui.theater -> app.ui.theater = false
            else -> return false
        }
        return true
    }
    // F11 como em qualquer programa do Windows: tela cheia da janela de qualquer tela (vale até com o cursor na busca).
    if (event.key == Key.F11) {
        app.toggleWindowFullscreen()
        return true
    }
    // F1 e F5 (ajuda e atualizar) valem em qualquer lugar; os atalhos com Ctrl ou Alt também, até com o cursor num campo de texto.
    if (event.key == Key.F1) { app.ui.shortcutsOpen = !app.ui.shortcutsOpen; return true }
    if (event.key == Key.F5) { refreshCurrent(app); return true }
    if (appShortcut(app, event)) return true
    if (app.ui.typing) return false
    // "/" leva o cursor à pesquisa (como no YouTube) e "?" abre a lista de atalhos. Pelo caractere digitado, não pela tecla: no teclado
    // brasileiro a "/" e o "?" ficam numa tecla que não é a mesma do teclado americano.
    // (Ctrl + Alt juntos é o AltGr: no teclado brasileiro a "/" digitada com ele chega assim.)
    val plainTyping = event.isCtrlPressed == event.isAltPressed
    if (plainTyping) {
        when (event.utf16CodePoint) {
            '/'.code -> { app.ui.focusSearchTick++; return true }
            '?'.code -> { app.ui.shortcutsOpen = !app.ui.shortcutsOpen; return true }
        }
    }
    val media = app.session.current.value ?: return false
    val player = app.player
    val state = player.state.value
    val onWatch = app.nav.current is Route.Watch
    val live = media.isLive

    // "<" e ">" (Shift + vírgula e ponto) mudam a velocidade, como no YouTube; também pelo caractere.
    if (!live && plainTyping && (event.utf16CodePoint == '<'.code || event.utf16CodePoint == '>'.code)) {
        val rate = stepSpeed(state.rate, faster = event.utf16CodePoint == '>'.code)
        player.setRate(rate)
        val label = if (rate == 1f) "normal" else "${"$rate".replace('.', ',')}x"
        app.toast("Velocidade: $label")
        return true
    }

    return when (event.key) {
        Key.Spacebar, Key.K -> { player.togglePause(); true }
        Key.M -> { player.setMuted(!state.muted); true }
        Key.F -> { if (onWatch) { app.setPlayerFullscreen(!app.ui.playerFullscreen); true } else false }
        Key.T -> { if (onWatch) { app.ui.theater = !app.ui.theater; true } else false }
        Key.DirectionLeft -> { if (!live) player.seekBy(-5_000); true }
        Key.DirectionRight -> { if (!live) player.seekBy(5_000); true }
        Key.J -> { if (!live) player.seekBy(-10_000); true }
        Key.L -> { if (!live) player.seekBy(10_000); true }
        Key.MoveHome -> { if (onWatch && !live) { player.seekTo(0); true } else false }
        Key.MoveEnd -> when {
            // Numa live, End é o "Voltar ao vivo": abre de novo no ponto mais novo e tira o atraso que se acumulou.
            onWatch && live -> { player.jumpToLive(); app.toast("Voltando ao ao vivo…"); true }
            onWatch && state.durationMs > 0 -> { player.seekTo((state.durationMs - 1_000).coerceAtLeast(0)); true }
            else -> false
        }
        // Enter na página de uma live leva o cursor à caixa do chat.
        Key.Enter, Key.NumPadEnter -> { if (onWatch && live && app.ui.chatVisible) { app.ui.focusChatTick++; true } else false }
        Key.DirectionUp -> { app.setVolume(stepVolume(state.volume, 5, app.maxVolume)); true }
        Key.DirectionDown -> { app.setVolume(stepVolume(state.volume, -5, app.maxVolume)); true }
        Key.N -> { if (event.isShiftPressed) { app.session.next(); true } else false }
        Key.P -> { if (event.isShiftPressed) { app.session.previous(); true } else false }
        Key.C -> {
            val resolved = (app.session.load.value as? app.apex.player.LoadState.Ready)?.resolved
            if (resolved != null && resolved.subtitles.isNotEmpty()) {
                if (app.session.subtitle.value != null) app.session.setSubtitleTrack(null)
                else app.session.setSubtitleTrack(resolved.subtitles.first())
                true
            } else false
        }
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
            if (!live && state.durationMs > 0) {
                val digit = when (event.key) {
                    Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4; Key.Five -> 5
                    Key.Six -> 6; Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9; else -> 0
                }
                player.seekTo(state.durationMs * digit / 10)
                true
            } else false
        }
        else -> false
    }
}

/**
 * Atalhos do app todo, com Ctrl ou Alt (valem até com o cursor num campo de texto): pesquisar, voltar e avançar, trocar de tela, atualizar.
 * Só Ctrl sozinho ou Alt sozinho: no Windows o AltGr (usado no teclado brasileiro para "/" e outros) chega como Ctrl + Alt.
 */
private fun appShortcut(app: AppContainer, event: KeyEvent): Boolean {
    val ctrl = event.isCtrlPressed && !event.isAltPressed
    val alt = event.isAltPressed && !event.isCtrlPressed
    if (ctrl) {
        when (event.key) {
            Key.K, Key.L -> app.ui.focusSearchTick++
            Key.R -> refreshCurrent(app)
            Key.B -> app.data.updateSettings { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }
            Key.Comma -> app.nav.goRoot(Route.Settings)
            Key.One -> app.nav.goRoot(Route.Home)
            Key.Two -> app.nav.goRoot(Route.Live(LiveFilter.All))
            Key.Three -> app.nav.goRoot(Route.Subscriptions)
            Key.Four -> app.nav.goRoot(Route.Library(LibraryTab.History))
            Key.Five -> app.nav.goRoot(Route.Settings)
            Key.Six -> app.nav.goRoot(Route.Multi)
            else -> return false
        }
        return true
    }
    if (alt) {
        when (event.key) {
            Key.DirectionLeft -> if (app.nav.canGoBack) app.nav.back()
            Key.DirectionRight -> app.nav.forward()
            Key.MoveHome -> app.nav.goRoot(Route.Home)
            else -> return false
        }
        return true
    }
    return false
}
