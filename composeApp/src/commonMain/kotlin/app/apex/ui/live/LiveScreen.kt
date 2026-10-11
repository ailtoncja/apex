package app.apex.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
import app.apex.ui.components.SkeletonCard
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import app.apex.source.SearchDate
import app.apex.source.SearchSort
import app.apex.state.CategoryTab
import kotlinx.coroutines.flow.combine
import app.apex.state.interleave
import app.apex.state.toggled
import app.apex.state.describe
import app.apex.state.allows
import app.apex.state.youtubeGameCategories
import app.apex.state.YOUTUBE_TOPIC_CATEGORIES
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
import app.apex.ui.components.ChipBar
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.MediaRow
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.ScrollRow
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(initial: LiveFilter) {
    val app = LocalApp.current
    val live = app.screens.live
    // Só uma plataforma vinda da rota muda o filtro; "Tudo" mantém o que a pessoa já tinha ligado (ao voltar de um vídeo, por exemplo).
    LaunchedEffect(initial) { if (initial != LiveFilter.All) live.select(initial) }
    val platforms = live.platforms
    LaunchedEffect(platforms) { live.start() }

    val state = rememberLazyGridState()
    val settings by app.data.settings.collectAsState()
    val blocked = settings.blockedChannels
    val followedAll by live.followed.collectAsState()
    // "Seus canais ao vivo" muda junto com as plataformas escolhidas.
    val followed = followedAll.withoutBlocked(blocked).filter { platforms.allows(it.platform) }
    val lists = live.lists()
    val items by remember(lists) { combine(lists.map { it.items }) { parts -> interleave(parts.toList()) } }.collectAsState(emptyList())
    val loading by remember(lists) { combine(lists.map { it.loading }) { parts -> parts.any { it } } }.collectAsState(false)
    val loaded by remember(lists) { combine(lists.map { it.loaded }) { parts -> parts.all { it } } }.collectAsState(false)
    val error by remember(lists) { combine(lists.map { it.error }) { parts -> parts.firstNotNullOfOrNull { it } } }.collectAsState(null)
    OnNearEnd(state) { lists.forEach { it.loadMore() } }

    ApexGrid(state) {
        fullSpan("filters") {
            // Dá para ligar duas plataformas ao mesmo tempo (YouTube + Twitch, por exemplo); "Tudo" desliga os filtros.
            ChipBar(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                item(key = "all") { ApexChip("Tudo", platforms.isEmpty(), { live.platforms = emptySet() }) }
                items(Platform.entries, key = { it.name }) { p -> ApexChip(p.label, p in platforms, { live.platforms = platforms.toggled(p) }, dot = p.color()) }
            }
        }

        if (followed.isNotEmpty()) {
            fullSpan("followed") {
                Column {
                    SectionTitle("Seus canais ao vivo", Modifier.padding(bottom = 12.dp), "${followed.size} no ar agora")
                    MediaRow(followed)
                }
            }
        }

        if (platforms.allows(Platform.Twitch)) fullSpan("tw-cats") { CategoryRow("Categorias na Twitch", Platform.Twitch) }
        if (platforms.allows(Platform.Kick)) fullSpan("kick-cats") { CategoryRow("Categorias na Kick", Platform.Kick) }
        if (platforms.allows(Platform.YouTube)) {
            fullSpan("yt-games") { YouTubeCategoryRow("Jogos no YouTube", games = true) }
            fullSpan("yt-topics") { YouTubeCategoryRow("Assuntos no YouTube", games = false) }
        }

        fullSpan("top-title") {
            SectionTitle(
                when (platforms.size) { 0 -> "Em alta ao vivo"; 1 -> "Em alta na ${platforms.first().label}"; else -> "Em alta: ${platforms.describe()}" },
                Modifier.padding(top = 4.dp),
            )
        }
        val shown = items.withoutBlocked(blocked)
        when {
            shown.isNotEmpty() -> videoItems(shown)
            !loaded || loading -> skeletons(12)
            error != null -> fullSpan("err") { ErrorBox(error.orEmpty(), { lists.forEach { it.refresh() } }) }
            else -> fullSpan("empty") {
                EmptyState(Icons.Rounded.Sensors, "Ninguém ao vivo", "Nenhuma live encontrada agora. Tente atualizar em instantes.")
            }
        }
        if (loading && shown.isNotEmpty()) skeletons(4)
    }
}

