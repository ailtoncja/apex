package app.apex.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.source.PlaylistInfo
import androidx.compose.material.icons.rounded.Search
import app.apex.ui.components.FilterTextField
import app.apex.state.matchesWords
import app.apex.state.searchWords
import app.apex.state.Page
import app.apex.state.Paged
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.Avatar
import app.apex.ui.components.EmptyState
import app.apex.ui.components.ErrorBox
import app.apex.ui.components.IconBtn
import app.apex.ui.components.OnNearEnd
import app.apex.ui.components.RemoteImage
import app.apex.ui.components.VideoRow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A página de uma playlist no estilo do YouTube: à esquerda um cartão com a capa, o título, o dono, os números e os botões;
 * à direita a lista numerada dos vídeos. Em janela estreita o cartão vai para cima da lista.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistPageLayout(
    kind: String,
    title: String,
    cover: String?,
    owner: Channel?,
    stats: List<String>,
    description: String?,
    items: List<Media>,
    loading: Boolean,
    error: String?,
    emptyMessage: String,
    onRetry: () -> Unit,
    onNearEnd: () -> Unit,
    actions: @Composable () -> Unit,
    trailing: ((Media) -> (@Composable () -> Unit))? = null,
) {
    val app = LocalApp.current
    val listState = rememberLazyListState()
    OnNearEnd(listState) { onNearEnd() }
    val unique = items.distinctBy { it.key }
    // Pesquisar dentro da playlist: pelo título e pelo canal; enquanto há texto, vai buscando as páginas que faltam para achar tudo.
    var query by remember { mutableStateOf("") }
    val words = remember(query) { searchWords(query) }
    val shown = if (words.isEmpty()) unique else unique.filter { matchesWords(words, it.title, it.channel?.name) }
    LaunchedEffect(words.isNotEmpty(), unique.size, loading) { if (words.isNotEmpty() && !loading) onNearEnd() }

    val header: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(ApexColors.Accent.copy(alpha = 0.28f).compositeOver(ApexColors.SurfaceHigh), ApexColors.SurfaceHigh)))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                if (!cover.isNullOrBlank()) RemoteImage(cover, Modifier.fillMaxSize())
                else Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(ApexColors.Accent.copy(alpha = 0.45f).compositeOver(ApexColors.SurfaceHigh), ApexColors.SurfaceHighest))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlaylistPlay, null, tint = ApexColors.OnSurface.copy(alpha = 0.55f), modifier = Modifier.size(72.dp)) }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            owner?.let { o ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable(enabled = o.id.isNotBlank()) { app.openChannel(o) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(o.avatarUrl, o.name, 26.dp)
                    Text(o.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val line = (listOf(kind) + stats).joinToString(" • ")
            Text(line, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unique.isNotEmpty()) {
                    ActionButton("Reproduzir tudo", { app.openMedia(unique.first(), unique.drop(1)) }, icon = Icons.Rounded.PlayArrow, primary = true)
                    ActionButton("Aleatório", { unique.shuffled().let { app.openMedia(it.first(), it.drop(1)) } }, icon = Icons.Rounded.Shuffle)
                }
                actions()
            }
            var expanded by remember { mutableStateOf(false) }
            description?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = ApexColors.OnSurface.copy(alpha = 0.85f),
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        val body: LazyListScopeBody = { scope ->
            if (unique.isEmpty() && !loading) scope.item("empty") {
                if (error != null) ErrorBox(error, onRetry) else EmptyState(Icons.Rounded.PlaylistPlay, "Playlist vazia", emptyMessage)
            }
            if (unique.size > 6 || query.isNotEmpty()) scope.item("filter") {
                FilterTextField(query, { query = it }, "Pesquisar nesta playlist", Modifier.fillMaxWidth().padding(start = 34.dp))
            }
            if (words.isNotEmpty() && shown.isEmpty() && unique.isNotEmpty() && !loading) scope.item("no-match") {
                EmptyState(Icons.Rounded.Search, "Nada encontrado", "Nenhum vídeo desta playlist combina com “${query.trim()}”.", alignStart = true)
            }
            scope.items(shown, key = { it.key }) { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${unique.indexOf(m) + 1}", Modifier.width(34.dp), style = MaterialTheme.typography.labelLarge,
                        color = ApexColors.Muted, textAlign = TextAlign.Center,
                    )
                    VideoRow(
                        m, Modifier.weight(1f), thumbWidth = 168.dp, compact = true, upNext = unique.filter { it.key != m.key },
                        trailing = trailing?.invoke(m),
                    )
                }
            }
            if (loading) scope.item("loading") { Text("Carregando…", color = ApexColors.Muted, modifier = Modifier.padding(start = 34.dp)) }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                header(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) { body(this) }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item("header") { header(Modifier.fillMaxWidth()) }
                body(this)
            }
        }
    }
}

