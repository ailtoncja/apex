package app.apex.ui.subs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.state.matchSubscriptions
import app.apex.state.matchesWords
import app.apex.state.channelsIn
import app.apex.state.describe
import app.apex.state.mediaIn
import app.apex.state.toggled
import app.apex.state.searchWords
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.Avatar
import app.apex.ui.components.ChannelRow
import app.apex.ui.components.ChipRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.FilterTextField
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.fullSpan
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems

private enum class SubsTab { News, Live, Supported, Channels }

private val PLATFORMS = listOf(Platform.YouTube, Platform.Twitch, Platform.Kick)

@OptIn(ExperimentalLayoutApi::class)
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
    var requestedTab by remember { mutableStateOf(SubsTab.News) }
    // Dá para ligar mais de uma plataforma ao mesmo tempo (vazio = todas).
    var platforms by remember { mutableStateOf<Set<Platform>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    val state = rememberLazyGridState()
    val blocked = settings.blockedChannels
    val words = searchWords(query)
    val searching = words.isNotEmpty()

    // Tudo abaixo respeita a plataforma escolhida e as palavras digitadas.
    val subsOnPlatform = subs.channelsIn(platforms)
    val channelsShown = subsOnPlatform.filter { matchesWords(words, it.name, it.handle) }
    val newsShown = feed.items.withoutBlocked(blocked).mediaIn(platforms).filter { matchesWords(words, it.title, it.channel?.name, it.category) }
    val liveShown = liveNow.withoutBlocked(blocked).mediaIn(platforms).filter { matchesWords(words, it.title, it.channel?.name, it.category) }

    // Canais que a pessoa paga (sub da Twitch, membro do YouTube) ficam separados dos outros.
    val supported = channelsShown.filter { it.support != null }
    val supportedKeys = subs.filter { it.support != null }.map { it.key }.toSet()
    val order = listOfNotNull(SubsTab.News, SubsTab.Live, SubsTab.Supported.takeIf { subs.any { it.support != null } }, SubsTab.Channels)
    val tab = requestedTab.takeIf { it in order } ?: SubsTab.News
    val labels = order.map {
        when (it) {
            SubsTab.News -> "Novidades (${newsShown.size})"
            SubsTab.Live -> "Ao vivo (${liveShown.size})"
            SubsTab.Supported -> "Apoiados (${supported.size})"
            SubsTab.Channels -> "Canais (${channelsShown.size})"
        }
    }

    ApexGrid(state) {
        if (subs.isNotEmpty()) {
            // Filtro por plataforma e busca por palavra-chave ou canal.
            fullSpan("filters") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ApexChip("Todas (${subs.size})", platforms.isEmpty(), { platforms = emptySet() })
                    PLATFORMS.forEach { p ->
                        val count = subs.count { it.platform == p }
                        if (count > 0) ApexChip("${p.label} ($count)", p in platforms, { platforms = platforms.toggled(p) }, dot = p.color())
                    }
                    FilterTextField(query, { query = it }, "Buscar nas suas inscrições (canal ou palavra-chave)", Modifier.width(380.dp))
                }
            }
            if (!searching) {
                fullSpan("avatars") {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(subsOnPlatform.sortedWith(compareByDescending<Channel> { it.key in liveKeys }.thenByDescending { it.support != null }), key = { it.key }) { ch ->
                            Column(
                                Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable { app.openChannel(ch) }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Avatar(ch.avatarUrl, ch.name, 56.dp, ring = if (ch.key in liveKeys) ApexColors.Live else if (ch.support != null) ApexColors.Support else null)
                                Text(
                                    ch.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
        fullSpan("tabs") {
            ChipRow(labels, order.indexOf(tab), { requestedTab = order[it] })
        }

        when {
            subs.isEmpty() -> fullSpan("empty") {
                EmptyState(
                    Icons.Rounded.Subscriptions, "Nenhuma inscrição ainda",
                    "Siga canais do YouTube, Twitch e Kick para ver tudo reunido aqui. Você pode importar direto das suas contas.",
                    action = { ActionButton("Importar das contas", { app.nav.goRoot(Route.Settings) }, primary = true) },
                )
            }
            tab == SubsTab.News -> {
                if (newsShown.isNotEmpty()) videoItems(newsShown)
                else if (feedLoading && !searching) skeletons(12)
                else fullSpan("nofeed") { NothingFound(searching, query, platforms, "Sem vídeos novos", newsHint(subs, platforms)) }
                if (feedLoading && newsShown.isNotEmpty()) fullSpan("loading") {
                    Text("Atualizando…", color = ApexColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            tab == SubsTab.Live -> {
                if (liveShown.isNotEmpty()) videoItems(liveShown)
                else fullSpan("nolive") { NothingFound(searching, query, platforms, "Ninguém ao vivo", "Nenhum dos seus canais está transmitindo agora.") }
            }
            tab == SubsTab.Supported -> {
                if (supported.isNotEmpty()) {
                    fullSpan("supported") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SectionTitle(
                                "Canais que você apoia", Modifier.padding(bottom = 8.dp),
                                subtitle = "Subs da Twitch e memberships do YouTube. Atualizado ao abrir o app e ao importar as contas.",
                            )
                            supported.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                        }
                    }
                    // O que esses canais têm de novo: ao vivo agora e vídeos recentes.
                    val mine = (liveShown + newsShown).filter { it.channel?.key in supportedKeys }.distinctBy { it.key }
                    if (mine.isNotEmpty()) {
                        fullSpan("supported-news") { SectionTitle("Novidades dos canais apoiados", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                        videoItems(mine)
                    }
                } else fullSpan("nosupported") { NothingFound(searching, query, platforms, "Nenhum canal apoiado", "Os canais que você paga (sub da Twitch, membro do YouTube) aparecem aqui.") }
            }
            else -> {
                if (channelsShown.isEmpty()) fullSpan("nochannels") { NothingFound(searching, query, platforms, "Nenhum canal", "Nenhum canal para mostrar.") }
                // Os canais separados por plataforma (os que a pessoa apoia vêm primeiro em cada grupo).
                PLATFORMS.forEach { p ->
                    val inGroup = channelsShown.filter { it.platform == p }.sortedByDescending { it.support != null }
                    if (inGroup.isNotEmpty()) {
                        fullSpan("group-${p.name}") {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SectionTitle(
                                    p.label, Modifier.padding(top = 10.dp, bottom = 6.dp),
                                    "${inGroup.size} ${if (inGroup.size == 1) "canal" else "canais"}" +
                                        inGroup.count { it.key in liveKeys }.takeIf { it > 0 }?.let { " • $it ao vivo" }.orEmpty(),
                                )
                                inGroup.forEach { ch -> ChannelRow(ch, live = ch.key in liveKeys) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun newsHint(subs: List<Channel>, platforms: Set<Platform>): String = when {
    platforms.isNotEmpty() && Platform.YouTube !in platforms -> "Vídeos novos existem só para canais do YouTube; Twitch e Kick ficam na aba Ao vivo."
    subs.none { it.platform == Platform.YouTube } -> "Os vídeos novos aparecem aqui para canais do YouTube. Twitch e Kick ficam na aba Ao vivo."
    else -> "Nada novo por enquanto."
}

@Composable
private fun NothingFound(searching: Boolean, query: String, platforms: Set<Platform>, emptyTitle: String, emptyMessage: String) {
    val app = LocalApp.current
    if (searching) {
        EmptyState(
            Icons.Rounded.Search, "Nada encontrado nas suas inscrições",
            "Nenhum resultado para “$query”" + (if (platforms.isEmpty()) "" else " em ${platforms.describe()}") + ". Quer procurar no YouTube, na Twitch e na Kick?",
            action = { ActionButton("Buscar “$query” em tudo", { app.search(query) }, primary = true) },
        )
    } else EmptyState(Icons.Rounded.Subscriptions, emptyTitle, emptyMessage)
}
