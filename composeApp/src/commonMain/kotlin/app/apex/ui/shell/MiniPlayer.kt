package app.apex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.nav.Route
import app.apex.theme.ApexColors
import app.apex.ui.components.IconBtn
import app.apex.ui.components.LiveTag

/** Janelinha flutuante que continua tocando quando você sai da página do vídeo. */
@Composable
fun BoxScope.MiniPlayer() {
    val app = LocalApp.current
    val current by app.session.current.collectAsState()
    val route = app.nav.current
    val media = current ?: return
    if (route is Route.Watch || app.ui.miniPlayerHidden) return

    val ps by app.player.state.collectAsState()
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()

    Column(
        Modifier.align(Alignment.BottomEnd).padding(20.dp).width(380.dp)
            .clip(RoundedCornerShape(14.dp)).background(ApexColors.Surface)
            .border(1.dp, ApexColors.Outline, RoundedCornerShape(14.dp)).hoverable(source),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { app.nav.push(Route.Watch) }) {
            app.player.Video(Modifier.matchParent())
            if (hovered) {
                Box(Modifier.matchParent().background(Color(0x66000000)))
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    IconBtn(Icons.Rounded.Fullscreen, "Abrir", { app.nav.push(Route.Watch) }, size = 34.dp, iconSize = 20.dp, tint = Color.White, hoverColor = Color(0x44FFFFFF))
                    IconBtn(Icons.Rounded.Close, "Fechar", { app.session.close() }, size = 34.dp, iconSize = 20.dp, tint = Color.White, hoverColor = Color(0x44FFFFFF))
                }
                IconBtn(
                    if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar ou pausar",
                    { app.player.togglePause() }, modifier = Modifier.align(Alignment.Center), size = 56.dp, iconSize = 34.dp,
                    tint = Color.White, background = Color(0x66000000), hoverColor = Color(0x99000000),
                )
            }
            if (media.isLive) LiveTag(Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        if (!media.isLive && ps.durationMs > 0) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth((ps.positionMs.toFloat() / ps.durationMs).coerceIn(0f, 1f)).height(3.dp).background(ApexColors.Accent))
            }
        }
        Column(Modifier.clickable { app.nav.push(Route.Watch) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(media.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            media.channel?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1) }
        }
    }
}

private fun Modifier.matchParent(): Modifier = this.then(Modifier.fillMaxWidth()).then(Modifier.aspectRatio(16f / 9f))
