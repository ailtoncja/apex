package app.apex.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.player.LoadState
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.Avatar
import app.apex.ui.components.EmptyState
import app.apex.ui.components.PlatformTag
import app.apex.ui.components.SkeletonCard
import app.apex.ui.components.SubscribeButton
import app.apex.ui.components.VerifiedMark
import app.apex.ui.components.VideoRow
import app.apex.ui.components.shimmer
import app.apex.util.formatCount
import app.apex.util.formatDuration
import app.apex.util.formatUploadDate
import app.apex.util.relativeTime

@Composable
fun WatchScreen(fullscreen: Boolean) {
    val app = LocalApp.current
    val current by app.session.current.collectAsState()
    val media = current

    if (fullscreen) {
        PlayerView(Modifier.fillMaxSize(), fullscreen = true)
        return
    }
    if (media == null) {
        EmptyState(Icons.Rounded.PlayCircle, "Nada tocando", "Escolha um vídeo ou uma live para assistir.")
        return
    }
    LaunchedEffect(media.key) { app.screens.watch.load(media) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1080.dp
        val maxH = maxHeight
        val maxW = maxWidth
        val theater = app.ui.theater
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (theater) {
                // Altura calculada de forma explícita (16:9, no máximo 74% da tela). Com `heightIn` + `aspectRatio` numa coluna rolável o
                // player era medido menor do que desenhado e o restante da página subia por cima dele.
                PlayerView(Modifier.fillMaxWidth().height(minOf(maxW * 9f / 16f, maxH * 0.74f)))
            }
            if (wide) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = if (theater) 16.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!theater) PlayerView(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
                        VideoInfo()
                        if (media.platform == Platform.YouTube && !media.isLive) Comments(media)
                    }
                    SideColumn(media, Modifier.width(404.dp))
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!theater) PlayerView(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
                    VideoInfo()
                    SideColumn(media, Modifier.fillMaxWidth())
                    if (media.platform == Platform.YouTube && !media.isLive) Comments(media)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoInfo() {
    val app = LocalApp.current
    val current by app.session.current.collectAsState()
    val load by app.session.load.collectAsState()
    val media = current ?: return
    val resolved = (load as? LoadState.Ready)?.resolved
    val reactions by app.data.reactions.collectAsState()
    val later by app.data.watchLater.collectAsState()
    val reaction = reactions[media.key] ?: 0
    val isLater = later.any { it.key == media.key }
    var expanded by remember(media.key) { mutableStateOf(false) }
    val channel = media.channel

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (channel != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { app.openChannel(channel) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(channel.avatarUrl, channel.name, 44.dp)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (channel.verified) VerifiedMark()
                            if (media.platform != Platform.YouTube) PlatformTag(media.platform)
                        }
                        (channel.followers ?: resolved?.subscribers)?.let {
                            Text(
                                "${formatCount(it)} ${if (media.platform == Platform.YouTube) "inscritos" else "seguidores"}",
                                style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                            )
                        }
                    }
                }
                SubscribeButton(channel)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (media.platform == Platform.YouTube) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(ApexColors.SurfaceHigh)) {
                    val base = resolved?.likes
                    val shown = base?.let { it + if (reaction == 1) 1 else 0 }
                    ActionButton(
                        shown?.let { formatCount(it) } ?: "Curtir",
                        { app.react(media, if (reaction == 1) 0 else 1) },
                        icon = Icons.Rounded.ThumbUp, active = reaction == 1,
                    )
                    ActionButton("", { app.react(media, if (reaction == -1) 0 else -1) }, icon = Icons.Rounded.ThumbDown, active = reaction == -1)
                }
            }
            if (!media.isLive) {
                ActionButton(if (isLater) "Salvo" else "Salvar", { app.data.toggleWatchLater(media); app.toast(if (isLater) "Removido de Assistir depois" else "Salvo em Assistir depois") },
                    icon = Icons.Rounded.WatchLater, active = isLater)
                ActionButton("Playlist", { app.ui.playlistTarget = media }, icon = Icons.Rounded.PlaylistAdd)
            }
            ActionButton("Copiar link", { app.system.copyText(media.url); app.toast("Link copiado") }, icon = Icons.Rounded.ContentCopy)
            ActionButton("Abrir no site", { app.system.openUrl(media.url) }, icon = Icons.Rounded.OpenInBrowser)
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ApexColors.SurfaceHigh)
                .clickable { expanded = !expanded }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val meta = buildList {
                media.viewCount?.let { add(if (media.isLive) "${formatCount(it)} assistindo agora" else "${formatCount(it)} de visualizações") }
                val date = formatUploadDate(resolved?.uploadDate).ifEmpty { relativeTime(media.publishedAt) }
                if (date.isNotEmpty() && !media.isLive) add(date)
                media.category?.let { add(it) }
            }.joinToString(" • ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.titleSmall)
            val description = resolved?.description
            if (description.isNullOrBlank()) {
                if (load is LoadState.Loading) Box(Modifier.fillMaxWidth().height(40.dp).shimmer())
                else Text("Sem descrição.", style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
            } else {
                Text(
                    description, style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                )
                Text(if (expanded) "Mostrar menos" else "…mais", style = MaterialTheme.typography.labelLarge, color = ApexColors.Muted)
            }
            if (expanded && resolved?.chapters?.isNotEmpty() == true) {
                Text("Capítulos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                resolved.chapters.forEach { ch ->
                    Text(
                        "${formatDuration(ch.startSec).ifEmpty { "0:00" }}  ${ch.title}",
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { app.player.seekTo(ch.startSec * 1000) }.padding(vertical = 3.dp),
                        style = MaterialTheme.typography.bodyMedium, color = ApexColors.OnSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SideColumn(media: Media, modifier: Modifier) {
    val app = LocalApp.current
    val watch = app.screens.watch
    val related by watch.related.collectAsState()
    val loading by watch.relatedLoading.collectAsState()
    val settings by app.data.settings.collectAsState()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (media.isLive) {
            if (settings.showChat) ChatPanel(media, Modifier.fillMaxWidth().height(520.dp))
            else Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ApexColors.Surface)
                    .border(1.dp, ApexColors.Outline, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chat oculto", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = ApexColors.Muted)
                ActionButton("Mostrar chat", { app.data.updateSettings { it.copy(showChat = true) } }, icon = Icons.Rounded.Visibility)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (media.isLive) "Mais ao vivo" else "A seguir", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("Reprodução automática", style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = settings.autoplayNext,
                onCheckedChange = { v -> app.data.updateSettings { it.copy(autoplayNext = v) } },
                colors = SwitchDefaults.colors(checkedTrackColor = ApexColors.Accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
            )
        }
        if (related.isEmpty() && loading) {
            repeat(5) { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { SkeletonCard(Modifier.width(168.dp)) } }
        }
        related.forEach { m ->
            VideoRow(m, thumbWidth = 168.dp, compact = true, upNext = related.filter { it.key != m.key })
        }
    }
}

@Composable
private fun Comments(media: Media) {
    val app = LocalApp.current
    val watch = app.screens.watch
    val comments by watch.comments.collectAsState()
    val loading by watch.commentsLoading.collectAsState()
    val error by watch.commentsError.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Comentários", style = MaterialTheme.typography.titleLarge)
            ApexChip("Mais relevantes", !watch.newestFirst, { if (watch.newestFirst) { watch.newestFirst = false; watch.loadComments(media) } })
            ApexChip("Mais recentes", watch.newestFirst, { if (!watch.newestFirst) { watch.newestFirst = true; watch.loadComments(media) } })
        }
        if (loading && comments.isEmpty()) {
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(40.dp).height(40.dp).clip(androidx.compose.foundation.shape.CircleShape).shimmer())
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.width(140.dp).height(12.dp).shimmer())
                        Box(Modifier.width(420.dp).height(12.dp).shimmer())
                    }
                }
            }
        }
        error?.let { Text("Não foi possível carregar os comentários: $it", color = ApexColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        comments.forEach { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(c.authorAvatar, c.author, 40.dp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (c.pinned) Text("Fixado", style = MaterialTheme.typography.labelSmall, color = ApexColors.Muted)
                        Text(c.author, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text(c.timeText, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
                    }
                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                    if (c.likes > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.Icon(Icons.Rounded.ThumbUp, null, tint = ApexColors.Muted, modifier = Modifier.height(14.dp).width(14.dp))
                            Text(formatCount(c.likes), style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
                        }
                    }
                }
            }
        }
    }
}
