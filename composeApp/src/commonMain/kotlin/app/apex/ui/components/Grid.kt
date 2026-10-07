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

fun LazyGridScope.videoItems(list: List<Media>, upNext: List<Media>? = null) {
    gridItems(list, key = { it.key }) { VideoCard(it, upNext = upNext ?: list.filter { m -> m.key != it.key }) }
}

fun LazyGridScope.skeletons(count: Int = 8) {
    items(count, key = { "sk$it" }) { SkeletonCard() }
}

/** Fileira horizontal de cartões (ao vivo, relacionados). */
@Composable
fun MediaRow(list: List<Media>, modifier: Modifier = Modifier, cardWidth: androidx.compose.ui.unit.Dp = 290.dp) {
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items(list, key = { it.key }) { VideoCard(it, Modifier.width(cardWidth), upNext = list.filter { m -> m.key != it.key }) }
    }
}

@Composable
fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(labels.size) { i -> ApexChip(labels[i], selected == i, { onSelect(i) }) }
    }
}
