package app.apex.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.source.SearchDate
import app.apex.source.SearchDuration
import app.apex.source.SearchFeature
import app.apex.source.SearchFilters
import app.apex.source.SearchSort
import app.apex.source.SearchType
import app.apex.state.SearchState
import app.apex.state.SearchTab
import app.apex.state.interleave
import app.apex.state.matchSubscriptions
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.ChannelRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.MediaRow
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.RemotePlaylistRow
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.SkeletonCard
import app.apex.ui.components.VideoRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(route: Route.Search) {
    val app = LocalApp.current
    val st = remember(route) { app.screens.search(route.query, route.filters) }
    LaunchedEffect(st) { st.start() }
    // As lives e os vídeos das inscrições entram na busca: garante que estão carregados mesmo abrindo a pesquisa direto.
    LaunchedEffect(Unit) { app.screens.subs.refreshFeedIfStale() }

    val settings by app.data.settings.collectAsState()
    val blocked = settings.blockedChannels
    val ytVideos by st.youtube.items.collectAsState()
    val ytLoading by st.youtube.loading.collectAsState()
    val ytLoaded by st.youtube.loaded.collectAsState()
    val ytError by st.youtube.error.collectAsState()
    val ytChannels by st.ytChannels.collectAsState()
    val ytPlaylists by st.ytPlaylists.collectAsState()
    val twLives by st.twitchLives.value.collectAsState()
    val twChannels by st.twitchChannels.value.collectAsState()
    val kickChannels by st.kickChannels.value.collectAsState()
    val liveKeys by app.screens.subs.liveChannels.collectAsState()
    val subs by app.data.subscriptions.collectAsState()
    val feed by app.data.feed.collectAsState()
    val liveNow by app.screens.subs.liveNow.collectAsState()
    val tab = st.tab
    val listState = rememberLazyListState()
    OnNearEnd(listState) { if (tab == SearchTab.All || tab == SearchTab.YouTube) st.youtube.loadMore() }
    var showFilters by remember { mutableStateOf(false) }

    val showYt = tab == SearchTab.All || tab == SearchTab.YouTube
    val showTw = tab == SearchTab.All || tab == SearchTab.Twitch
    val showKick = tab == SearchTab.All || tab == SearchTab.Kick

    // O que a pessoa já segue e combina com a busca (canais pelo nome; vídeos e lives por título, canal e categoria).
    val mine = remember(route.query, subs, feed, liveNow) { matchSubscriptions(route.query, subs, liveNow + feed.items) }
    val mineMedia = mine.media.withoutBlocked(blocked)

    val channelLists = listOf(
        if (showYt) ytChannels.take(if (tab == SearchTab.All) 3 else 8).map { it to false } else emptyList(),
        if (showTw) twChannels.take(if (tab == SearchTab.All) 3 else 8).map { it.channel to (it.live != null) } else emptyList(),
        if (showKick) kickChannels.take(if (tab == SearchTab.All) 3 else 8).map { it.channel to (it.live != null) } else emptyList(),
    )
    val channels: List<Pair<Channel, Boolean>> =
        (if (tab == SearchTab.All) interleave(channelLists) else channelLists.flatten()).filter { it.first.key !in blocked }

    val twitchLives = if (showTw) twLives.take(if (tab == SearchTab.All) 12 else 24) else emptyList()
    val kickLives = if (showKick) kickChannels.mapNotNull { it.live }.take(if (tab == SearchTab.All) 6 else 12) else emptyList()
    val ytLives = if (showYt) ytVideos.filter { it.isLive }.take(if (tab == SearchTab.All) 6 else 12) else emptyList()
    val lives: List<Media> = interleave(listOf(twitchLives, kickLives, ytLives)).withoutBlocked(blocked)
    val showLivesAsList = tab == SearchTab.Twitch || tab == SearchTab.Kick

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
                        items(SearchTab.entries.filter { it != SearchTab.Subs || subs.isNotEmpty() }) { t ->
                            val dot = when (t) {
                                SearchTab.All, SearchTab.Subs -> null
                                SearchTab.YouTube -> Platform.YouTube.color()
                                SearchTab.Twitch -> Platform.Twitch.color()
                                SearchTab.Kick -> Platform.Kick.color()
                            }
                            val label = if (t == SearchTab.Subs && !mine.isEmpty) "Inscrições (${mine.channels.size + mineMedia.size})" else t.label
                            ApexChip(label, tab == t, { st.tab = t }, dot = dot)
                        }
                    }
                    if (showYt) {
                        // Filtros no estilo do YouTube: um botão que abre o painel e os que estão ligados aparecem aqui, para tirar com um toque.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ApexChip(if (route.filters.activeCount > 0) "Filtros (${route.filters.activeCount})" else "Filtros", false, { showFilters = true })
                            activeFilters(route.filters).forEach { (label, without) ->
                                ApexChip("$label  ✕", true, { app.nav.replaceTop(route.copy(filters = without)) })
                            }
                        }
                    }
                }
            }

            // 1) o que combina entre as inscrições da pessoa
            if (tab == SearchTab.All && !mine.isEmpty) {
                item("mine") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(
                            "Das suas inscrições", subtitle = "${mine.channels.size} canais • ${mineMedia.size} vídeos e lives",
                            trailing = { ActionButton("Ver tudo", { st.tab = SearchTab.Subs }) },
                        )
                        mine.channels.take(3).forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                        if (mineMedia.isNotEmpty()) MediaRow(mineMedia.take(10))
                    }
                }
            }
            if (tab == SearchTab.Subs) {
                if (mine.isEmpty) item("mine-none") {
                    EmptyState(Icons.Rounded.Search, "Nada nas suas inscrições", "Nenhum canal, vídeo ou live dos canais que você segue combina com “${route.query}”. Veja a aba Tudo.")
                } else {
                    if (mine.channels.isNotEmpty()) {
                        item("mine-title-ch") { SectionTitle("Canais", subtitle = "${mine.channels.size} que você segue") }
                        items(mine.channels, key = { "mc-" + it.key }) { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                    }
                    if (mineMedia.isNotEmpty()) {
                        item("mine-title-v") { SectionTitle("Vídeos e lives", subtitle = "${mineMedia.size} dos seus canais") }
                        items(mineMedia, key = { "mv-" + it.key }) { VideoRow(it, upNext = mineMedia.filter { m -> m.key != it.key }) }
                    }
                }
            }

            // 2) canais
            if (tab != SearchTab.Subs && channels.isNotEmpty()) {
                item("channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("Canais", Modifier.padding(bottom = 4.dp))
                        channels.take(if (tab == SearchTab.All) 6 else 8).forEach { (ch, live) -> ChannelRow(ch, live = live || ch.key in liveKeys) }
                    }
                }
            }

            // 3) lives
            if (tab != SearchTab.Subs && lives.isNotEmpty()) {
                item("lives-title") { SectionTitle("Ao vivo agora", subtitle = "${lives.size} ${if (lives.size == 1) "transmissão" else "transmissões"}") }
                if (showLivesAsList) {
                    items(lives, key = { "l-" + it.key }) { VideoRow(it, upNext = lives.filter { m -> m.key != it.key }) }
                } else {
                    item("lives") { MediaRow(lives) }
                }
            }

            // 4) playlists e 5) vídeos do YouTube
            if (showYt) {
                if (ytPlaylists.isNotEmpty()) {
                    item("playlists-title") { SectionTitle("Playlists") }
                    items(ytPlaylists.take(if (tab == SearchTab.All) 3 else 12), key = { "p-" + it.id }) { RemotePlaylistRow(it) }
                }
                val shown = ytVideos.withoutBlocked(blocked)
                if (shown.isNotEmpty()) {
                    item("videos-title") { SectionTitle("Vídeos do YouTube") }
                    items(shown.distinctBy { it.key }, key = { "v-" + it.key }) { VideoRow(it, upNext = shown.filter { m -> m.key != it.key }) }
                } else if (!ytLoaded || ytLoading) {
                    items(5, key = { "sk$it" }) { SkeletonRow() }
                } else if (ytError != null) {
                    item("err") { ErrorBox(ytError.orEmpty(), { st.youtube.refresh() }) }
                } else if (channels.isEmpty() && lives.isEmpty() && ytPlaylists.isEmpty()) {
                    item("none") { EmptyState(Icons.Rounded.Search, "Nada encontrado", "Tente outras palavras ou remova os filtros.") }
                }
                if (ytLoading && shown.isNotEmpty()) item("more") { SkeletonRow() }
            } else if (tab != SearchTab.Subs && channels.isEmpty() && lives.isEmpty()) {
                item("none-live") {
                    EmptyState(Icons.Rounded.Search, "Nada encontrado", "Nenhum canal ou live da ${tab.label} para “${route.query}”. Veja a aba Tudo.")
                }
            }
        }
    }

    if (showFilters) {
        FiltersDialog(route.filters, onChange = { app.nav.replaceTop(route.copy(filters = it)) }, onClose = { showFilters = false })
    }
}

