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
import app.apex.state.allows
import app.apex.state.describe
import app.apex.state.toggled
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
    val platforms = st.platforms
    val onlySubs = st.onlySubs
    // Cada combinação de consulta e filtros do YouTube tem o seu estado: ao mudar um filtro, leva junto as plataformas e "Inscrições" ligadas.
    fun changeFilters(filters: SearchFilters) {
        app.screens.search(route.query, filters).also { it.platforms = st.platforms; it.onlySubs = st.onlySubs }
        app.nav.replaceTop(route.copy(filters = filters))
    }
    // Com uma plataforma só, a lista mostra mais resultados dela; com várias (ou todas), um pouco de cada.
    val single = platforms.size == 1
    val listState = rememberLazyListState()
    var showFilters by remember { mutableStateOf(false) }

    val showYt = platforms.allows(Platform.YouTube)
    val showTw = platforms.allows(Platform.Twitch)
    val showKick = platforms.allows(Platform.Kick)
    OnNearEnd(listState) { if (showYt && !onlySubs) st.youtube.loadMore() }

    // O que a pessoa já segue e combina com a busca (canais pelo nome; vídeos e lives por título, canal e categoria).
    val mine = remember(route.query, subs, feed, liveNow, platforms) { matchSubscriptions(route.query, subs, liveNow + feed.items, platforms) }
    val mineMedia = mine.media.withoutBlocked(blocked)

    val channelLists = listOf(
        if (showYt) ytChannels.take(if (single) 8 else 3).map { it to false } else emptyList(),
        if (showTw) twChannels.take(if (single) 8 else 3).map { it.channel to (it.live != null) } else emptyList(),
        if (showKick) kickChannels.take(if (single) 8 else 3).map { it.channel to (it.live != null) } else emptyList(),
    )
    val channels: List<Pair<Channel, Boolean>> =
        (if (single) channelLists.flatten() else interleave(channelLists)).filter { it.first.key !in blocked }

    val twitchLives = if (showTw) twLives.take(if (single) 24 else 12) else emptyList()
    val kickLives = if (showKick) kickChannels.mapNotNull { it.live }.take(if (single) 12 else 6) else emptyList()
    val ytLives = if (showYt) ytVideos.filter { it.isLive }.take(if (single) 12 else 6) else emptyList()
    val lives: List<Media> = interleave(listOf(twitchLives, kickLives, ytLives)).withoutBlocked(blocked)
    val showLivesAsList = single && platforms.first() != Platform.YouTube

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
                    // Os filtros combinam: por exemplo "Inscrições" + "Twitch" mostra só o que vem dos canais da Twitch que a pessoa segue.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApexChip("Tudo", platforms.isEmpty() && !onlySubs, { st.platforms = emptySet(); st.onlySubs = false })
                        Platform.entries.forEach { p -> ApexChip(p.label, p in platforms, { st.platforms = platforms.toggled(p) }, dot = p.color()) }
                        if (subs.isNotEmpty()) {
                            val label = if (!mine.isEmpty) "Inscrições (${mine.channels.size + mineMedia.size})" else "Inscrições"
                            ApexChip(label, onlySubs, { st.onlySubs = !onlySubs })
                        }
                    }
                    if (showYt && !onlySubs) {
                        // Filtros no estilo do YouTube: um botão que abre o painel e os que estão ligados aparecem aqui, para tirar com um toque.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ApexChip(if (route.filters.activeCount > 0) "Filtros (${route.filters.activeCount})" else "Filtros", false, { showFilters = true })
                            activeFilters(route.filters).forEach { (label, without) ->
                                ApexChip("$label  ✕", true, { changeFilters(without) })
                            }
                        }
                    }
                }
            }

            // 1) o que combina entre as inscrições da pessoa
            if (!onlySubs && !mine.isEmpty) {
                item("mine") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(
                            "Das suas inscrições", subtitle = "${mine.channels.size} canais • ${mineMedia.size} vídeos e lives",
                            trailing = { ActionButton("Ver tudo", { st.onlySubs = true }) },
                        )
                        mine.channels.take(3).forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                        if (mineMedia.isNotEmpty()) MediaRow(mineMedia.take(10))
                    }
                }
            }
            if (onlySubs) {
                if (mine.isEmpty) item("mine-none") {
                    EmptyState(
                        Icons.Rounded.Search, "Nada nas suas inscrições",
                        "Nenhum canal, vídeo ou live dos canais que você segue" + (if (platforms.isEmpty()) "" else " em ${platforms.describe()}") +
                            " combina com “${route.query}”. Tire o filtro Inscrições para ver tudo.",
                    )
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
            if (!onlySubs && channels.isNotEmpty()) {
                item("channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("Canais", Modifier.padding(bottom = 4.dp))
                        channels.take(if (single) 8 else 6).forEach { (ch, live) -> ChannelRow(ch, live = live || ch.key in liveKeys) }
                    }
                }
            }

            // 3) lives
            if (!onlySubs && lives.isNotEmpty()) {
                item("lives-title") { SectionTitle("Ao vivo agora", subtitle = "${lives.size} ${if (lives.size == 1) "transmissão" else "transmissões"}") }
                if (showLivesAsList) {
                    items(lives, key = { "l-" + it.key }) { VideoRow(it, upNext = lives.filter { m -> m.key != it.key }) }
                } else {
                    item("lives") { MediaRow(lives) }
                }
            }

            // 4) playlists e 5) vídeos do YouTube
            if (showYt && !onlySubs) {
                if (ytPlaylists.isNotEmpty()) {
                    item("playlists-title") { SectionTitle("Playlists") }
                    items(ytPlaylists.take(if (single) 12 else 3), key = { "p-" + it.id }) { RemotePlaylistRow(it) }
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
            } else if (!onlySubs && channels.isEmpty() && lives.isEmpty()) {
                item("none-live") {
                    EmptyState(Icons.Rounded.Search, "Nada encontrado", "Nenhum canal ou live da ${platforms.describe()} para “${route.query}”. Tente outras plataformas.")
                }
            }
        }
    }

    if (showFilters) {
        FiltersDialog(route.filters, onChange = { changeFilters(it) }, onClose = { showFilters = false })
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
