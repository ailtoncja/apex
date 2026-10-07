package app.apex.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.Platform
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.Avatar
import app.apex.ui.components.ChipRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.PlatformTag
import app.apex.ui.components.RemoteImage
import app.apex.ui.components.SubscribeButton
import app.apex.ui.components.VerifiedMark
import app.apex.ui.components.VideoCard
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems
import app.apex.util.formatCount

@Composable
fun ChannelScreen(seed: Channel) {
    val app = LocalApp.current
    val st = remember(seed.key) { app.screens.channel(seed) }
    LaunchedEffect(st) { st.start() }

    val details by st.details.value.collectAsState()
    val liveNow by st.live.value.collectAsState()
    val settings by app.data.settings.collectAsState()
    val isYt = seed.platform == Platform.YouTube
    val channel = details?.channel?.let { it.copy(avatarUrl = it.avatarUrl ?: seed.avatarUrl) } ?: seed
    var tab by remember(seed.key) { mutableIntStateOf(0) }
    val tabs = if (isYt) listOf("Vídeos", "Transmissões", "Sobre") else listOf("Ao vivo", "Sobre")

    val list = when {
        isYt && tab == 0 -> st.videos
        isYt && tab == 1 -> st.pastLives
        else -> null
    }
    LaunchedEffect(tab, st) { if (isYt && tab == 1) st.pastLives.loadIfNeeded() }
    val items by (list?.items ?: st.videos.items).collectAsState()
    val loading by (list?.loading ?: st.videos.loading).collectAsState()
    val error by (list?.error ?: st.videos.error).collectAsState()
    val state = rememberLazyGridState()
    OnNearEnd(state) { list?.loadMore() }

    ApexGrid(state) {
        fullSpan("header") {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                details?.bannerUrl?.let {
                    RemoteImage(it, Modifier.fillMaxWidth().aspectRatio(6f).clip(RoundedCornerShape(16.dp)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(channel.avatarUrl, channel.name, 96.dp, ring = if (liveNow != null) ApexColors.Live else null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(channel.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (channel.verified) VerifiedMark(Modifier.padding(top = 4.dp))
                            PlatformTag(channel.platform)
                        }
                        val meta = listOfNotNull(
                            channel.handle,
                            channel.followers?.let { "${formatCount(it)} ${if (isYt) "inscritos" else "seguidores"}" },
                        ).joinToString(" • ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
                        details?.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("", { app.system.openUrl(channel.url.ifBlank { seed.url }) }, icon = Icons.Rounded.OpenInBrowser)
                        SubscribeButton(channel)
                    }
                }
                ChipRow(tabs, tab, { tab = it })
            }
        }

        liveNow?.let { live ->
            if (tab == 0 || !isYt && tab == 0) {
                fullSpan("live-now") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Ao vivo agora", style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.fillMaxWidth()) { VideoCard(live, Modifier.fillMaxWidth(0.34f)) }
                    }
                }
            }
        }

        val aboutTab = if (isYt) 2 else 1
        when {
            tab == aboutTab -> fullSpan("about") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Sobre", style = MaterialTheme.typography.titleLarge)
                    Text(
                        details?.description?.takeIf { it.isNotBlank() } ?: "Este canal não tem descrição.",
                        style = MaterialTheme.typography.bodyMedium, color = ApexColors.OnSurface,
                    )
                    channel.followers?.let {
                        Text("${formatCount(it)} ${if (isYt) "inscritos" else "seguidores"}", style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
                    }
                    ActionButton("Abrir no site", { app.system.openUrl(channel.url.ifBlank { seed.url }) }, icon = Icons.Rounded.OpenInBrowser)
                }
            }
            !isYt -> if (liveNow == null) fullSpan("offline") {
                EmptyState(Icons.Rounded.Sensors, "Fora do ar", "${channel.name} não está transmitindo agora. Siga o canal para vê-lo aqui quando entrar ao vivo.")
            }
            items.isNotEmpty() -> videoItems(items.withoutBlocked(settings.blockedChannels).map { m ->
                m.copy(channel = (m.channel ?: channel).copy(avatarUrl = m.channel?.avatarUrl ?: channel.avatarUrl))
            })
            loading -> skeletons(8)
            error != null -> fullSpan("err") { ErrorBox(error.orEmpty(), { list?.refresh() }) }
            else -> fullSpan("none") {
                EmptyState(Icons.Rounded.VideoLibrary, "Sem vídeos", "Nada para mostrar nesta aba.")
            }
        }
        if (loading && items.isNotEmpty() && tab != aboutTab) skeletons(4)
    }
}
