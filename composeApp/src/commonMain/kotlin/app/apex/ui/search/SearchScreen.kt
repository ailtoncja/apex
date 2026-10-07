package app.apex.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.source.SearchDate
import app.apex.source.SearchDuration
import app.apex.source.SearchSort
import app.apex.state.SearchTab
import app.apex.state.interleave
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.ui.components.ApexChip
import app.apex.ui.components.ChannelRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.MediaRow
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.SkeletonCard
import app.apex.ui.components.VideoRow
import app.apex.theme.color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height

@Composable
fun SearchScreen(route: Route.Search) {
    val app = LocalApp.current
    val st = remember(route) { app.screens.search(route.query, route.filters) }
    LaunchedEffect(st) { st.start() }

    val settings by app.data.settings.collectAsState()
    val blocked = settings.blockedChannels
    val ytVideos by st.youtube.items.collectAsState()
    val ytLoading by st.youtube.loading.collectAsState()
    val ytLoaded by st.youtube.loaded.collectAsState()
    val ytError by st.youtube.error.collectAsState()
    val ytChannels by st.ytChannels.collectAsState()
    val twLives by st.twitchLives.value.collectAsState()
    val twChannels by st.twitchChannels.value.collectAsState()
    val kickChannels by st.kickChannels.value.collectAsState()
    val liveKeys by app.screens.subs.liveChannels.collectAsState()
    val tab = st.tab
    val listState = rememberLazyListState()
    OnNearEnd(listState) { st.youtube.loadMore() }

    val showYt = tab == SearchTab.All || tab == SearchTab.YouTube
    val showTw = tab == SearchTab.All || tab == SearchTab.Twitch
    val showKick = tab == SearchTab.All || tab == SearchTab.Kick

    val channels: List<Pair<Channel, Boolean>> = interleave(
        listOf(
            if (showYt) ytChannels.take(3).map { it to false } else emptyList(),
            if (showTw) twChannels.take(3).map { it.channel to (it.live != null) } else emptyList(),
            if (showKick) kickChannels.take(3).map { it.channel to (it.live != null) } else emptyList(),
        ),
    ).filter { it.first.key !in blocked }

    val lives: List<Media> = interleave(
        listOf(
            if (showTw) twLives.take(12) else emptyList(),
            if (showKick) kickChannels.mapNotNull { it.live }.take(6) else emptyList(),
            if (showYt) ytVideos.filter { it.isLive }.take(6) else emptyList(),
        ),
    ).withoutBlocked(blocked)

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1120.dp).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Resultados para “${route.query}”", Modifier.padding(top = 4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SearchTab.entries.toList()) { t ->
                            val dot = when (t) {
                                SearchTab.All -> null
                                SearchTab.YouTube -> Platform.YouTube.color()
                                SearchTab.Twitch -> Platform.Twitch.color()
                                SearchTab.Kick -> Platform.Kick.color()
                            }
                            ApexChip(t.label, tab == t, { st.tab = t }, dot = dot)
                        }
                    }
                    if (showYt) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                FilterMenu("Ordenar", SearchSort.entries, route.filters.sort, { it.label }) {
                                    app.nav.replaceTop(route.copy(filters = route.filters.copy(sort = it)))
                                }
                            }
                            item {
                                FilterMenu("Data", SearchDate.entries, route.filters.date, { it.label }) {
                                    app.nav.replaceTop(route.copy(filters = route.filters.copy(date = it)))
                                }
                            }
                            item {
                                FilterMenu("Duração", SearchDuration.entries, route.filters.duration, { it.label }) {
                                    app.nav.replaceTop(route.copy(filters = route.filters.copy(duration = it)))
                                }
                            }
                            item {
                                ApexChip("Só ao vivo", route.filters.liveOnly, {
                                    app.nav.replaceTop(route.copy(filters = route.filters.copy(liveOnly = !route.filters.liveOnly)))
                                })
                            }
                        }
                    }
                }
            }

            if (channels.isNotEmpty()) {
                item("channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        channels.take(6).forEach { (ch, live) -> ChannelRow(ch, live = live || ch.key in liveKeys) }
                    }
                }
            }

            if (lives.isNotEmpty()) {
                item("lives") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Ao vivo agora")
                        MediaRow(lives)
                    }
                }
            }

            if (showYt) {
                val shown = ytVideos.withoutBlocked(blocked)
                if (shown.isNotEmpty()) {
                    if (showTw || showKick) item("videos-title") { SectionTitle("Vídeos") }
                    items(shown, key = { "v-" + it.key }) { VideoRow(it, upNext = shown.filter { m -> m.key != it.key }) }
                } else if (!ytLoaded || ytLoading) {
                    items(5, key = { "sk$it" }) { SkeletonRow() }
                } else if (ytError != null) {
                    item("err") { ErrorBox(ytError.orEmpty(), { st.youtube.refresh() }) }
                } else if (channels.isEmpty() && lives.isEmpty()) {
                    item("none") { EmptyState(Icons.Rounded.Search, "Nada encontrado", "Tente outras palavras ou remova os filtros.") }
                }
                if (ytLoading && shown.isNotEmpty()) item("more") { SkeletonRow() }
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonCard(Modifier.width(246.dp))
    }
}

@Composable
private fun <T> FilterMenu(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ApexChip("$label: ${name(selected)}", false, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(name(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}
