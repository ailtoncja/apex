package app.apex.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.state.HomeSource
import app.apex.state.allows
import app.apex.state.describe
import app.apex.state.inCategories
import app.apex.state.interleave
import app.apex.state.toEnumSet
import app.apex.state.toggled
import app.apex.state.watchedCategories
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.FilterColumn
import app.apex.ui.components.FilterOption
import app.apex.ui.components.FiltersButton
import app.apex.ui.components.FiltersSheet
import app.apex.ui.components.ChipRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.MediaRow
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Quantas lives de Twitch/Kick entram nas seções "Recomendados" e "Em alta" quando o YouTube não é a única plataforma. */
private const val SIDE_LIVES = 24

@OptIn(ExperimentalLayoutApi::class)
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
    val history by app.data.history.collectAsState()
    val feedLoading by app.screens.subs.feedLoading.collectAsState()

    // Os filtros da tela inicial combinam: por exemplo "Inscrições" + "Twitch". Nenhum ligado = tudo, como sempre foi.
    val sources = settings.homeSources.toEnumSet(HomeSource.entries.toTypedArray())
    val platforms = settings.homePlatforms.toEnumSet(Platform.entries.toTypedArray())
    val everything = sources.isEmpty()
    fun on(source: HomeSource) = everything || source in sources
    val youtubeOk = platforms.allows(Platform.YouTube)
    fun setFilters(newSources: Set<HomeSource>, newPlatforms: Set<Platform>) =
        app.data.updateSettings { it.copy(homeSources = newSources.map { s -> s.name }, homePlatforms = newPlatforms.map { p -> p.name }) }

    val selected = home.selected
    val topic = home.topics[selected]
    // As listas do YouTube: recomendadas (conta) ou baseadas no que a pessoa viu, e "Em alta".
    val recsList = if (loggedIn) home.recommendations else home.discover
    val trendingShown = !everything && on(HomeSource.Trending) && youtubeOk
    val recsShown = on(HomeSource.Recs) && youtubeOk

    // Twitch e Kick não têm "recomendados" nem "em alta" como o YouTube: lá valem as lives mais vistas e as das categorias que a pessoa assiste.
    val liveSides = listOf(Platform.Twitch, Platform.Kick).filter { platforms.allows(it) }
    val sideLists = remember(liveSides) { liveSides.mapNotNull { home.topLives(it) } }
    val recLivesShown = selected == 0 && !everything && liveSides.isNotEmpty() && on(HomeSource.Recs)
    val trendLivesShown = selected == 0 && !everything && liveSides.isNotEmpty() && on(HomeSource.Trending)
    val sideItems by remember(sideLists) { combine(sideLists.map { it.items }) { parts -> interleave(parts.toList()) } }.collectAsState(emptyList())
    val sideLoaded by remember(sideLists) { combine(sideLists.map { it.loaded }) { parts -> parts.all { it } } }.collectAsState(false)
    val sideAll = sideItems.withoutBlocked(blocked).filter { platforms.allows(it.platform) }
    val watched = remember(history) { watchedCategories(history) }
    val recLives = sideAll.inCategories(watched).take(SIDE_LIVES)
    val recFallback = recLivesShown && recLives.isEmpty()
    val recLivesOut = if (recFallback) sideAll.take(SIDE_LIVES / 2) else recLives
    val trendLives = (if (recLivesShown && !recFallback) sideAll.filter { m -> recLivesOut.none { it.key == m.key } } else sideAll).take(SIDE_LIVES)
    // Uma seção de lives só aparece se tem o que mostrar (ou se ainda está carregando).
    val recLivesBlock = recLivesShown && (recLivesOut.isNotEmpty() || !sideLoaded)
    val trendLivesBlock = trendLivesShown && (trendLives.isNotEmpty() || !sideLoaded)

    // Um assunto (Jogos, Música…) com Twitch ou Kick ligados: lives dessas plataformas sobre o assunto.
    val noLives = remember { MutableStateFlow(emptyList<Media>()) }
    val notLoading = remember { MutableStateFlow(false) }
    val topicLiveOn = selected > 0 && platforms.any { it != Platform.YouTube }
    val topicLoadable = if (topicLiveOn) home.topicLives(topic) else null
    val topicLivesAll by (topicLoadable?.value ?: noLives).collectAsState()
    val topicLivesLoading by (topicLoadable?.loading ?: notLoading).collectAsState()
    val topicLives = topicLivesAll.withoutBlocked(blocked).filter { platforms.allows(it.platform) }

    val list = when {
        selected > 0 -> home.topicList(topic)
        trendingShown -> home.trending
        else -> recsList
    }
    val items by list.items.collectAsState()
    val loading by list.loading.collectAsState()
    val error by list.error.collectAsState()
    val loaded by list.loaded.collectAsState()
    val recsItems by recsList.items.collectAsState()
    val recsLoading by recsList.loading.collectAsState()
    val recsLoaded by recsList.loaded.collectAsState()
    // Só pede à rede o que vai aparecer.
    val mainShown = if (selected > 0) youtubeOk else recsShown || trendingShown
    LaunchedEffect(selected, loggedIn, sources, platforms) {
        if (selected > 0) {
            if (youtubeOk) list.loadIfNeeded()
        } else {
            if (recsShown) recsList.loadIfNeeded()
            if (trendingShown) home.trending.loadIfNeeded()
        }
        if (recLivesShown || trendLivesShown) sideLists.forEach { it.loadIfNeeded() }
        topicLoadable?.loadIfNeeded()
    }
    OnNearEnd(state) { if (mainShown) list.loadMore() }

    var showFilters by remember { mutableStateOf(false) }
    val activeCount = sources.size + platforms.size
    ApexGrid(state) {
        // A barra de assuntos ("Tudo", Jogos, Música…) como sempre foi: a primeira linha da página, que rola junto com o conteúdo. Na ponta
        // direita, o botão "Filtros" (igual ao da pesquisa) escolhe de onde vêm os vídeos e em qual plataforma; os ligados aparecem embaixo, com ✕.
        fullSpan("chips") {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { ChipRow(home.topics.map { it.label }, selected, { home.selected = it }, arrows = false) }
                    FiltersButton(activeCount) { showFilters = true }
                }
                if (activeCount > 0) {
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sources.forEach { src -> ApexChip("${src.label}  ✕", true, { setFilters(sources - src, platforms) }) }
                        platforms.forEach { p -> ApexChip("${p.label}  ✕", true, { setFilters(sources, platforms - p) }, dot = p.color()) }
                    }
                }
            }
        }

        if (selected == 0) {
            var anyShown = false

            // Seus canais ao vivo: mudam junto com os filtros (de onde vêm e em qual plataforma estão).
            val followed = followedLive.withoutBlocked(blocked).filter { platforms.allows(it.platform) }
            if (on(HomeSource.Subs) && followed.isNotEmpty()) {
                anyShown = true
                fullSpan("followed-title") {
                    SectionTitle("Seus canais ao vivo", Modifier.padding(bottom = 12.dp), "${followed.size} no ar agora" + (if (platforms.isNotEmpty()) " • ${platforms.describe()}" else ""))
                }
                fullSpan("followed-row") { MediaRow(followed) }
            }

            val top = topLive.withoutBlocked(blocked).filter { t -> platforms.allows(t.platform) && followedLive.none { it.key == t.key } }
            if (on(HomeSource.Live) && (top.isNotEmpty() || liveLoading)) {
                anyShown = true
                fullSpan("live-title") {
                    SectionTitle(
                        "Ao vivo agora", Modifier.padding(top = 4.dp, bottom = 12.dp), if (platforms.isEmpty()) "Twitch, Kick e YouTube" else platforms.describe(),
                        trailing = {
                            ActionButton("Ver tudo", {
                                // A tela Ao vivo abre com as mesmas plataformas que estão ligadas aqui.
                                app.screens.live.platforms = platforms
                                app.nav.goRoot(Route.Live(LiveFilter.All))
                            })
                        },
                    )
                }
                if (top.isNotEmpty()) fullSpan("live-row") { MediaRow(top) }
            }

            val subFeed = feed.items.withoutBlocked(blocked).take(12)
            if (on(HomeSource.Subs) && youtubeOk) {
                if (subFeed.isNotEmpty()) {
                    anyShown = true
                    fullSpan("feed-title") {
                        SectionTitle(
                            "Das suas inscrições", Modifier.padding(top = 4.dp), null,
                            trailing = { ActionButton("Ver todas", { app.nav.goRoot(Route.Subscriptions) }) },
                        )
                    }
                    videoItems(subFeed, section = "feed:")
                } else if (subs.none { it.platform == Platform.YouTube } && !feedLoading && everything) {
                    anyShown = true
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
            }

            // Recomendados: do YouTube (a conta ou o que a pessoa viu) e, com Twitch/Kick ligados, as lives das categorias que ela assiste.
            val recsTitle = if (loggedIn) "Recomendados para você" else if (history.isNotEmpty()) "Com base no que você assistiu" else "Em alta esta semana"
            if ((recsShown && !trendingShown) || (recLivesBlock && !recsShown)) {
                anyShown = true
                fullSpan("rec-title") {
                    SectionTitle(
                        if (recsShown) recsTitle else "Recomendados para você", Modifier.padding(top = 4.dp),
                        if (recLivesBlock && !recsShown) "Lives de ${platforms.describe()}" + (if (recFallback) " • ainda sem histórico, as mais vistas" else "") else null,
                    )
                }
            }
            if (recsShown && trendingShown) {
                // As duas ligadas: Recomendados vem primeiro (uma fileira curta) e Em alta embaixo.
                val recs = recsItems.withoutBlocked(blocked).take(12)
                if (recs.isNotEmpty()) {
                    anyShown = true
                    fullSpan("rec-title-yt") { SectionTitle(recsTitle, Modifier.padding(top = 4.dp)) }
                    videoItems(recs, section = "rec:")
                } else if (recsLoading || !recsLoaded) skeletons(4)
            }
            if (recLivesBlock) {
                if (recLivesOut.isNotEmpty()) {
                    anyShown = true
                    if (recsShown) fullSpan("rec-title-live") {
                        SectionTitle(
                            "Lives recomendadas", Modifier.padding(top = 8.dp),
                            platforms.describe() + (if (recFallback) " • ainda sem histórico, as mais vistas" else " • das categorias que você assiste"),
                        )
                    }
                    videoItems(recLivesOut, section = "rec-live:")
                } else if (!sideLoaded) skeletons(4)
            }

            // Em alta: os vídeos mais vistos da semana (YouTube) e as lives mais vistas (Twitch/Kick).
            if (trendingShown || trendLivesBlock) {
                anyShown = true
                fullSpan("trend-title") {
                    SectionTitle(
                        "Em alta" + if (trendingShown) " esta semana" else "", Modifier.padding(top = 4.dp),
                        if (trendLivesBlock && !trendingShown) "Lives de ${platforms.describe()}" else null,
                    )
                }
                if (trendLivesBlock) {
                    if (trendLives.isNotEmpty()) {
                        if (trendingShown) fullSpan("trend-title-live") { SectionTitle("Lives em alta", subtitle = platforms.describe()) }
                        videoItems(trendLives, section = "trend-live:")
                    } else if (!sideLoaded) skeletons(4)
                }
            }
            if (on(HomeSource.Subs) && !youtubeOk && !everything) {
                fullSpan("yt-only") {
                    Text(
                        "As novidades em vídeo das inscrições vêm do YouTube; com ${platforms.describe()} aparecem só as lives.",
                        style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!anyShown && !liveLoading && !recsShown && !trendingShown && (!(recLivesShown || trendLivesShown) || sideLoaded)) {
                fullSpan("nothing") {
                    EmptyState(
                        Icons.Rounded.Subscriptions, "Nada para mostrar com esses filtros",
                        "Nenhum dos seus canais está ao vivo em ${platforms.describe()} agora. Tire um filtro para ver mais.",
                        action = { ActionButton("Limpar filtros", { setFilters(emptySet(), emptySet()) }, primary = true) },
                    )
                }
            }
        } else if (topicLiveOn) {
            // Assunto + Twitch/Kick: as lives dessas plataformas vêm primeiro; os vídeos do YouTube só se ele estiver ligado.
            if (topicLives.isNotEmpty()) {
                fullSpan("topic-live-title") {
                    SectionTitle("Ao vivo: ${topic.label}", Modifier.padding(top = 4.dp), platforms.filter { it != Platform.YouTube }.toSet().describe())
                }
                videoItems(topicLives, section = "topic-live:")
            } else if (topicLivesLoading) skeletons(4)
            else if (!youtubeOk) fullSpan("topic-none") {
                EmptyState(
                    Icons.Rounded.Subscriptions, "Nada ao vivo sobre ${topic.label}",
                    "Nenhuma live de ${platforms.describe()} sobre esse assunto agora. Ligue o YouTube ou tire o filtro para ver vídeos.",
                    action = { ActionButton("Limpar filtros", { setFilters(sources, emptySet()) }, primary = true) },
                )
            }
            if (youtubeOk && topicLives.isNotEmpty()) fullSpan("topic-yt-title") { SectionTitle("Vídeos: ${topic.label}", Modifier.padding(top = 8.dp)) }
        }

        val shown = if (!mainShown) emptyList() else items.withoutBlocked(blocked)
        when {
            !mainShown -> {}
            shown.isNotEmpty() -> videoItems(shown, section = "main:")
            !loaded || loading -> skeletons(12)
            error != null -> fullSpan("error") { ErrorBox(error.orEmpty(), { list.refresh() }) }
        }
        if (mainShown && loading && shown.isNotEmpty()) skeletons(4)
    }

    if (showFilters) HomeFiltersDialog(sources, platforms, { newSources, newPlatforms -> setFilters(newSources, newPlatforms) }) { showFilters = false }
}

/** A janela do botão "Filtros" da tela inicial: o que mostrar (de onde vêm os vídeos) e de quais plataformas; escolhas de colunas diferentes se somam. */
@Composable
private fun HomeFiltersDialog(
    sources: Set<HomeSource>, platforms: Set<Platform>, onChange: (Set<HomeSource>, Set<Platform>) -> Unit, onClose: () -> Unit,
) {
    FiltersSheet("Filtros da tela inicial", sources.isNotEmpty() || platforms.isNotEmpty(), onClear = { onChange(emptySet(), emptySet()) }, onClose = onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            FilterColumn("O que mostrar", Modifier.weight(1f)) {
                FilterOption("Tudo", sources.isEmpty()) { onChange(emptySet(), platforms) }
                HomeSource.entries.forEach { src ->
                    FilterOption(src.label, src in sources) { onChange(if (src in sources) sources - src else sources + src, platforms) }
                }
            }
            FilterColumn("Plataforma", Modifier.weight(1f)) {
                FilterOption("Todas as plataformas", platforms.isEmpty()) { onChange(sources, emptySet()) }
                Platform.entries.forEach { p -> FilterOption(p.label, p in platforms) { onChange(sources, platforms.toggled(p)) } }
            }
            Box(Modifier.weight(2.4f))
        }
    }
}
