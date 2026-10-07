package app.apex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.WatchLater
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.theme.ApexColors
import app.apex.util.formatCount
import app.apex.util.formatDuration
import app.apex.util.relativeTime

fun mediaMeta(media: Media): String = if (media.isLive) {
    listOfNotNull(media.viewCount?.let { "${formatCount(it)} assistindo" }, media.category).joinToString(" • ")
} else {
    listOfNotNull(
        media.viewCount?.let { "${formatCount(it)} de visualizações" },
        media.publishedAt?.let { relativeTime(it) }?.takeIf { it.isNotEmpty() },
    ).joinToString(" • ")
}

@Composable
fun MediaThumbnail(
    media: Media,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    radius: Dp = 12.dp,
    showPlatform: Boolean = true,
) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(radius))) {
        RemoteImage(media.thumbnailUrl, Modifier.fillMaxWidth().fillMaxHeight())
        if (showPlatform && media.platform != Platform.YouTube) {
            PlatformTag(media.platform, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        if (media.isClip || media.isVod) {
            Text(
                if (media.isClip) "CLIPE" else "VOD",
                Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (media.isLive) {
            LiveTag(Modifier.align(Alignment.BottomStart).padding(8.dp))
            media.viewCount?.let {
                Text(
                    formatCount(it),
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        } else {
            val d = formatDuration(media.durationSec)
            if (d.isNotEmpty()) {
                Text(
                    d,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (progress != null && progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(ApexColors.Accent))
            }
        }
    }
}

@Composable
private fun rememberProgress(media: Media): Float? {
    if (media.isLive) return null
    val app = LocalApp.current
    val history by app.data.history.collectAsState()
    return remember(media.key, history) {
        val e = history.firstOrNull { it.media.key == media.key } ?: return@remember null
        if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs) else null
    }
}

@Composable
fun MediaMenu(media: Media, expanded: Boolean, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val later by app.data.watchLater.collectAsState()
    val isLater = later.any { it.key == media.key }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!media.isLive) {
            DropdownMenuItem(
                text = { Text(if (isLater) "Remover de Assistir depois" else "Salvar em Assistir depois") },
                leadingIcon = { Icon(Icons.Rounded.WatchLater, null) },
                onClick = {
                    app.data.toggleWatchLater(media)
                    app.toast(if (isLater) "Removido de Assistir depois" else "Salvo em Assistir depois")
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Adicionar à playlist…") },
                leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
                onClick = { app.ui.playlistTarget = media; onDismiss() },
            )
        }
        media.channel?.let { ch ->
            DropdownMenuItem(
                text = { Text("Ir para o canal") },
                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                onClick = { app.openChannel(ch); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text("Copiar link") },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
            onClick = { app.system.copyText(media.url); app.toast("Link copiado"); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Abrir no navegador") },
            leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
            onClick = { app.system.openUrl(media.url); onDismiss() },
        )
        media.channel?.let { ch ->
            DropdownMenuItem(
                text = { Text("Não recomendar este canal") },
                leadingIcon = { Icon(Icons.Rounded.Block, null) },
                onClick = { app.data.blockChannel(ch); app.toast("${ch.name} foi ocultado"); onDismiss() },
            )
        }
    }
}

/** Cartão vertical da grade: miniatura, avatar do canal, título e informações. */
@Composable
fun VideoCard(media: Media, modifier: Modifier = Modifier, upNext: List<Media>? = null) {
    val app = LocalApp.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val progress = rememberProgress(media)

    Column(
        modifier.clip(RoundedCornerShape(12.dp)).hoverable(source)
            .clickable(interactionSource = source, indication = null) { app.openMedia(media, upNext) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            MediaThumbnail(media, Modifier.fillMaxWidth(), progress, radius = if (hovered) 4.dp else 12.dp)
            if (hovered && !media.isLive) {
                IconBtn(
                    Icons.Rounded.WatchLater, "Assistir depois",
                    onClick = { app.data.toggleWatchLater(media); app.toast("Salvo em Assistir depois") },
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), size = 32.dp, iconSize = 18.dp,
                    background = Color(0xB3000000), hoverColor = Color(0xE6000000),
                )
            }
        }
        Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            media.channel?.let { ch ->
                Avatar(
                    ch.avatarUrl, ch.name, 36.dp,
                    Modifier.clip(CircleShape).clickable { app.openChannel(ch) },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(media.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                media.channel?.let { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            ch.name, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { app.openChannel(ch) },
                        )
                        if (ch.verified) VerifiedMark()
                    }
                }
                val meta = mediaMeta(media)
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                if (hovered || menu) {
                    IconBtn(Icons.Rounded.MoreVert, "Mais", { menu = true }, size = 28.dp, iconSize = 18.dp)
                } else {
                    Box(Modifier.size(28.dp))
                }
                MediaMenu(media, menu) { menu = false }
            }
        }
    }
}

/** Cartão horizontal (busca, relacionados, biblioteca). */
@Composable
fun VideoRow(
    media: Media,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 246.dp,
    compact: Boolean = false,
    upNext: List<Media>? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val app = LocalApp.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val progress = rememberProgress(media)

    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).hoverable(source)
            .clickable(interactionSource = source, indication = null) { onClick?.invoke() ?: app.openMedia(media, upNext) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MediaThumbnail(media, Modifier.width(thumbWidth), progress, radius = if (hovered) 4.dp else 12.dp)
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                media.title,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (compact) {
                media.channel?.let {
                    Text(it.name, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(mediaMeta(media), style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 2)
            } else {
                Text(mediaMeta(media), style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1)
                media.channel?.let { ch ->
                    Row(
                        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { app.openChannel(ch) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Avatar(ch.avatarUrl, ch.name, 24.dp)
                        Text(ch.name, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1)
                        if (ch.verified) VerifiedMark()
                    }
                }
            }
        }
        Box {
            if (trailing != null) trailing() else if (hovered || menu) {
                IconBtn(Icons.Rounded.MoreVert, "Mais", { menu = true }, size = 28.dp, iconSize = 18.dp)
            } else Box(Modifier.size(28.dp))
            MediaMenu(media, menu) { menu = false }
        }
    }
}

@Composable
fun SubscribeButton(channel: Channel, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val subs by app.data.subscriptions.collectAsState()
    val on = subs.any { it.key == channel.key }
    val (on1, off1) = if (channel.platform == Platform.YouTube) "Inscrito" to "Inscrever-se" else "Seguindo" to "Seguir"
    ActionButton(
        label = if (on) on1 else off1,
        onClick = {
            app.setSubscribed(channel, !on)
            app.toast(if (on) "Você deixou de seguir ${channel.name}" else "Agora você segue ${channel.name}")
            if (!on) app.screens.subs.refreshFeed()
        },
        modifier = modifier,
        primary = !on,
        active = false,
    )
}

/** Linha de canal (resultado de busca, lista de inscrições). */
@Composable
fun ChannelRow(channel: Channel, modifier: Modifier = Modifier, live: Boolean = false, subtitle: String? = null) {
    val app = LocalApp.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { app.openChannel(channel) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(channel.avatarUrl, channel.name, 64.dp, ring = if (live) ApexColors.Live else null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.verified) VerifiedMark()
                PlatformTag(channel.platform)
                channel.support?.let { SupportBadge(it) }
            }
            val line = subtitle ?: listOfNotNull(
                channel.handle,
                channel.followers?.let { "${formatCount(it)} ${if (channel.platform == Platform.YouTube) "inscritos" else "seguidores"}" },
            ).joinToString(" • ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1)
            if (live) LiveTag(Modifier.padding(top = 4.dp))
        }
        SubscribeButton(channel)
    }
}

@Composable
fun CategoryCard(category: LiveCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteImage(
            category.imageUrl,
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(if (hovered) 6.dp else 12.dp)),
        )
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(category.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            category.viewers?.let {
                Text("${formatCount(it)} assistindo", style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1)
            }
        }
    }
}
