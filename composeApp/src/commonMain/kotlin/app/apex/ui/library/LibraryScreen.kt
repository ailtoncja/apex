package app.apex.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.LocalPlaylist
import app.apex.model.Platform
import app.apex.source.RemotePlaylist
import app.apex.state.Loadable
import app.apex.state.Page
import app.apex.state.Paged
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.OnNearEnd
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.width
import app.apex.model.Media
import app.apex.nav.LibraryTab
import app.apex.nav.Route
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.EmptyState
import app.apex.ui.components.IconBtn
import app.apex.ui.components.RemoteImage
import app.apex.ui.components.SectionTitle
import app.apex.ui.components.RemotePlaylistRow
import app.apex.ui.components.VideoRow

@Composable
fun LibraryScreen(tab: LibraryTab) {
    val app = LocalApp.current
    val history by app.data.history.collectAsState()
    val later by app.data.watchLater.collectAsState()
    val liked by app.data.liked.collectAsState()
    val playlists by app.data.playlists.collectAsState()
    var newPlaylist by remember { mutableStateOf(false) }
    val accounts by app.data.accounts.collectAsState()
    val hasYouTube = accounts.containsKey(Platform.YouTube.name)
    var source by remember(tab) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val remoteList = remember(tab, hasYouTube) {
        val browseId = when (tab) {
            LibraryTab.History -> "FEhistory"
            LibraryTab.WatchLater -> "VLWL"
            LibraryTab.Liked -> "VLLL"
            else -> null
        }
        if (hasYouTube && browseId != null) {
            Paged<Media>(app.scope, { it.key }) { token -> app.youtube.accountList(browseId, token).let { Page(it.items, it.continuation) } }
        } else null
    }
    val emptyList = remember { Paged<Media>(app.scope) { Page(emptyList(), null) } }
    // Clipes que a conta do YouTube criou (a Twitch e a Kick têm os clipes na página de cada canal).
    val myClips = remember(hasYouTube) {
        if (hasYouTube) Paged<Media>(app.scope, { it.key }) { Page(app.youtube.myClips(), null) } else null
    }
    val clipItems by (myClips ?: emptyList).items.collectAsState()
    val clipsLoading by (myClips ?: emptyList).loading.collectAsState()
    val clipsError by (myClips ?: emptyList).error.collectAsState()
    LaunchedEffect(tab, myClips) { if (tab == LibraryTab.Clips) myClips?.loadIfNeeded() }
    val remoteItems by (remoteList ?: emptyList).items.collectAsState()
    val remoteLoading by (remoteList ?: emptyList).loading.collectAsState()
    val remoteError by (remoteList ?: emptyList).error.collectAsState()
    val remotePlaylists = remember(hasYouTube) { Loadable(app.scope, emptyList<RemotePlaylist>()) { app.youtube.accountPlaylists() } }
    val ytPlaylists by remotePlaylists.value.collectAsState()
    LaunchedEffect(remoteList, source) { if (source == 1) remoteList?.loadIfNeeded() }
    LaunchedEffect(tab, hasYouTube) { if (tab == LibraryTab.Playlists && hasYouTube) remotePlaylists.loadIfNeeded() }
    OnNearEnd(listState) { if (source == 1) remoteList?.loadMore() }

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("tabs") {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LibraryTab.entries.forEach { t ->
                        ApexChip(t.label, t == tab, { app.nav.replaceTop(Route.Library(t)) })
                    }
                }
            }
            if (remoteList != null) {
                item("source") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ApexChip("Neste app", source == 0, { source = 0 })
                        ApexChip("Conta do YouTube", source == 1, { source = 1 })
                    }
                }
            }
            if (source == 1 && remoteList != null) {
                item("r-title") { SectionTitle("${tab.label} no YouTube") }
                if (remoteItems.isEmpty() && !remoteLoading) item("r-empty") {
                    if (remoteError != null) ErrorBox(remoteError.orEmpty(), { remoteList.refresh() })
                    else EmptyState(Icons.Rounded.History, "Nada por aqui", "Esta lista da sua conta está vazia.")
                }
                items(remoteItems.distinctBy { it.key }, key = { "r-" + it.key }) { VideoRow(it, upNext = remoteItems.filter { m -> m.key != it.key }) }
                if (remoteLoading) item("r-loading") { Text("Carregando…", color = ApexColors.Muted) }
            } else when (tab) {
                LibraryTab.History -> {
                    item("h-title") {
                        SectionTitle(
                            "Histórico", subtitle = "${history.size} vídeos",
                            trailing = {
                                if (history.isNotEmpty()) ActionButton("Limpar histórico", { app.data.clearHistory(); app.toast("Histórico limpo") }, icon = Icons.Rounded.Delete)
                            },
                        )
                    }
                    if (history.isEmpty()) item("h-empty") {
                        EmptyState(Icons.Rounded.History, "Nada por aqui", "Os vídeos e lives que você assistir aparecem neste histórico.")
                    }
                    items(history.distinctBy { it.media.key }, key = { it.media.key }) { e ->
                        VideoRow(
                            e.media, upNext = history.map { it.media }.filter { it.key != e.media.key },
                            trailing = { IconBtn(Icons.Rounded.Close, "Remover", { app.data.removeHistory(e.media.key) }, size = 32.dp, iconSize = 18.dp) },
                        )
                    }
                }
                LibraryTab.WatchLater -> {
                    item("w-title") {
                        SectionTitle(
                            "Assistir depois", subtitle = "${later.size} vídeos",
                            trailing = {
                                if (later.isNotEmpty()) ActionButton("Reproduzir tudo", { app.openMedia(later.first(), later.drop(1)) }, icon = Icons.Rounded.PlayArrow, primary = true)
                            },
                        )
                    }
                    if (later.isEmpty()) item("w-empty") {
                        EmptyState(Icons.Rounded.WatchLater, "Lista vazia", "Salve vídeos com o botão do relógio e eles ficam aqui.")
                    }
                    items(later.distinctBy { it.key }, key = { it.key }) { m ->
                        VideoRow(
                            m, upNext = later.filter { it.key != m.key },
                            trailing = { IconBtn(Icons.Rounded.Close, "Remover", { app.data.toggleWatchLater(m) }, size = 32.dp, iconSize = 18.dp) },
                        )
                    }
                }
                LibraryTab.Liked -> {
                    item("l-title") {
                        SectionTitle(
                            "Vídeos curtidos", subtitle = "${liked.size} vídeos",
                            trailing = {
                                if (liked.isNotEmpty()) ActionButton("Reproduzir tudo", { app.openMedia(liked.first(), liked.drop(1)) }, icon = Icons.Rounded.PlayArrow, primary = true)
                            },
                        )
                    }
                    if (liked.isEmpty()) item("l-empty") {
                        EmptyState(Icons.Rounded.ThumbUp, "Nenhuma curtida", "Os vídeos que você curtir aparecem aqui.")
                    }
                    items(liked.distinctBy { it.key }, key = { it.key }) { m ->
                        VideoRow(
                            m, upNext = liked.filter { it.key != m.key },
                            trailing = { IconBtn(Icons.Rounded.Close, "Descurtir", { app.data.setReaction(m, 0) }, size = 32.dp, iconSize = 18.dp) },
                        )
                    }
                }
                LibraryTab.Clips -> {
                    item("c-title") {
                        SectionTitle(
                            "Clipes", subtitle = if (hasYouTube) "${clipItems.size} clipes que você criou no YouTube" else null,
                            trailing = {
                                if (clipItems.isNotEmpty()) ActionButton("Reproduzir tudo", { app.openMedia(clipItems.first(), clipItems.drop(1)) }, icon = Icons.Rounded.PlayArrow, primary = true)
                            },
                        )
                    }
                    when {
                        !hasYouTube -> item("c-login") {
                            EmptyState(Icons.Rounded.ContentCut, "Entre no YouTube", "Os clipes que você criou aparecem aqui. Entre na sua conta em Ajustes › Contas.")
                        }
                        clipItems.isNotEmpty() -> items(clipItems, key = { "c-" + it.key }) { VideoRow(it, upNext = clipItems.filter { m -> m.key != it.key }) }
                        clipsLoading -> item("c-loading") { Text("Carregando…", color = ApexColors.Muted) }
                        clipsError != null -> item("c-error") { ErrorBox(clipsError.orEmpty(), { myClips?.refresh() }) }
                        else -> item("c-empty") {
                            EmptyState(Icons.Rounded.ContentCut, "Nenhum clipe", "Crie clipes no YouTube (botão Clipe, abaixo do vídeo) e eles aparecem aqui. Para ver clipes da Twitch e da Kick, abra a aba Clipes do canal.")
                        }
                    }
                }
                LibraryTab.Playlists -> {
                    item("p-title") {
                        SectionTitle(
                            "Playlists", subtitle = "${playlists.size} listas",
                            trailing = { ActionButton("Nova playlist", { newPlaylist = true }, icon = Icons.Rounded.Add, primary = true) },
                        )
                    }
                    if (playlists.isEmpty()) item("p-empty") {
                        EmptyState(Icons.Rounded.PlaylistPlay, "Sem playlists", "Crie uma playlist e adicione vídeos pelo menu de cada vídeo.")
                    } else item("p-grid") {
                        PlaylistGrid(playlists)
                    }
                    if (hasYouTube) {
                        item("yt-title") { SectionTitle("Playlists do YouTube", Modifier.padding(top = 10.dp)) }
                        if (ytPlaylists.isEmpty()) item("yt-empty") {
                            Text("Nenhuma playlist encontrada na sua conta.", color = ApexColors.Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        items(ytPlaylists, key = { "yt-" + it.id }) { pl -> RemotePlaylistRow(pl) }
                    }
                }
            }
        }
    }

    if (newPlaylist) NamePlaylistDialog("Nova playlist", "", { newPlaylist = false }) { name ->
        app.data.createPlaylist(name)
        newPlaylist = false
    }
}

