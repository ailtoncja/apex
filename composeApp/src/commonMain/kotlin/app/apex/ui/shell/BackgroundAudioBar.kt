package app.apex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.player.NORMAL_VOLUME
import app.apex.theme.ApexColors
import app.apex.ui.components.IconBtn
import app.apex.ui.components.RemoteImage
import app.apex.ui.watch.MiniSlider
import app.apex.util.formatClock

/**
 * A barra da música de fundo (ver [app.apex.player.BackgroundAudio]): aparece embaixo do conteúdo enquanto há música, com o que toca, tocar e
 * pausar, anterior e próxima, repetir a lista, o volume dela (separado do vídeo), a fila e o ✕ que para.
 */
@Composable
fun BackgroundAudioBar() {
    val app = LocalApp.current
    val bg = app.background
    val index by bg.index.collectAsState()
    val queue by bg.queue.collectAsState()
    val s by bg.state.collectAsState()
    val loop by bg.loop.collectAsState()
    val settings by app.data.settings.collectAsState()
    val media = queue.getOrNull(index) ?: return
    val volume = settings.backgroundVolume
    var queueOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(ApexColors.Surface).testTag("background-bar")) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(ApexColors.Outline)) {
            if (!media.isLive && s.durationMs > 0) {
                Box(Modifier.fillMaxWidth((s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f)).height(2.dp).background(ApexColors.Accent))
            }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Headphones, "Música de fundo", tint = ApexColors.Accent, modifier = Modifier.size(20.dp))
            // Capa e título: voltar a assistir o vídeo no player principal, do ponto em que o som está (ele sai do fundo).
            val watchSource = remember { MutableInteractionSource() }
            val watchHovered by watchSource.collectIsHoveredAsState()
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).hoverable(watchSource)
                    .background(if (watchHovered) ApexColors.SurfaceHigh else Color.Transparent)
                    .clickable(interactionSource = watchSource, indication = null) { app.watchFromBackground(media.key) }
                    .padding(4.dp).testTag("background-watch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp))) {
                RemoteImage(media.thumbnailUrl, Modifier.matchParentSize())
                if (watchHovered) {
                    Box(Modifier.matchParentSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.OndemandVideo, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (watchHovered) "Assistir: ${media.title}" else media.title,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    media.channel?.name,
                    when {
                        s.error != null -> "não deu para tocar"
                        !s.hasMedia && !s.loading -> "parado"
                        media.isLive -> "ao vivo"
                        s.durationMs > 0 -> "${formatClock(s.positionMs)} / ${formatClock(s.durationMs)}"
                        else -> null
                    },
                    if (queue.size > 1) "${index + 1} de ${queue.size}" else null,
                ).joinToString(" • ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            IconBtn(Icons.Rounded.SkipPrevious, "Anterior", { bg.previous() }, size = 36.dp, iconSize = 22.dp)
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (s.loading && !s.playing) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = ApexColors.Accent, strokeWidth = 2.dp)
                } else {
                    IconBtn(
                        if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pausar a música de fundo" else "Tocar a música de fundo",
                        { bg.togglePause() }, modifier = Modifier.testTag("background-toggle"), size = 40.dp, iconSize = 26.dp,
                    )
                }
            }
            IconBtn(
                Icons.Rounded.SkipNext, "Próxima", { bg.next() }, size = 36.dp, iconSize = 22.dp,
                tint = if (index + 1 < queue.size || loop) ApexColors.OnSurface else ApexColors.Muted.copy(alpha = 0.5f),
            )
            IconBtn(
                Icons.Rounded.Repeat, if (loop) "Repetir a lista: ligado" else "Repetir a lista", { bg.setLoop(!loop) }, size = 36.dp, iconSize = 20.dp,
                tint = if (loop) ApexColors.Accent else ApexColors.OnSurface,
            )
            IconBtn(
                if (volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Volume da música de fundo",
                { bg.setVolume(if (volume == 0) NORMAL_VOLUME / 2 else 0) }, size = 36.dp, iconSize = 20.dp,
            )
            MiniSlider(
                volume / NORMAL_VOLUME.toFloat(), { v -> bg.setVolume((v * NORMAL_VOLUME).toInt()) },
                Modifier.width(90.dp).testTag("background-volume"),
                fill = ApexColors.OnSurface, track = ApexColors.Outline,
            )
            Text("$volume%", Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted)
            Box {
                IconBtn(Icons.Rounded.QueueMusic, "Fila da música de fundo", { queueOpen = true }, size = 36.dp, iconSize = 20.dp)
                DropdownMenu(expanded = queueOpen, onDismissRequest = { queueOpen = false }) {
                    queue.forEachIndexed { i, m ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.OndemandVideo, "Assistir",
                                    Modifier.size(20.dp).clip(RoundedCornerShape(50)).clickable { queueOpen = false; app.watchFromBackground(m.key) }
                                        .testTag("background-watch-${m.key}"),
                                )
                            },
                            text = {
                                Text(
                                    "${i + 1}. ${m.title}", Modifier.widthIn(max = 360.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (i == index) ApexColors.Accent else ApexColors.OnSurface,
                                )
                            },
                            trailingIcon = {
                                Icon(Icons.Rounded.Close, "Tirar da fila", Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable { bg.remove(m.key) })
                            },
                            onClick = { bg.jump(i); queueOpen = false },
                        )
                    }
                }
            }
            IconBtn(Icons.Rounded.Close, "Parar a música de fundo", { bg.close() }, modifier = Modifier.testTag("background-close"), size = 36.dp, iconSize = 20.dp)
        }
    }
}
