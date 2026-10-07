package app.apex.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.ui.components.FilterTextField
import app.apex.ui.components.ChipMenu
import app.apex.ui.components.ActionButton
import app.apex.state.ViewerSort
import app.apex.state.ViewerRange
import app.apex.state.CATEGORY_LANGUAGES
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import app.apex.model.Platform
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.state.withoutBlocked
import app.apex.theme.color
import app.apex.ui.components.ApexChip
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.CategoryCard
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.MediaRow
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems

@Composable
fun LiveScreen(initial: LiveFilter) {
    val app = LocalApp.current
    val live = app.screens.live
    LaunchedEffect(initial) { live.filter = initial }
    val filter = live.filter
    LaunchedEffect(filter) { live.start() }

    val state = rememberLazyGridState()
    val settings by app.data.settings.collectAsState()
    val blocked = settings.blockedChannels
    val followed by live.followed.collectAsState()
    val list = live.current()
    val items by list.items.collectAsState()
    val loading by list.loading.collectAsState()
    val error by list.error.collectAsState()
    val loaded by list.loaded.collectAsState()
    OnNearEnd(state) { list.loadMore() }

    ApexGrid(state) {
        fullSpan("filters") {
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(LiveFilter.entries.toList()) { f ->
                    val dot = when (f) {
                        LiveFilter.All -> null
                        LiveFilter.YouTube -> Platform.YouTube.color()
                        LiveFilter.Twitch -> Platform.Twitch.color()
                        LiveFilter.Kick -> Platform.Kick.color()
                    }
                    ApexChip(f.label, filter == f, { live.filter = f }, dot = dot)
                }
            }
        }

        if (followed.isNotEmpty()) {
            fullSpan("followed") {
                Column {
                    SectionTitle("Seus canais ao vivo", Modifier.padding(bottom = 12.dp), "${followed.size} no ar agora")
                    MediaRow(followed.withoutBlocked(blocked))
                }
            }
        }

        if (filter == LiveFilter.All || filter == LiveFilter.Twitch) {
            fullSpan("tw-cats") { CategoryRow("Categorias na Twitch", Platform.Twitch) }
        }
        if (filter == LiveFilter.All || filter == LiveFilter.Kick) {
            fullSpan("kick-cats") { CategoryRow("Categorias na Kick", Platform.Kick) }
        }

        fullSpan("top-title") {
            SectionTitle(
                if (filter == LiveFilter.All) "Em alta ao vivo" else "Em alta na ${filter.label}",
                Modifier.padding(top = 4.dp),
            )
        }
        val shown = items.withoutBlocked(blocked)
        when {
            shown.isNotEmpty() -> videoItems(shown)
            !loaded || loading -> skeletons(12)
            error != null -> fullSpan("err") { ErrorBox(error.orEmpty(), { list.refresh() }) }
            else -> fullSpan("empty") {
                EmptyState(Icons.Rounded.Sensors, "Ninguém ao vivo", "Nenhuma live encontrada agora. Tente atualizar em instantes.")
            }
        }
        if (loading && shown.isNotEmpty()) skeletons(4)
    }
}

@Composable
private fun CategoryRow(title: String, platform: Platform) {
    val app = LocalApp.current
    val source = if (platform == Platform.Twitch) app.screens.live.twitchCategories else app.screens.live.kickCategories
    val cats by source.value.collectAsState()
    if (cats.isEmpty()) return
    Column {
        SectionTitle(title, Modifier.padding(bottom = 12.dp))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cats, key = { it.id + it.name }) { c ->
                CategoryCard(c, { app.nav.push(Route.Category(platform, c)) }, Modifier.width(136.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryScreen(platform: Platform, category: app.apex.model.LiveCategory) {
    val app = LocalApp.current
    val cat = remember(platform, category.id) { app.screens.category(platform, category) }
    LaunchedEffect(cat) { cat.streams.loadIfNeeded() }
    val items by cat.streams.items.collectAsState()
    val loading by cat.streams.loading.collectAsState()
    val error by cat.streams.error.collectAsState()
    val settings by app.data.settings.collectAsState()
    val state = rememberLazyGridState()
    OnNearEnd(state) { cat.streams.loadMore() }

    val shown = cat.visible(items.withoutBlocked(settings.blockedChannels))
    // Com o filtro ligado a primeira página pode render pouco: busca as páginas seguintes (a Kick entrega por páginas).
    LaunchedEffect(shown.size, items.size, loading) { if (shown.size < 12 && !loading && cat.streams.hasMore) cat.streams.loadMore() }
    val filtering = cat.query.isNotBlank() || cat.range != ViewerRange.Any

    ApexGrid(state) {
        fullSpan("title") {
            SectionTitle(
                category.name, Modifier.padding(top = 12.dp, bottom = 6.dp),
                "${platform.label} • ao vivo" + if (shown.isNotEmpty()) " • ${shown.size} ${if (shown.size == 1) "live" else "lives"}" else "",
            )
        }
        fullSpan("filters") {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipMenu("Idioma", CATEGORY_LANGUAGES, cat.language, { it.label }) { cat.selectLanguage(it) }
                ChipMenu("Ordem", ViewerSort.entries, cat.sort, { it.label }) { cat.selectSort(it) }
                ChipMenu("Público", ViewerRange.entries, cat.range, { it.label }) { cat.range = it }
                FilterTextField(cat.query, { cat.query = it }, "Buscar nesta categoria", Modifier.width(260.dp))
            }
        }
        when {
            shown.isNotEmpty() -> videoItems(shown)
            loading || !cat.streams.loaded.value -> skeletons(12)
            error != null -> fullSpan("err") { ErrorBox(error.orEmpty(), { cat.streams.refresh() }) }
            else -> fullSpan("empty") {
                EmptyState(
                    Icons.Rounded.Sensors, "Sem lives",
                    if (filtering || cat.language.code != null) "Nenhuma live com esses filtros agora. Tente outro idioma ou outra faixa de público."
                    else "Nenhuma live nesta categoria agora.",
                    action = if (filtering) {
                        { ActionButton("Limpar filtros", { cat.query = ""; cat.range = ViewerRange.Any }) }
                    } else null,
                )
            }
        }
        if (loading && shown.isNotEmpty()) skeletons(4)
    }
}