/**
 * Uma fileira de categorias que carrega do servidor. Se a carga falhar (ou vier vazia), tenta de novo sozinha algumas vezes
 * e, se não der, mostra o aviso com um botão para tentar de novo: a fileira nunca some sem explicação.
 */
@Composable
private fun CategoryRow(title: String, platform: Platform) {
    val app = LocalApp.current
    val source = if (platform == Platform.Twitch) app.screens.live.twitchCategories else app.screens.live.kickCategories
    CategoryRowContent(title, source, "as categorias da ${platform.label}") { c -> app.nav.push(Route.Category(platform, c)) }
}

@Composable
internal fun CategoryRowContent(
    title: String, source: app.apex.state.Loadable<List<app.apex.model.LiveCategory>>, what: String, onOpen: (app.apex.model.LiveCategory) -> Unit,
) {
    val cats by source.value.collectAsState()
    CategoryLoadGuard(source)
    if (cats.isEmpty()) {
        CategoryRowPlaceholder(title, source, what)
        return
    }
    Column {
        SectionTitle(title, Modifier.padding(bottom = 12.dp))
        ScrollRow(arrowCenterY = 68.dp) {
            items(cats, key = { it.id + it.name }) { c -> CategoryCard(c, { onOpen(c) }, Modifier.width(136.dp)) }
        }
    }
}

/** Tenta de novo (3 vezes, com pausa maior a cada uma) quando a lista de categorias terminou de carregar vazia. */
@Composable
private fun CategoryLoadGuard(source: app.apex.state.Loadable<List<app.apex.model.LiveCategory>>) {
    val cats by source.value.collectAsState()
    val loaded by source.loaded.collectAsState()
    val loading by source.loading.collectAsState()
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(cats.isEmpty(), loaded, loading, tries) {
        if (cats.isEmpty() && loaded && !loading && tries < 3) {
            kotlinx.coroutines.delay(2_500L * (tries + 1))
            tries++
            source.reload()
        }
    }
}

/** No lugar da fileira vazia: o título e "carregando" ou, se falhou, o aviso com o botão de tentar de novo. */
@Composable
private fun CategoryRowPlaceholder(title: String, source: app.apex.state.Loadable<List<app.apex.model.LiveCategory>>, what: String) {
    val loaded by source.loaded.collectAsState()
    val loading by source.loading.collectAsState()
    val error by source.error.collectAsState()
    Column {
        SectionTitle(title, Modifier.padding(bottom = 12.dp))
        if (!loaded || loading) {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(7) { SkeletonCard(Modifier.width(136.dp)) }
            }
        } else {
            ErrorBox(error ?: "Não consegui carregar $what agora.", { source.reload() })
        }
    }
}

