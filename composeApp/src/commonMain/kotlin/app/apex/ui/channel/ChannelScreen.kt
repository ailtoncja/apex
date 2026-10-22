package app.apex.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.ChannelSort
import app.apex.model.PlaylistSort
import app.apex.source.orderPlaylists
import app.apex.model.sortedBy
import app.apex.model.ClipSort
import app.apex.model.Platform
import app.apex.ui.components.FilterTextField
import androidx.compose.foundation.layout.width
import app.apex.state.matchesWords
import app.apex.state.searchWords
import app.apex.state.withoutBlocked
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.newTabClick
import app.apex.ui.components.LinkedText
import app.apex.ui.components.SelectableText
import app.apex.ui.components.ApexGrid
import app.apex.ui.components.Avatar
import app.apex.ui.components.ChipRow
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.PlatformTag
import app.apex.ui.components.RemoteImage
import app.apex.ui.components.SubscribeButton
import app.apex.ui.components.VerifiedMark
import app.apex.ui.components.VideoCard
import app.apex.ui.components.fullSpan
import app.apex.ui.components.playlistItems
import app.apex.ui.components.skeletons
import app.apex.ui.components.videoItems
import app.apex.util.formatCount

@Composable
fun ChannelScreen(seed: Channel, initialTab: Int = 0) {
    val app = LocalApp.current
    val st = remember(seed.key) { app.screens.channel(seed) }
    LaunchedEffect(st) { st.start() }

    val details by st.details.value.collectAsState()
    val liveNow by st.live.value.collectAsState()
    val settings by app.data.settings.collectAsState()
    val isYt = seed.platform == Platform.YouTube
    val channel = details?.channel?.let { it.copy(avatarUrl = it.avatarUrl ?: seed.avatarUrl) } ?: seed
    var tab by remember(seed.key) { mutableIntStateOf(initialTab) }
    val tabs = if (isYt) listOf("Vídeos", "Transmissões", "Playlists", "Sobre") else listOf("Ao vivo", "VODs", "Clipes", "Sobre")

    // A ordem de cada aba: mais recentes, mais vistos e mais antigos (nos vídeos e transmissões do YouTube e nos VODs da Twitch e da Kick).
    val sortOptions = when {
        isYt && (tab == 0 || tab == 1) -> ChannelSort.entries
        !isYt && tab == 1 -> ChannelSort.entries
        else -> emptyList()
    }
    val clipsTab = !isYt && tab == 2
    val sort = when {
        isYt && tab == 0 -> st.videoSort
        isYt && tab == 1 -> st.liveSort
        !isYt && tab == 1 -> st.vodSort
        else -> ChannelSort.Recent
    }
    val list = when {
        isYt && tab == 0 -> st.videosBy(st.videoSort)
        isYt && tab == 1 -> st.pastLivesBy(st.liveSort)
        !isYt && tab == 1 -> st.vodsBy(st.vodSort)
        !isYt && tab == 2 -> st.clips
        else -> null
    }
    // As playlists na ordem escolhida: as do YouTube vêm de lá; "Mais antigas" e "A–Z" precisam da lista inteira (ver loadAll abaixo).
    val playlistList = st.playlistsBy(st.playlistSort)
    val loadedPlaylists by playlistList.items.collectAsState()
    val allPlaylists = remember(loadedPlaylists, st.playlistSort) { orderPlaylists(loadedPlaylists, st.playlistSort) }
    val playlistsLoading by playlistList.loading.collectAsState()
    val playlistsError by playlistList.error.collectAsState()
    LaunchedEffect(tab, playlistList, st.playlistSort) {
        if (isYt && tab == 2 && (st.playlistSort == PlaylistSort.Oldest || st.playlistSort == PlaylistSort.Name)) { playlistList.loadIfNeeded(); st.loadAll(playlistList) }
    }

    // Pesquisar no canal: na aba "Vídeos" a busca é a do YouTube dentro do canal inteiro; nas outras abas filtra pelo nome o que a aba lista.
    val words = remember(st.query) { searchWords(st.query) }
    val searching = words.isNotEmpty()
    val channelSearch = if (isYt && tab == 0 && st.searchedQuery.isNotEmpty() && searching) remember(st.searchedQuery) { st.searchPaged(st.searchedQuery) } else null
    LaunchedEffect(channelSearch) { channelSearch?.loadIfNeeded() }
    // Ordenar o resultado da busca do YouTube (mais recentes, vistos, antigos) precisa dele inteiro.
    LaunchedEffect(channelSearch, st.searchSort) { if (channelSearch != null && st.searchSort != null) st.loadAll(channelSearch) }
    LaunchedEffect(tab, searching, st, list) {
        if (!searching) return@LaunchedEffect
        when {
            isYt && tab == 2 -> { playlistList.loadIfNeeded(); st.loadAll(playlistList) }
            // Na aba "Vídeos" a busca é a do YouTube; nas outras filtra pelo nome a lista inteira (na ordem que está na tela).
            tab != 0 && list != null -> { list.loadIfNeeded(); st.loadAll(list) }
        }
    }
    val playlists = if (searching) allPlaylists.filter { matchesWords(words, it.title) } else allPlaylists
    val activeList = channelSearch ?: list
    LaunchedEffect(tab, st, list) {
        if (isYt && tab == 2) playlistList.loadIfNeeded()
        list?.loadIfNeeded()
    }
    val loadedItems by (activeList?.items ?: st.videos.items).collectAsState()
    val loading by (activeList?.loading ?: st.videos.loading).collectAsState()
    val error by (activeList?.error ?: st.videos.error).collectAsState()
    // O resultado da busca do YouTube já vem filtrado (e é reordenado aqui se a pessoa escolheu uma ordem); as demais listas são filtradas
    // aqui, na ordem escolhida para a aba.
    val items = when {
        channelSearch != null -> st.searchSort?.let { sortedBy(loadedItems, it) } ?: loadedItems
        // Nos clipes, o que a busca achou pode ser reordenado (mais vistos, recentes, antigos) sem buscar de novo.
        searching -> loadedItems.filter { matchesWords(words, it.title, it.category) }.let { found ->
            if (clipsTab) st.searchSort?.let { sortedBy(found, it) } ?: found else found
        }
        else -> loadedItems
    }
    val state = rememberLazyGridState()
    // Trocar de ordem recomeça a lista: volta para o topo.
    LaunchedEffect(list, st.playlistSort) { state.scrollToItem(0) }
    OnNearEnd(state) { if (isYt && tab == 2) playlistList.loadMore() else activeList?.loadMore() }

    ApexGrid(state) {
        fullSpan("header") {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                details?.bannerUrl?.let {
                    RemoteImage(it, Modifier.fillMaxWidth().aspectRatio(6f).clip(RoundedCornerShape(16.dp)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        channel.avatarUrl, channel.name, 96.dp,
                        liveNow?.let { live -> Modifier.clip(CircleShape).newTabClick { app.openLiveInNewTab(channel, live) }.clickable { app.openLive(channel, live) } } ?: Modifier,
                        ring = if (liveNow != null) ApexColors.Live else null,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectableText(channel.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (channel.verified) VerifiedMark(Modifier.padding(top = 4.dp))
                            PlatformTag(channel.platform)
                        }
                        val meta = listOfNotNull(
                            channel.handle,
                            channel.followers?.let { "${formatCount(it)} ${if (isYt) "inscritos" else "seguidores"}" },
                        ).joinToString(" • ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
                        details?.description?.takeIf { it.isNotBlank() }?.let {
                            LinkedText(it, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("", { app.system.openUrl(channel.url.ifBlank { seed.url }) }, icon = Icons.Rounded.OpenInBrowser)
                        SubscribeButton(channel)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { ChipRow(tabs, tab, { tab = it }) }
                    val placeholder = when {
                        isYt && tab == 0 -> "Pesquisar neste canal"
                        isYt && tab == 1 -> "Pesquisar nas transmissões"
                        isYt && tab == 2 -> "Pesquisar nas playlists"
                        !isYt && tab == 1 -> "Pesquisar nos VODs"
                        !isYt && tab == 2 -> "Pesquisar nos clipes"
                        else -> null
                    }
                    if (placeholder != null) FilterTextField(st.query, st::updateQuery, placeholder, Modifier.width(300.dp))
                }
            }
        }

        liveNow?.let { live ->
            if (tab == 0 && !searching) {
                fullSpan("live-now") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Ao vivo agora", style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.fillMaxWidth()) { VideoCard(live, Modifier.fillMaxWidth(0.34f)) }
                    }
                }
            }
        }

        val aboutTab = 3
        // A ordem vale também durante a busca: na aba "Vídeos" do YouTube ordena o resultado da busca (com "Relevância", a ordem do
        // YouTube); nas outras abas a busca filtra a lista já na ordem escolhida.
        if (channelSearch != null) fullSpan("search-sort") {
            val options = listOf<ChannelSort?>(null) + ChannelSort.entries
            ChipRow(options.map { it?.label ?: "Relevância" }, options.indexOf(st.searchSort), { i -> st.searchSort = options[i] })
        } else if (sortOptions.isNotEmpty()) fullSpan("sort") {
            ChipRow(sortOptions.map { it.label }, sortOptions.indexOf(sort), { i ->
                when {
                    isYt && tab == 0 -> st.videoSort = sortOptions[i]
                    isYt && tab == 1 -> st.liveSort = sortOptions[i]
                    else -> st.vodSort = sortOptions[i]
                }
            })
        }
        if (isYt && tab == 2) fullSpan("playlist-sort") {
            ChipRow(PlaylistSort.entries.map { it.label }, st.playlistSort.ordinal, { i -> st.playlistSort = PlaylistSort.entries[i] })
        }
        if (clipsTab) fullSpan("clip-sort") {
            val sort by st.clipSort.collectAsState()
            if (searching) {
                // Pesquisando, a lista inteira já está carregada: a ordem é feita aqui, e dá para ver os mais antigos também.
                val options = listOf(ChannelSort.Popular, ChannelSort.Recent, ChannelSort.Oldest)
                val current = st.searchSort ?: if (sort == ClipSort.Popular) ChannelSort.Popular else ChannelSort.Recent
                ChipRow(options.map { it.label }, options.indexOf(current), { i -> st.searchSort = options[i] })
            } else {
                ChipRow(ClipSort.entries.map { it.label }, sort.ordinal, { st.setClipSort(ClipSort.entries[it]) })
            }
        }
        when {
            tab == aboutTab -> fullSpan("about") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Sobre", style = MaterialTheme.typography.titleLarge)
                    LinkedText(
                        details?.description?.takeIf { it.isNotBlank() } ?: "Este canal não tem descrição.",
                        style = MaterialTheme.typography.bodyMedium, color = ApexColors.OnSurface,
                    )
                    channel.followers?.let {
                        Text("${formatCount(it)} ${if (isYt) "inscritos" else "seguidores"}", style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
                    }
                    ActionButton("Abrir no site", { app.system.openUrl(channel.url.ifBlank { seed.url }) }, icon = Icons.Rounded.OpenInBrowser)
                }
            }
            isYt && tab == 2 -> when {
                playlists.isNotEmpty() -> playlistItems(playlists)
                playlistsLoading -> skeletons(6)
                searching && allPlaylists.isNotEmpty() -> fullSpan("pl-nomatch") { NoMatch(st.query) }
                playlistsError != null -> fullSpan("pl-err") { ErrorBox(playlistsError.orEmpty(), { playlistList.refresh() }) }
                else -> fullSpan("pl-none") { EmptyState(Icons.Rounded.VideoLibrary, "Sem playlists", "${channel.name} não tem playlists públicas.") }
            }
            !isYt && tab == 0 -> if (liveNow == null) fullSpan("offline") {
                EmptyState(
                    Icons.Rounded.Sensors, "Fora do ar",
                    "${channel.name} não está transmitindo agora. Veja a aba VODs para assistir transmissões passadas, ou siga o canal para vê-lo aqui quando entrar ao vivo.",
                )
            }
            items.isNotEmpty() -> videoItems(items.withoutBlocked(settings.blockedChannels).map { m ->
                m.copy(channel = (m.channel ?: channel).copy(avatarUrl = m.channel?.avatarUrl ?: channel.avatarUrl))
            })
            loading -> skeletons(8)
            error != null -> fullSpan("err") { ErrorBox(error.orEmpty(), { activeList?.refresh() }) }
            searching && (channelSearch != null || loadedItems.isNotEmpty()) -> fullSpan("nomatch") { NoMatch(st.query) }
            else -> fullSpan("none") {
                when {
                    !isYt && tab == 1 -> EmptyState(Icons.Rounded.VideoLibrary, "Sem VODs", "${channel.name} não tem transmissões passadas salvas (ou guarda os VODs só para inscritos).")
                    !isYt && tab == 2 -> EmptyState(Icons.Rounded.VideoLibrary, "Sem clipes", "Ninguém criou clipes de ${channel.name} ainda.")
                    else -> EmptyState(Icons.Rounded.VideoLibrary, "Sem vídeos", "Nada para mostrar nesta aba.")
                }
            }
        }
        if (loading && items.isNotEmpty() && tab != aboutTab && !(isYt && tab == 2)) skeletons(4)
        if (isYt && tab == 2 && playlistsLoading && playlists.isNotEmpty()) skeletons(3)
    }
}

/** Nada na lista combina com o que a pessoa digitou no campo de pesquisar do canal. */
@Composable
private fun NoMatch(query: String) {
    EmptyState(Icons.Rounded.Search, "Nada encontrado", "Nenhum resultado para “${query.trim()}” aqui. Confira o texto ou procure em outra aba.")
}
