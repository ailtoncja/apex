package app.apex.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.Tune
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
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.source.SearchDate
import app.apex.source.SearchDuration
import app.apex.source.SearchFeature
import app.apex.source.SearchFilters
import app.apex.source.SearchSort
import app.apex.source.SearchType
import app.apex.state.ChannelEntry
import app.apex.state.MediaEntry
import app.apex.state.PlaylistEntry
import app.apex.state.SearchKind
import app.apex.state.SearchSources
import app.apex.state.allows
import app.apex.state.buildSearchEntries
import app.apex.state.describe
import app.apex.state.extraCount
import app.apex.state.kind
import app.apex.state.matchSubscriptions
import app.apex.state.toggled
import app.apex.state.withKind
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.ChannelRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.RemotePlaylistRow
import app.apex.ui.components.VideoRow
import app.apex.ui.components.shimmer

/** A largura máxima da lista de resultados (em janelas bem largas ela para aqui, encostada à esquerda). */
private val RESULTS_MAX_WIDTH = 1500.dp

/** A tela de resultados como a do YouTube: abas de tipo no topo, o botão de filtros e uma lista só. */
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
    val twLivesLoaded by st.twitchLives.loaded.collectAsState()
    val twChannelsLoaded by st.twitchChannels.loaded.collectAsState()
    val kickLoaded by st.kickChannels.loaded.collectAsState()
    val liveKeys by app.screens.subs.liveChannels.collectAsState()
    val subs by app.data.subscriptions.collectAsState()
    val feed by app.data.feed.collectAsState()
    val liveNow by app.screens.subs.liveNow.collectAsState()
    val platforms = st.platforms
    val onlySubs = st.onlySubs
    val kind = route.filters.kind()

    // Cada combinação de consulta e filtros do YouTube tem o seu estado: ao mudar um filtro, leva junto as plataformas e "Inscrições" ligadas.
    fun changeFilters(filters: SearchFilters) {
        app.screens.search(route.query, filters).also { it.platforms = st.platforms; it.onlySubs = st.onlySubs }
        app.nav.replaceTop(route.copy(filters = filters))
    }
    fun clearAll() {
        val clean = SearchFilters(type = SearchType.Any)
        app.screens.search(route.query, clean).also { it.platforms = emptySet(); it.onlySubs = false }
        app.nav.replaceTop(route.copy(filters = clean))
    }

    val listState = rememberLazyListState()
    var showFilters by remember { mutableStateOf(false) }
    val showYt = platforms.allows(Platform.YouTube)
    val showTw = platforms.allows(Platform.Twitch)
    val showKick = platforms.allows(Platform.Kick)
    OnNearEnd(listState) { if (showYt && !onlySubs) st.youtube.loadMore() }

    // O que a pessoa já segue e combina com a busca (canais pelo nome; vídeos e lives por título, canal e categoria).
    val mine = remember(route.query, subs, feed, liveNow, platforms) { matchSubscriptions(route.query, subs, liveNow + feed.items, platforms) }
    val followed = remember(subs) { subs.map { it.key }.toSet() }
    val entries = remember(kind, platforms, onlySubs, ytVideos, ytChannels, ytPlaylists, twLives, twChannels, kickChannels, mine, followed, blocked) {
        buildSearchEntries(
            kind, platforms, onlySubs,
            SearchSources(ytVideos, ytChannels, ytPlaylists, twLives, twChannels, kickChannels, mine, followed), blocked,
        )
    }
    val playable = remember(entries) { entries.filterIsInstance<MediaEntry>().map { it.media } }

    val waiting = !onlySubs && ((showYt && !ytLoaded) || (showTw && (!twLivesLoaded || !twChannelsLoaded)) || (showKick && !kickLoaded))
    val activeCount = route.filters.extraCount + platforms.size + (if (onlySubs) 1 else 0)

    // Os resultados começam no canto esquerdo da página (como as outras telas), sem flutuar no meio da janela.
    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopStart) {
        LazyColumn(
            Modifier.widthIn(max = RESULTS_MAX_WIDTH).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Abas de tipo (como as do YouTube) e o botão de filtros.
            item("tabs") {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SearchKind.entries) { k -> ApexChip(k.label, kind == k, { if (k != kind) changeFilters(route.filters.withKind(k)) }) }
                    }
                    FiltersButton(activeCount) { showFilters = true }
                }
            }
            // Os filtros ligados aparecem aqui, para tirar com um toque.
            if (activeCount > 0) {
                item("active") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeFilters(route.filters).forEach { (label, without) -> ApexChip("$label  ✕", true, { changeFilters(without) }) }
                        platforms.forEach { p -> ApexChip("${p.label}  ✕", true, { st.platforms = platforms - p }, dot = p.color()) }
                        if (onlySubs) ApexChip("Só inscrições  ✕", true, { st.onlySubs = false })
                    }
                }
            }

            items(entries, key = { it.key }) { e ->
                when (e) {
                    is ChannelEntry -> ChannelRow(e.channel, live = e.live != null || e.channel.key in liveKeys, liveMedia = e.live)
                    is MediaEntry -> VideoRow(e.media, thumbWidth = 300.dp, upNext = playable.filter { it.key != e.media.key })
                    is PlaylistEntry -> RemotePlaylistRow(e.playlist)
                }
            }

            when {
                entries.isEmpty() && waiting -> items(5, key = { "sk$it" }) { SkeletonResultRow() }
                entries.isEmpty() && ytError != null && showYt && !onlySubs -> item("err") { ErrorBox(ytError.orEmpty(), { st.youtube.refresh() }) }
                entries.isEmpty() -> item("none") {
                    EmptyState(
                        Icons.Rounded.Search, "Nada encontrado",
                        emptyMessage(route.query, kind, platforms, onlySubs),
                        action = if (activeCount > 0 || kind != SearchKind.All) {
                            { ActionButton("Limpar filtros", { clearAll() }, primary = true) }
                        } else null,
                    )
                }
                ytLoading && showYt && !onlySubs -> item("more") { SkeletonResultRow() }
            }
        }
    }

    if (showFilters) {
        FiltersDialog(
            route.filters, onChange = { changeFilters(it) }, onClose = { showFilters = false },
            platforms = platforms, onPlatforms = { st.platforms = it },
            onlySubs = onlySubs, onOnlySubs = { st.onlySubs = it },
            onClear = { clearAll() },
        )
    }
}