@Composable
private fun PlaylistGrid(playlists: List<LocalPlaylist>) {
    val app = LocalApp.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        playlists.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { pl ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { app.nav.push(Route.PlaylistPage(pl.id)) },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box {
                            RemoteImage(pl.items.firstOrNull()?.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                            Text(
                                "${pl.items.size} vídeos",
                                Modifier.align(Alignment.BottomEnd).padding(8.dp).background(androidx.compose.ui.graphics.Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                        Text(pl.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val app = LocalApp.current
    val playlists by app.data.playlists.collectAsState()
    val pl = playlists.firstOrNull { it.id == id }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }

    if (pl == null) {
        EmptyState(Icons.Rounded.PlaylistPlay, "Playlist não encontrada", "Ela pode ter sido excluída.")
        return
    }
    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("title") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(pl.name, Modifier.padding(top = 8.dp), "${pl.items.size} vídeos • Playlist local")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pl.items.isNotEmpty()) ActionButton("Reproduzir tudo", { app.openMedia(pl.items.first(), pl.items.drop(1)) }, icon = Icons.Rounded.PlayArrow, primary = true)
                        ActionButton("Renomear", { rename = true }, icon = Icons.Rounded.Edit)
                        ActionButton("Excluir", { delete = true }, icon = Icons.Rounded.Delete)
                    }
                }
            }
            if (pl.items.isEmpty()) item("empty") {
                EmptyState(Icons.Rounded.PlaylistPlay, "Playlist vazia", "Use “Adicionar à playlist…” no menu de qualquer vídeo.")
            }
            items(pl.items.distinctBy { it.key }, key = { it.key }) { m ->
                VideoRow(
                    m, upNext = pl.items.filter { it.key != m.key },
                    trailing = { IconBtn(Icons.Rounded.Close, "Remover", { app.data.togglePlaylistItem(pl.id, m) }, size = 32.dp, iconSize = 18.dp) },
                )
            }
        }
    }
    if (rename) NamePlaylistDialog("Renomear playlist", pl.name, { rename = false }) { app.data.renamePlaylist(pl.id, it); rename = false }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("Excluir “${pl.name}”?") },
        text = { Text("Os vídeos continuam disponíveis, só a lista será removida.") },
        confirmButton = { TextButton({ app.data.deletePlaylist(pl.id); delete = false; app.nav.back() }) { Text("Excluir", color = ApexColors.Accent) } },
        dismissButton = { TextButton({ delete = false }) { Text("Cancelar") } },
        containerColor = ApexColors.SurfaceHigh,
    )
}

