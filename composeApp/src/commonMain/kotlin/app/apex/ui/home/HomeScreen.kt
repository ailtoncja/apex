package app.apex.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Platform
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.ChipRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.MediaRow
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val home = app.screens.home
    LaunchedEffect(Unit) { home.start() }

    val state = rememberLazyGridState()
    val settings by app.data.settings.collectAsState()
    val accounts by app.data.accounts.collectAsState()
    val blocked = settings.blockedChannels
    val loggedIn = accounts.containsKey(Platform.YouTube.name)

    val followedLive by home.followedLive.collectAsState()
    val topLive by home.topLive.collectAsState()
    val liveLoading by home.liveLoading.collectAsState()
    val feed by app.data.feed.collectAsState()
    val subs by app.data.subscriptions.collectAsState()
    val feedLoading by app.screens.subs.feedLoading.collectAsState()

    val selected = home.selected
    val topic = home.topics[selected]
    val list = when {
        selected > 0 -> home.topicList(topic)
        loggedIn -> home.recommendations
        else -> home.discover
    }
    val items by list.items.collectAsState()
    val loading by list.loading.collectAsState()
    val error by list.error.collectAsState()
    val loaded by list.loaded.collectAsState()
    LaunchedEffect(selected, loggedIn) { list.loadIfNeeded() }
    OnNearEnd(state) { list.loadMore() }

    ApexGrid(state) {
        fullSpan("chips") {
            ChipRow(home.topics.map { it.label }, selected, { home.selected = it })
        }

        if (selected == 0) {
            if (followedLive.isNotEmpty()) {
                fullSpan("followed-title") {
                    SectionTitle("Seus canais ao vivo", Modifier.padding(bottom = 12.dp), "${followedLive.size} no ar agora")
                }
                fullSpan("followed-row") { MediaRow(followedLive.withoutBlocked(blocked)) }
            }
            if (topLive.isNotEmpty() || liveLoading) {
                fullSpan("live-title") {
                    SectionTitle(
                        "Ao vivo agora", Modifier.padding(top = 4.dp, bottom = 12.dp), "Twitch, Kick e YouTube",
                        trailing = { ActionButton("Ver tudo", { app.nav.goRoot(Route.Live(LiveFilter.All)) }) },
                    )
                }
                if (topLive.isNotEmpty()) fullSpan("live-row") { MediaRow(topLive.withoutBlocked(blocked)) }
            }
            val subFeed = feed.items.withoutBlocked(blocked).take(12)
            if (subFeed.isNotEmpty()) {
                fullSpan("feed-title") {
                    SectionTitle(
                        "Das suas inscrições", Modifier.padding(top = 4.dp), null,
                        trailing = { ActionButton("Ver todas", { app.nav.goRoot(Route.Subscriptions) }) },
                    )
                }
                videoItems(subFeed)
            } else if (subs.none { it.platform == Platform.YouTube } && !feedLoading) {
                fullSpan("feed-empty") {
                    Row(
                        Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh, RoundedCornerShape(16.dp)).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Rounded.Subscriptions, null, tint = ApexColors.Accent, modifier = Modifier.size(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Traga seus canais para cá", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Entre na conta para importar suas inscrições do YouTube e os canais que você segue na Twitch e na Kick.",
                                style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                            )
                        }
                        ActionButton("Importar inscrições", { app.nav.goRoot(Route.Settings) }, primary = true)
                    }
                }
            }
            fullSpan("rec-title") {
                SectionTitle(
                    if (loggedIn) "Recomendados para você" else if (app.data.history.value.isNotEmpty()) "Com base no que você assistiu" else "Em alta esta semana",
                    Modifier.padding(top = 4.dp),
                )
            }
        }

        val shown = items.withoutBlocked(blocked)
        when {
            shown.isNotEmpty() -> videoItems(shown)
            !loaded || loading -> skeletons(12)
            error != null -> fullSpan("error") { ErrorBox(error.orEmpty(), { list.refresh() }) }
        }
        if (loading && shown.isNotEmpty()) skeletons(4)
    }
}
