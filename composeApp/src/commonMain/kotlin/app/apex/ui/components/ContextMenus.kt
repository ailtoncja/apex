package app.apex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.nav.Route
import app.apex.source.RemotePlaylist
import app.apex.theme.ApexColors
import kotlin.math.roundToInt

/**
 * Faz o conteúdo abrir um menu quando a pessoa aperta o botão direito do mouse, na posição do cursor
 * (o clique normal continua funcionando como antes).
 */
@Composable
fun ContextMenuHost(
    modifier: Modifier = Modifier,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var at by remember { mutableStateOf<IntOffset?>(null) }
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val change = event.changes.firstOrNull() ?: continue
                        at = IntOffset(change.position.x.roundToInt(), change.position.y.roundToInt())
                        change.consume()
                    }
                }
            }
        },
    ) {
        content()
        at?.let { point -> Box(Modifier.offset { point }) { menu { at = null } } }
    }
}

private fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

/** Opções de uma playlist do YouTube (de um canal ou da conta). */
@Composable
fun RemotePlaylistMenu(playlist: RemotePlaylist, expanded: Boolean, onDismiss: () -> Unit) {
    val app = LocalApp.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Abrir") },
            leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
            onClick = { app.nav.push(Route.RemotePlaylist(playlist.id, playlist.title)); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Copiar para minhas playlists") },
            leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
            onClick = { app.copyRemotePlaylist(playlist.id, playlist.title); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Copiar link") },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
            onClick = { app.system.copyText(playlistUrl(playlist.id)); app.toast("Link copiado"); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Abrir no navegador") },
            leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
            onClick = { app.system.openUrl(playlistUrl(playlist.id)); onDismiss() },
        )
    }
}

/** Cartão de playlist para a grade (aba "Playlists" do canal). */
@Composable
fun RemotePlaylistCard(playlist: RemotePlaylist, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    ContextMenuHost(
        modifier.clip(RoundedCornerShape(12.dp)).hoverable(source)
            .clickable(interactionSource = source, indication = null) { app.nav.push(Route.RemotePlaylist(playlist.id, playlist.title)) },
        menu = { dismiss -> RemotePlaylistMenu(playlist, true, dismiss) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(if (hovered) 4.dp else 12.dp))) {
                RemoteImage(playlist.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                playlist.countText?.let {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Rounded.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.Top) {
                Text(
                    playlist.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Box {
                    if (hovered || menu) IconBtn(Icons.Rounded.MoreVert, "Mais", { menu = true }, size = 28.dp, iconSize = 18.dp)
                    else Box(Modifier.size(28.dp))
                    RemotePlaylistMenu(playlist, menu) { menu = false }
                }
            }
        }
    }
}

/** Linha de playlist do YouTube na biblioteca. */
@Composable
fun RemotePlaylistRow(playlist: RemotePlaylist, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    var menu by remember { mutableStateOf(false) }
    ContextMenuHost(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable { app.nav.push(Route.RemotePlaylist(playlist.id, playlist.title)) },
        menu = { dismiss -> RemotePlaylistMenu(playlist, true, dismiss) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteImage(playlist.thumbnailUrl, Modifier.width(176.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                playlist.countText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted) }
            }
            Box {
                IconBtn(Icons.Rounded.MoreVert, "Mais", { menu = true }, size = 28.dp, iconSize = 18.dp)
                RemotePlaylistMenu(playlist, menu) { menu = false }
            }
        }
    }
}