@Composable
fun NamePlaylistDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onConfirm(name) }) { Text("Salvar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
        containerColor = ApexColors.SurfaceHigh,
    )
}

/** Diálogo "Salvar em…": marca em quais playlists o vídeo está (locais e, se houver conta, do YouTube). */
@Composable
fun AddToPlaylistDialog(media: Media, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playlists by app.data.playlists.collectAsState()
    val accounts by app.data.accounts.collectAsState()
    val ytLogged = accounts.containsKey(Platform.YouTube.name) && media.platform == Platform.YouTube && !media.isClip
    var creating by remember { mutableStateOf(false) }
    var creatingRemote by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<app.apex.source.PlaylistOption>?>(null) }
    LaunchedEffect(media.key, ytLogged) {
        if (ytLogged) remote = runCatching { app.youtube.playlistOptions(media.id) }.getOrDefault(emptyList())
    }
    if (creating) {
        NamePlaylistDialog("Nova playlist", "", { creating = false }) { name ->
            app.data.createPlaylist(name, media)
            app.toast("Salvo em “$name”")
            creating = false
            onDismiss()
        }
        return
    }
    if (creatingRemote) {
        NamePlaylistDialog("Nova playlist no YouTube", "", { creatingRemote = false }) { name ->
            creatingRemote = false
            scope.launch {
                val id = runCatching { app.youtube.createPlaylist(name, media.id) }.getOrNull()
                app.toast(if (id != null) "Salvo em “$name” no YouTube" else "Não foi possível criar a playlist")
                onDismiss()
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvar em…") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (playlists.isEmpty() && !ytLogged) Text("Você ainda não tem playlists.", color = ApexColors.Muted)
                playlists.forEach { pl ->
                    val has = pl.items.any { it.key == media.key }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { app.data.togglePlaylistItem(pl.id, media) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.Checkbox(checked = has, onCheckedChange = { app.data.togglePlaylistItem(pl.id, media) })
                        Text(pl.name, Modifier.weight(1f), maxLines = 1)
                        Text("${pl.items.size}", color = ApexColors.Muted)
                    }
                }
                if (ytLogged) {
                    Text("No YouTube", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge, color = ApexColors.Muted)
                    val options = remote
                    if (options == null) Text("Carregando…", color = ApexColors.Muted)
                    options?.forEach { opt ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            androidx.compose.material3.Checkbox(checked = opt.contains, onCheckedChange = { v ->
                                remote = remote?.map { if (it.id == opt.id) it.copy(contains = v) else it }
                                scope.launch {
                                    val ok = runCatching { app.youtube.editPlaylist(opt.id, media.id, v) }.getOrDefault(false)
                                    if (!ok) app.toast("Não foi possível atualizar a playlist")
                                }
                            })
                            Text(opt.title, Modifier.weight(1f), maxLines = 1)
                        }
                    }
                    TextButton({ creatingRemote = true }) { Text("Nova playlist no YouTube") }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Concluído") } },
        dismissButton = { TextButton({ creating = true }) { Text("Nova playlist") } },
        containerColor = ApexColors.SurfaceHigh,
    )
}

/** Uma playlist da conta do YouTube. */
@Composable
fun RemotePlaylistScreen(id: String, title: String) {
    val app = LocalApp.current
    val list = remember(id) {
        Paged<Media>(app.scope, { it.key }) { token -> app.youtube.playlistVideos(id, token).let { Page(it.items, it.continuation) } }
    }
    LaunchedEffect(list) { list.loadIfNeeded() }
    val items by list.items.collectAsState()
    val loading by list.loading.collectAsState()
    val error by list.error.collectAsState()
    val state = rememberLazyListState()
    OnNearEnd(state) { list.loadMore() }

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth(), state = state,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("title") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(title, Modifier.padding(top = 8.dp), "Playlist do YouTube")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (items.isNotEmpty()) ActionButton("Reproduzir tudo", { app.openMedia(items.first(), items.drop(1)) }, icon = Icons.Rounded.PlayArrow, primary = true)
                        ActionButton("Copiar para minhas playlists", { app.copyRemotePlaylist(id, title) }, icon = Icons.Rounded.PlaylistAdd)
                        ActionButton("Copiar link", { app.system.copyText("https://www.youtube.com/playlist?list=$id"); app.toast("Link copiado") }, icon = Icons.Rounded.ContentCopy)
                        ActionButton("Abrir no navegador", { app.system.openUrl("https://www.youtube.com/playlist?list=$id") }, icon = Icons.Rounded.OpenInBrowser)
                    }
                }
            }
            if (items.isEmpty() && !loading) item("empty") {
                if (error != null) ErrorBox(error.orEmpty(), { list.refresh() })
                else EmptyState(Icons.Rounded.PlaylistPlay, "Playlist vazia", "Nenhum vídeo nesta playlist.")
            }
            items(items.distinctBy { it.key }, key = { it.key }) { m -> VideoRow(m, upNext = items.filter { it.key != m.key }) }
            if (loading) item("loading") { Text("Carregando…", color = ApexColors.Muted) }
        }
    }
}
