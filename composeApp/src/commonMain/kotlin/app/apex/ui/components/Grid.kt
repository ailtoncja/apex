package app.apex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.model.Media

val GridMinWidth = 290.dp

/** A largura máxima do conteúdo de uma página de lista (biblioteca, pesquisa): em janelas bem largas ele para aqui, encostado à esquerda. */
val PAGE_MAX_WIDTH = 1500.dp

@Composable
fun ApexGrid(state: LazyGridState, modifier: Modifier = Modifier, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(GridMinWidth),
        modifier = modifier,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        content = content,
    )
}

fun LazyGridScope.fullSpan(key: Any? = null, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * Os cartões de uma lista. A chave de cada item tem de ser única em TODA a grade: o mesmo vídeo pode aparecer em duas seções
 * (por exemplo no feed das inscrições e nos recomendados), por isso cada seção usa o seu [section]; e, dentro da lista,
 * repetidos são ignorados. Com chave repetida o Compose derruba o app ("Key was already used").
 */
fun LazyGridScope.videoItems(list: List<Media>, upNext: List<Media>? = null, section: String = "") {
    val unique = list.distinctBy { it.key }
    gridItems(unique, key = { section + it.key }) { VideoCard(it, upNext = upNext ?: unique.filter { m -> m.key != it.key }) }
}

fun LazyGridScope.playlistItems(list: List<app.apex.source.RemotePlaylist>) {
    gridItems(list, key = { "pl-" + it.id }) { RemotePlaylistCard(it) }
}

fun LazyGridScope.skeletons(count: Int = 8) {
    items(count, key = { "sk$it" }) { SkeletonCard() }
}

/** Fileira horizontal de cartões (ao vivo, relacionados). */
@Composable
fun MediaRow(list: List<Media>, modifier: Modifier = Modifier, cardWidth: androidx.compose.ui.unit.Dp = 290.dp) {
    val unique = list.distinctBy { it.key }
    // As setas ficam no meio da miniatura (16:9).
    ScrollRow(modifier, arrowCenterY = cardWidth * 9 / 32, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items(unique, key = { it.key }) { VideoCard(it, Modifier.width(cardWidth), upNext = unique.filter { m -> m.key != it.key }) }
    }
}

/**
 * A fileira de opções (assuntos, abas, ordens). Com [arrows], é a barra de uma linha como a do YouTube, com as setinhas nas pontas quando não
 * cabe ([ChipBar]); sem elas, a fileira simples de sempre (a do Início), que anda com Shift + roda do mouse e com o touchpad.
 */
@Composable
fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, arrows: Boolean = true) {
    if (arrows) {
        ChipBar(modifier.padding(vertical = 10.dp)) {
            items(labels.size) { i -> ApexChip(labels[i], selected == i, { onSelect(i) }) }
        }
    } else {
        LazyRow(modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(labels.size) { i -> ApexChip(labels[i], selected == i, { onSelect(i) }) }
        }
    }
}