private typealias LazyListScopeBody = (androidx.compose.foundation.lazy.LazyListScope) -> Unit

/** Uma playlist do YouTube (de um canal ou da conta). */
@Composable
fun RemotePlaylistScreen(id: String, title: String, coverUrl: String? = null, countText: String? = null) {
    val app = LocalApp.current
    val info = remember(id) { MutableStateFlow<PlaylistInfo?>(null) }
    val list = remember(id) {
        Paged<Media>(app.scope, { it.key }) { token ->
            val r = app.youtube.playlist(id, token)
            if (token == null) info.value = r.info
            Page(r.page.items, r.page.continuation)
        }
    }
    LaunchedEffect(list) { list.loadIfNeeded() }
    val items by list.items.collectAsState()
    val loading by list.loading.collectAsState()
    val error by list.error.collectAsState()
    val header by info.collectAsState()
    val url = "https://www.youtube.com/playlist?list=$id"

    PlaylistPageLayout(
        kind = "Playlist do YouTube",
        title = header?.title ?: title,
        cover = coverUrl ?: items.firstOrNull()?.thumbnailUrl,
        owner = header?.owner,
        stats = header?.stats?.takeIf { it.isNotEmpty() } ?: listOfNotNull(countText),
        description = header?.description,
        items = items, loading = loading, error = error,
        emptyMessage = "Nenhum vídeo nesta playlist.",
        onRetry = { list.refresh() }, onNearEnd = { list.loadMore() },
        actions = {
            ActionButton("Copiar para minhas playlists", { app.copyRemotePlaylist(id, header?.title ?: title) }, icon = Icons.Rounded.PlaylistAdd)
            ActionButton("Copiar link", { app.system.copyText(url); app.toast("Link copiado") }, icon = Icons.Rounded.ContentCopy)
            ActionButton("Abrir no navegador", { app.system.openUrl(url) }, icon = Icons.Rounded.OpenInBrowser)
        },
    )
}

/** Uma playlist local (criada no app, ou copiada de uma do YouTube). */
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
    PlaylistPageLayout(
        kind = "Playlist local",
        title = pl.name,
        cover = pl.items.firstOrNull()?.thumbnailUrl,
        owner = null,
        stats = listOf("${pl.items.distinctBy { it.key }.size} ${if (pl.items.size == 1) "vídeo" else "vídeos"}"),
        description = null,
        items = pl.items, loading = false, error = null,
        emptyMessage = "Use “Adicionar à playlist…” no menu de qualquer vídeo.",
        onRetry = {}, onNearEnd = {},
        actions = {
            ActionButton("Renomear", { rename = true }, icon = Icons.Rounded.Edit)
            ActionButton("Excluir", { delete = true }, icon = Icons.Rounded.Delete)
        },
        trailing = { m -> { IconBtn(Icons.Rounded.Close, "Remover", { app.data.togglePlaylistItem(pl.id, m) }, size = 32.dp, iconSize = 18.dp) } },
    )
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
