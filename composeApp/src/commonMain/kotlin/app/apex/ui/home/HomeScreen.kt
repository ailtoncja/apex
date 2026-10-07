package app.apex.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Platform
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.state.HomeSource
import app.apex.state.allows
import app.apex.state.describe
import app.apex.state.toEnumSet
import app.apex.state.toggled
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
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
    LaunchedEffect(selected, loggedIn, sources, platforms) {
        if (selected > 0) list.loadIfNeeded()
        else {
            if (recsShown) recsList.loadIfNeeded()
            if (trendingShown) home.trending.loadIfNeeded()
        }
    }
    OnNearEnd(state) { list.loadMore() }

    ApexGrid(state) {
        fullSpan("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeSource.entries.forEach { s ->
                        ApexChip(s.label, s in sources, { setFilters(if (s in sources) sources - s else sources + s, platforms) })
                    }
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(26.dp).background(ApexColors.Outline))
                    Platform.entries.forEach { p ->
                        ApexChip(p.label, p in platforms, { setFilters(sources, platforms.toggled(p)) }, dot = p.color())
                    }
                    if (sources.isNotEmpty() || platforms.isNotEmpty()) ApexChip("Limpar", false, { setFilters(emptySet(), emptySet()) })
                }
                ChipRow(home.topics.map { it.label }, selected, { home.selected = it })
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

            val top = topLive.withoutBlocked(blocked).filter { platforms.allows(it.platform) }
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

            // Recomendados (e, se escolhido, Em alta): vêm do YouTube.
            if (recsShown && !trendingShown) {
                anyShown = true
                fullSpan("rec-title") {
                    SectionTitle(
                        if (loggedIn) "Recomendados para você"
                        else if (app.data.history.value.isNotEmpty()) "Com base no que você assistiu" else "Em alta esta semana",
                        Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (recsShown && trendingShown) {
                // As duas ligadas: Recomendados vem primeiro (uma fileira curta) e Em alta embaixo.
                val recs = recsItems.withoutBlocked(blocked).take(12)
                if (recs.isNotEmpty()) {
                    anyShown = true
                    fullSpan("rec-title") { SectionTitle(if (loggedIn) "Recomendados para você" else "Com base no que você assistiu", Modifier.padding(top = 4.dp)) }
                    videoItems(recs, section = "rec:")
                } else if (recsLoading || !recsLoaded) skeletons(4)
            }
            if (trendingShown) {
                anyShown = true
                fullSpan("trend-title") { SectionTitle("Em alta esta semana", Modifier.padding(top = 4.dp)) }
            }
            if (!youtubeOk && (on(HomeSource.Recs) || on(HomeSource.Trending) || on(HomeSource.Subs)) && !everything) {
                fullSpan("yt-only") {
                    Text(
                        "Recomendados, Em alta e as novidades das inscrições vêm do YouTube; com ${platforms.describe()} só aparecem as lives.",
                        style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!anyShown && !liveLoading && !recsShown && !trendingShown) {
                fullSpan("nothing") {
                    EmptyState(
                        Icons.Rounded.Subscriptions, "Nada para mostrar com esses filtros",
                        "Nenhum dos seus canais está ao vivo em ${platforms.describe()} agora. Tire um filtro para ver mais.",
                        action = { ActionButton("Limpar filtros", { setFilters(emptySet(), emptySet()) }, primary = true) },
                    )
                }
            }
        }

        val shown = if (selected == 0 && !(recsShown || trendingShown)) emptyList() else items.withoutBlocked(blocked)
        when {
            shown.isNotEmpty() -> videoItems(shown, section = "main:")
            selected == 0 && !(recsShown || trendingShown) -> {}
            !loaded || loading -> skeletons(12)
            error != null -> fullSpan("error") { ErrorBox(error.orEmpty(), { list.refresh() }) }
        }
        if (loading && shown.isNotEmpty()) skeletons(4)
    }
}
