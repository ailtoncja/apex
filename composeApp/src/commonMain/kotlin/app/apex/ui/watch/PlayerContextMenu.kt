package app.apex.ui.watch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import app.apex.LocalApp
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.VOD_PREFIX
import app.apex.player.LoadState
import app.apex.theme.ApexColors
import app.apex.util.formatClock

/**
 * O link de [media] começando no ponto [positionMs] do player, do jeito que cada site aceita; `null` onde não dá (live, Kick, clipe da Twitch).
 * No YouTube, o clipe vira o vídeo de onde ele saiu, no mesmo ponto.
 */
internal fun linkAt(media: Media, positionMs: Long): String? {
    if (media.isLive) return null
    val seconds = (positionMs / 1000).coerceAtLeast(0)
    return when (media.platform) {
        Platform.YouTube -> "https://www.youtube.com/watch?v=${media.videoId}&t=${(media.clipStartMs ?: 0) / 1000 + seconds}s"
        Platform.Twitch -> if (media.isVod) {
            "https://www.twitch.tv/videos/${media.id.removePrefix(VOD_PREFIX)}?t=${seconds / 3600}h${seconds % 3600 / 60}m${seconds % 60}s"
        } else null
        Platform.Kick -> null
    }
}

/**
 * O menu do botão direito no player, como o do YouTube: repetir, velocidade, qualidade e legendas (estes três abrem o painel de
 * configurações já na lista certa, por [onPanel]), copiar o link (também no ponto atual), abrir no navegador, salvar, ir para o canal,
 * modo cinema e tela cheia. Numa live: voltar ao vivo, qualidade, mostrar/ocultar o chat e o Multi. À direita, o atalho de cada opção.
 */
@Composable
internal fun PlayerContextMenu(fullscreen: Boolean, onPanel: (PlayerMenu) -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val player = app.player
    val media by session.current.collectAsState()
    val ps by player.state.collectAsState()
    val load by session.load.collectAsState()
    val loop by session.loop.collectAsState()
    val quality by session.quality.collectAsState()
    val subtitle by session.subtitle.collectAsState()
    val later by app.data.watchLater.collectAsState()
    val settings by app.data.settings.collectAsState()
    val resolved = (load as? LoadState.Ready)?.resolved
    // Sem nada aberto não há o que mostrar (o próximo clique direito abre de novo).
    val current = resolved?.media ?: media ?: return
    val live = resolved?.isLive ?: current.isLive

    @Composable
    fun Item(label: String, icon: ImageVector, shortcut: String? = null, checked: Boolean = false, tag: String? = null, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = {
                if (checked) Icon(Icons.Rounded.Check, null, tint = ApexColors.Accent) else Icon(icon, null)
            },
            trailingIcon = shortcut?.let { { Text(it, color = ApexColors.Muted, style = MaterialTheme.typography.labelMedium) } },
            onClick = { onDismiss(); onClick() },
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = Modifier.testTag("player-menu")) {
        if (live) {
            if (ps.behindLiveMs > LIVE_EDGE_SLACK_MS || !ps.playing) {
                Item("Voltar ao vivo", Icons.Rounded.LiveTv, "End") { player.jumpToLive() }
            }
        } else {
            Item("Repetir", Icons.Rounded.Repeat, "R", checked = loop, tag = "player-menu-loop") {
                session.setLoop(!loop)
                app.toast(if (!loop) "Repetir: ligado" else "Repetir: desligado")
            }
            Item("Velocidade: ${if (ps.rate == 1f) "normal" else "${ps.rate}x".replace('.', ',')}", Icons.Rounded.Speed, "< >") { onPanel(PlayerMenu.Speed) }
        }
        if (resolved != null && resolved.qualities.size > 1) {
            Item("Qualidade: ${quality?.label ?: "—"}", Icons.Rounded.HighQuality) { onPanel(PlayerMenu.Quality) }
        }
        if (resolved != null && resolved.subtitles.isNotEmpty()) {
            Item("Legendas: ${subtitle?.name ?: "desativadas"}", Icons.Rounded.ClosedCaption, "C") { onPanel(PlayerMenu.Subtitles) }
        }
        if (live && !fullscreen) {
            Item(if (settings.showChat) "Ocultar o chat" else "Mostrar o chat", Icons.Rounded.Chat) {
                app.data.updateSettings { it.copy(showChat = !it.showChat) }
            }
        }
        HorizontalDivider()

        Item(if (live) "Copiar link da live" else "Copiar link do vídeo", Icons.Rounded.ContentCopy, tag = "player-menu-copy") {
            app.system.copyText(current.url)
            app.toast("Link copiado")
        }
        val here = linkAt(current, ps.positionMs)
        if (here != null) {
            Item("Copiar link em ${formatClock(ps.positionMs)}", Icons.Rounded.Link, tag = "player-menu-copy-here") {
                app.system.copyText(here)
                app.toast("Link copiado: começa em ${formatClock(ps.positionMs)}")
            }
        }
        Item("Ouvir só o som em segundo plano", Icons.Rounded.Headphones, tag = "player-menu-background") { app.moveToBackground() }
        Item(if (here != null) "Abrir no navegador neste ponto" else "Abrir no navegador", Icons.Rounded.OpenInBrowser, tag = "player-menu-browser") {
            // Os dois tocando ao mesmo tempo, o som sairia em dobro: o player pausa e o navegador continua dali.
            player.pause()
            app.system.openUrl(here ?: current.url)
        }
        HorizontalDivider()

        if (!live) {
            val isLater = later.any { it.key == current.key }
            Item(if (isLater) "Remover de Assistir depois" else "Salvar em Assistir depois", Icons.Rounded.WatchLater) {
                app.data.toggleWatchLater(current)
                app.toast(if (isLater) "Removido de Assistir depois" else "Salvo em Assistir depois")
            }
            Item("Adicionar à playlist…", Icons.Rounded.PlaylistAdd) { app.ui.playlistTarget = current }
        } else {
            Item("Assistir no Multi", Icons.Rounded.GridView) { app.openMulti(listOf(current)) }
        }
        current.channel?.let { ch ->
            Item("Ir para o canal", Icons.Rounded.Person) {
                if (fullscreen) app.setPlayerFullscreen(false)
                app.openChannel(ch)
            }
        }
        HorizontalDivider()

        if (!fullscreen) {
            Item("Modo cinema", Icons.Rounded.Crop169, "T", checked = app.ui.theater) { app.ui.theater = !app.ui.theater }
        }
        Item(if (fullscreen) "Sair da tela cheia" else "Tela cheia", if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "F") {
            app.setPlayerFullscreen(!fullscreen)
        }
    }
}
