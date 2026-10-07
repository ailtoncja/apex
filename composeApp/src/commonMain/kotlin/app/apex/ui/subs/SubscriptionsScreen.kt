package app.apex.ui.subs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.Avatar
import app.apex.ui.components.ChannelRow
import app.apex.ui.components.ChipRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems

private enum class SubsTab { News, Live, Supported, Channels }

@Composable
fun SubscriptionsScreen() {
    val app = LocalApp.current
    val subsState = app.screens.subs
    LaunchedEffect(Unit) { subsState.refreshFeedIfStale() }

    val subs by app.data.subscriptions.collectAsState()
    val feed by app.data.feed.collectAsState()
    val settings by app.data.settings.collectAsState()
    val feedLoading by subsState.feedLoading.collectAsState()
    val liveKeys by subsState.liveChannels.collectAsState()
    val liveNow by subsState.liveNow.collectAsState()
    var requestedTab by remember { mutableStateOf(SubsTab.News) }
    val state = rememberLazyGridState()
    val blocked = settings.blockedChannels

    // Canais que a pessoa paga (sub da Twitch, membro do YouTube) ficam separados dos outros.
    val supported = subs.filter { it.support != null }
    val supportedKeys = supported.map { it.key }.toSet()
    val order = listOfNotNull(SubsTab.News, SubsTab.Live, SubsTab.Supported.takeIf { supported.isNotEmpty() }, SubsTab.Channels)
    val tab = requestedTab.takeIf { it in order } ?: SubsTab.News
    val labels = order.map {
        when (it) {
            SubsTab.News -> "Novidades"
            SubsTab.Live -> "Ao vivo (${liveNow.size})"
            SubsTab.Supported -> "Apoiados (${supported.size})"
            SubsTab.Channels -> "Canais (${subs.size})"
        }
    }

    ApexGrid(state) {
        if (subs.isNotEmpty()) {
            fullSpan("avatars") {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(subs.sortedWith(compareByDescending<Channel> { it.key in liveKeys }.thenByDescending { it.support != null }), key = { it.key }) { ch ->
                        Column(
                            Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable { app.openChannel(ch) }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Avatar(ch.avatarUrl, ch.name, 56.dp, ring = if (ch.key in liveKeys) ApexColors.Live else if (ch.support != null) ApexColors.Support else null)
                            Text(
                                ch.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        fullSpan("tabs") {
            ChipRow(labels, order.indexOf(tab), { requestedTab = order[it] })
        }

        when {
            subs.isEmpty() -> fullSpan("empty") {
                EmptyState(
                    Icons.Rounded.Subscriptions, "Nenhuma inscrição ainda",
                    "Siga canais do YouTube, Twitch e Kick para ver tudo reunido aqui. Você pode importar direto das suas contas.",
                    action = { ActionButton("Importar das contas", { app.nav.goRoot(Route.Settings) }, primary = true) },
                )
            }
            tab == SubsTab.News -> {
                val items = feed.items.withoutBlocked(blocked)
                if (items.isNotEmpty()) videoItems(items)
                else if (feedLoading) skeletons(12)
                else fullSpan("nofeed") {
                    EmptyState(
                        Icons.Rounded.Subscriptions, "Sem vídeos novos",
                        if (subs.none { it.platform == Platform.YouTube }) "Os vídeos novos aparecem aqui para canais do YouTube. Twitch e Kick ficam na aba Ao vivo."
                        else "Nada novo por enquanto.",
                    )
                }
                if (feedLoading && items.isNotEmpty()) fullSpan("loading") {
                    Text("Atualizando…", color = ApexColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            tab == SubsTab.Live -> {
                if (liveNow.isNotEmpty()) videoItems(liveNow.withoutBlocked(blocked))
                else fullSpan("nolive") {
                    EmptyState(Icons.Rounded.Subscriptions, "Ninguém ao vivo", "Nenhum dos seus canais está transmitindo agora.")
                }
            }
            tab == SubsTab.Supported -> {
                fullSpan("supported") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle(
                            "Canais que você apoia", Modifier.padding(bottom = 8.dp),
                            subtitle = "Subs da Twitch e memberships do YouTube. Atualizado ao abrir o app e ao importar as contas.",
                        )
                        supported.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                    }
                }
                // O que esses canais têm de novo: ao vivo agora e vídeos recentes.
                val mine = (liveNow + feed.items).filter { it.channel?.key in supportedKeys }.withoutBlocked(blocked).distinctBy { it.key }
                if (mine.isNotEmpty()) {
                    fullSpan("supported-news") { SectionTitle("Novidades dos canais apoiados", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                    videoItems(mine)
                }
            }
            else -> {
                fullSpan("channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (supported.isNotEmpty()) {
                            SectionTitle("Apoiados (${supported.size})", Modifier.padding(bottom = 8.dp))
                            supported.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                            SectionTitle("Outros canais", Modifier.padding(top = 20.dp, bottom = 8.dp))
                            subs.filter { it.support == null }.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                        } else {
                            SectionTitle("Seus canais", Modifier.padding(bottom = 8.dp))
                            subs.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                        }
                    }
                }
            }
        }
    }
}