/** O que dizer quando não há resultado (cada aba e cada filtro têm o seu motivo). */
internal fun emptyMessage(query: String, kind: SearchKind, platforms: Set<Platform>, onlySubs: Boolean): String = when {
    onlySubs -> "Nenhum canal, vídeo ou live dos canais que você segue" + (if (platforms.isEmpty()) "" else " em ${platforms.describe()}") +
        " combina com “$query”. Tire o filtro de inscrições para ver tudo."
    (kind == SearchKind.Videos || kind == SearchKind.Playlists) && platforms.isNotEmpty() && Platform.YouTube !in platforms ->
        "${kind.label} só existem no YouTube. Ligue o YouTube nos filtros ou tire a plataforma."
    kind == SearchKind.Lives -> "Nenhuma live de “$query” agora" + (if (platforms.isEmpty()) "." else " em ${platforms.describe()}.")
    platforms.isNotEmpty() -> "Nada de “$query” em ${platforms.describe()}. Tente outras plataformas ou outras palavras."
    else -> "Nada para “$query”. Tente outras palavras ou tire algum filtro."
}

/** O botão "Filtros" do canto direito, como o do YouTube. */
@Composable
private fun FiltersButton(count: Int, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.clip(RoundedCornerShape(50)).hoverable(source)
            .background(if (hovered) ApexColors.SurfaceHighest else ApexColors.SurfaceHigh)
            .clickable(interactionSource = source, indication = null, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = ApexColors.OnSurface)
        Text(if (count > 0) "Filtros ($count)" else "Filtros", style = MaterialTheme.typography.labelLarge, color = ApexColors.OnSurface)
    }
}

/** Os filtros do YouTube ligados (a aba de tipo não entra: ela já aparece no topo), cada um como ficaria sem ele. */
private fun activeFilters(f: SearchFilters): List<Pair<String, SearchFilters>> = buildList {
    if (f.sort != SearchSort.Relevance) add("Ordem: ${f.sort.label}" to f.copy(sort = SearchSort.Relevance))
    if (f.date != SearchDate.Any) add(f.date.label to f.copy(date = SearchDate.Any))
    if (f.duration != SearchDuration.Any) add(f.duration.label to f.copy(duration = SearchDuration.Any))
    f.features.forEach { add(it.label to f.copy(features = f.features - it)) }
}

/** O painel de filtros, como o do YouTube: uma coluna para cada grupo. */
@Composable
internal fun FiltersDialog(
    filters: SearchFilters, onChange: (SearchFilters) -> Unit, onClose: () -> Unit,
    // "Onde procurar": só aparece quando a tela passa as plataformas (a pesquisa passa; os testes do painel sozinho não precisam).
    platforms: Set<Platform> = emptySet(), onPlatforms: ((Set<Platform>) -> Unit)? = null,
    onlySubs: Boolean = false, onOnlySubs: ((Boolean) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    val anyActive = filters.activeCount > 0 || platforms.isNotEmpty() || onlySubs
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = ApexColors.SurfaceHigh, modifier = Modifier.widthIn(max = 940.dp).padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filtros de pesquisa", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (anyActive) {
                        ActionButton("Limpar tudo", { onClear?.invoke() ?: onChange(SearchFilters(type = SearchType.Any)) })
                        Box(Modifier.width(8.dp))
                    }
                    ActionButton("Concluído", onClose, primary = true)
                }
                if (onPlatforms != null && onOnlySubs != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        FilterColumn("Onde procurar", Modifier.weight(1f)) {
                            FilterOption("Todas as plataformas", platforms.isEmpty()) { onPlatforms(emptySet()) }
                            Platform.entries.forEach { p -> FilterOption(p.label, p in platforms) { onPlatforms(platforms.toggled(p)) } }
                        }
                        FilterColumn("De quem", Modifier.weight(1f)) {
                            FilterOption("De todos", !onlySubs) { onOnlySubs(false) }
                            FilterOption("Só dos canais que sigo", onlySubs) { onOnlySubs(true) }
                        }
                        Box(Modifier.weight(3.2f))
                    }
                    Text("FILTROS DO YOUTUBE", style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted)
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

/** Uma linha de resultado carregando: miniatura à esquerda e duas linhas de texto. */
@Composable
private fun SkeletonResultRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(300.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).shimmer())
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(16.dp).shimmer())
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).shimmer())
            Box(Modifier.fillMaxWidth(0.25f).height(12.dp).shimmer())
        }
    }
}