/** Categorias do YouTube: os jogos mais assistidos (com a capa que a Twitch tem) e assuntos como Música e Futebol. */
@Composable
private fun YouTubeCategoryRow(title: String, games: Boolean) {
    val app = LocalApp.current
    val twitchCats by app.screens.live.twitchCategories.value.collectAsState()
    LaunchedEffect(games) { if (games) app.screens.live.twitchCategories.loadIfNeeded() }
    if (games) CategoryLoadGuard(app.screens.live.twitchCategories)
    val cats = if (games) youtubeGameCategories(twitchCats) else YOUTUBE_TOPIC_CATEGORIES
    if (cats.isEmpty()) {
        // Os jogos vêm da lista da Twitch: sem ela, mostra o mesmo aviso em vez de sumir.
        if (games) CategoryRowPlaceholder(title, app.screens.live.twitchCategories, "os jogos mais vistos")
        return
    }
    Column {
        SectionTitle(title, Modifier.padding(bottom = 12.dp))
        ScrollRow(arrowCenterY = 68.dp) {
            items(cats, key = { it.id }) { c -> CategoryCard(c, { app.nav.push(Route.Category(Platform.YouTube, c)) }, Modifier.width(136.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryScreen(platform: Platform, category: app.apex.model.LiveCategory) {
    val app = LocalApp.current
    val cat = remember(platform, category.id) { app.screens.category(platform, category) }
    val list = cat.current
    LaunchedEffect(cat, list) { list.loadIfNeeded() }
    val items by list.items.collectAsState()
    val loading by list.loading.collectAsState()
    val loaded by list.loaded.collectAsState()
    val error by list.error.collectAsState()
    val settings by app.data.settings.collectAsState()
    val state = rememberLazyGridState()
    OnNearEnd(state) { list.loadMore() }

    val videosTab = platform == Platform.YouTube && cat.tab == CategoryTab.Videos
    val shown = cat.visible(items.withoutBlocked(settings.blockedChannels))
    // Com o filtro ligado a primeira página pode render pouco: busca as páginas seguintes (a Kick entrega por páginas).
    LaunchedEffect(shown.size, items.size, loading, list) { if (shown.size < 12 && !loading && list.hasMore) list.loadMore() }
    val filtering = cat.query.isNotBlank() || (!videosTab && cat.range != ViewerRange.Any)

    ApexGrid(state) {
        fullSpan("title") {
            SectionTitle(
                category.name, Modifier.padding(top = 12.dp, bottom = 6.dp),
                "${platform.label} • ${if (videosTab) "vídeos" else "ao vivo"}" +
                    if (shown.isNotEmpty()) " • ${shown.size} ${if (videosTab) (if (shown.size == 1) "vídeo" else "vídeos") else (if (shown.size == 1) "live" else "lives")}" else "",
            )
        }
        fullSpan("filters") {
            // Os filtros andam pelas setinhas; a caixa de busca fica fixa à direita.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChipBar(Modifier.weight(1f)) {
                    if (platform == Platform.YouTube) items(CategoryTab.entries, key = { "t-" + it.name }) { t -> ApexChip(t.label, cat.tab == t, { cat.selectTab(t) }) }
                    if (cat.hasLanguage) {
                        item(key = "lang") { ChipMenu("Idioma", CATEGORY_LANGUAGES, cat.language, { it.label }) { cat.selectLanguage(it) } }
                        item(key = "order") { ChipMenu("Ordem", ViewerSort.entries, cat.sort, { it.label }) { cat.selectSort(it) } }
                    }
                    if (videosTab) {
                        item(key = "vsort") { ChipMenu("Ordenar", listOf(SearchSort.Relevance, SearchSort.UploadDate, SearchSort.Views, SearchSort.Rating), cat.videoSort, { it.label }) { cat.selectVideoSort(it) } }
                        item(key = "vdate") { ChipMenu("Data", SearchDate.entries, cat.videoDate, { it.label }) { cat.selectVideoDate(it) } }
                    } else {
                        item(key = "range") { ChipMenu("Público", ViewerRange.entries, cat.range, { it.label }) { cat.range = it } }
                    }
                }
                FilterTextField(cat.query, { cat.query = it }, "Buscar nesta categoria", Modifier.width(260.dp))
            }
        }
        when {
            shown.isNotEmpty() -> videoItems(shown)
            loading || !loaded -> skeletons(12)
            error != null -> fullSpan("err") { ErrorBox(error.orEmpty(), { list.refresh() }) }
            else -> fullSpan("empty") {
                EmptyState(
                    Icons.Rounded.Sensors, if (videosTab) "Sem vídeos" else "Sem lives",
                    if (filtering || (cat.hasLanguage && cat.language.code != null)) "Nada com esses filtros agora. Tente outro idioma ou outra faixa de público."
                    else if (videosTab) "Nenhum vídeo encontrado para este assunto." else "Nenhuma live nesta categoria agora.",
                    action = if (filtering) {
                        { ActionButton("Limpar filtros", { cat.query = ""; cat.range = ViewerRange.Any }) }
                    } else null,
                )
            }
        }
        if (loading && shown.isNotEmpty()) skeletons(4)
    }
}
