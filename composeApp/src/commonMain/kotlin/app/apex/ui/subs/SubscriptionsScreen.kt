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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
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
    var tab by remember { mutableIntStateOf(0) }
    val state = rememberLazyGridState()
    val blocked = settings.blockedChannels

    ApexGrid(state) {
        if (subs.isNotEmpty()) {
            fullSpan("avatars") {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(subs.sortedByDescending { it.key in liveKeys }, key = { it.key }) { ch ->
                        Column(
                            Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable { app.openChannel(ch) }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Avatar(ch.avatarUrl, ch.name, 56.dp, ring = if (ch.key in liveKeys) ApexColors.Live else null)
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
            ChipRow(listOf("Novidades", "Ao vivo (${liveNow.size})", "Canais (${subs.size})"), tab, { tab = it })
        }

        when {
            subs.isEmpty() -> fullSpan("empty") {
                EmptyState(
                    Icons.Rounded.Subscriptions, "Nenhuma inscrição ainda",
                    "Siga canais do YouTube, Twitch e Kick para ver tudo reunido aqui. Você pode importar direto das suas contas.",
                    action = { ActionButton("Importar das contas", { app.nav.goRoot(Route.Settings) }, primary = true) },
                )
            }
            tab == 0 -> {
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
            tab == 1 -> {
                if (liveNow.isNotEmpty()) videoItems(liveNow.withoutBlocked(blocked))
                else fullSpan("nolive") {
                    EmptyState(Icons.Rounded.Subscriptions, "Ninguém ao vivo", "Nenhum dos seus canais está transmitindo agora.")
                }
            }
            else -> {
                fullSpan("channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("Seus canais", Modifier.padding(bottom = 8.dp))
                        subs.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                    }
                }
            }
        }
    }
}