/** Os filtros ligados, cada um com o filtro como ficaria sem ele (para tirar com um toque). */
private fun activeFilters(f: SearchFilters): List<Pair<String, SearchFilters>> = buildList {
    if (f.sort != SearchSort.Relevance) add("Ordem: ${f.sort.label}" to f.copy(sort = SearchSort.Relevance))
    if (f.date != SearchDate.Any) add(f.date.label to f.copy(date = SearchDate.Any))
    if (f.type != SearchType.Any) add("Tipo: ${f.type.label}" to f.copy(type = SearchType.Any))
    if (f.duration != SearchDuration.Any) add(f.duration.label to f.copy(duration = SearchDuration.Any))
    if (f.liveOnly) add("Ao vivo" to f.copy(liveOnly = false))
    f.features.forEach { add(it.label to f.copy(features = f.features - it)) }
}

/** O painel de filtros, como o do YouTube: uma coluna para cada grupo. */
@Composable
internal fun FiltersDialog(filters: SearchFilters, onChange: (SearchFilters) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = ApexColors.SurfaceHigh, modifier = Modifier.widthIn(max = 940.dp).padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filtros de pesquisa", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (filters.activeCount > 0) {
                        ActionButton("Limpar tudo", { onChange(SearchFilters(type = SearchType.Any)) })
                        Box(Modifier.width(8.dp))
                    }
                    ActionButton("Concluído", onClose, primary = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    FilterColumn("Data de envio", Modifier.weight(1f)) {
                        SearchDate.entries.forEach { d -> FilterOption(d.label, filters.date == d) { onChange(filters.copy(date = d)) } }
                    }
                    FilterColumn("Tipo", Modifier.weight(1f)) {
                        SearchType.entries.forEach { t -> FilterOption(t.label, filters.type == t && !filters.channelsOnly) { onChange(filters.copy(type = t, channelsOnly = false)) } }
                    }
                    FilterColumn("Duração", Modifier.weight(1f)) {
                        SearchDuration.entries.forEach { d -> FilterOption(d.label, filters.duration == d) { onChange(filters.copy(duration = d)) } }
                    }
                    FilterColumn("Recursos", Modifier.weight(1.2f)) {
                        FilterOption("Ao vivo", filters.liveOnly) { onChange(filters.copy(liveOnly = !filters.liveOnly)) }
                        SearchFeature.entries.forEach { f ->
                            FilterOption(f.label, f in filters.features) {
                                onChange(filters.copy(features = if (f in filters.features) filters.features - f else filters.features + f))
                            }
                        }
                    }
                    FilterColumn("Ordenar por", Modifier.weight(1f)) {
                        SearchSort.entries.forEach { s -> FilterOption(s.label, filters.sort == s) { onChange(filters.copy(sort = s)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterColumn(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(18.dp)) { if (selected) Icon(Icons.Rounded.Check, null, tint = ApexColors.Accent, modifier = Modifier.width(18.dp)) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) ApexColors.OnSurface else ApexColors.OnSurface.copy(alpha = 0.8f), maxLines = 1)
    }
}

@Composable
private fun SkeletonRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonCard(Modifier.width(246.dp))
    }
}
