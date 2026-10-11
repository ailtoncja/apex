package app.apex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.apex.theme.ApexColors
import kotlinx.coroutines.launch

/** Quanto da largura visível a seta anda de uma vez: quase a tela inteira, deixando o último cartão à vista para dar a noção de continuidade. */
private const val PAGE_FRACTION = 0.85f

/**
 * Uma fileira horizontal que dá para percorrer: o `LazyRow` do Compose no computador não anda com a roda do mouse e não tem barra,
 * então os cartões além da borda ficavam inalcançáveis. Aqui aparecem setas nas pontas (só quando há para onde ir); a roda do mouse com Shift
 * e os gestos horizontais do touchpad continuam funcionando.
 *
 * [arrowCenterY] é a altura (a partir do topo) em que as setas ficam centralizadas — em geral o meio da miniatura; sem ele, o meio da fileira.
 * Com [fade] as setas são as da barra de filtros do YouTube: um botão pequeno e liso sobre uma faixa que apaga os filtros que estão sumindo
 * na ponta (veja [ChipBar]), em vez do botão redondo com sombra sobre os cartões.
 */
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    arrowCenterY: Dp? = null,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(14.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    fade: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val canBack by remember(state) { derivedStateOf { state.canScrollBackward } }
    val canForward by remember(state) { derivedStateOf { state.canScrollForward } }
    fun page(direction: Int) {
        val step = state.layoutInfo.viewportSize.width * PAGE_FRACTION
        scope.launch { state.animateScrollBy(direction * step) }
    }
    Box(modifier.fillMaxWidth()) {
        LazyRow(
            Modifier.fillMaxWidth(), state = state, contentPadding = contentPadding, horizontalArrangement = horizontalArrangement,
            verticalAlignment = if (fade) Alignment.CenterVertically else Alignment.Top, content = content,
        )
        if (fade) {
            if (canBack) FadeArrow(Icons.Rounded.ChevronLeft, "Rolar para a esquerda", atStart = true) { page(-1) }
            if (canForward) FadeArrow(Icons.Rounded.ChevronRight, "Rolar para a direita", atStart = false) { page(1) }
        } else {
            if (canBack) RowArrow(Icons.Rounded.ChevronLeft, "Rolar para a esquerda", arrowCenterY, Alignment.TopStart, Alignment.CenterStart) { page(-1) }
            if (canForward) RowArrow(Icons.Rounded.ChevronRight, "Rolar para a direita", arrowCenterY, Alignment.TopEnd, Alignment.CenterEnd) { page(1) }
        }
    }
}

/**
 * A barra de filtros como a do YouTube: os filtros numa linha só, que anda para os lados pelas setinhas das pontas (cada seta só aparece quando
 * há mais filtros para aquele lado, sobre uma faixa que apaga os filtros que passam por baixo dela).
 */
@Composable
fun ChipBar(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    ScrollRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), fade = true, content = content)
}

/** A seta da barra de filtros: o botão pequeno na ponta, com a faixa de "esmaecer" (da cor do fundo da página para transparente) atrás. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FadeArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, atStart: Boolean, onClick: () -> Unit,
) {
    val solid = ApexColors.Background
    val colors = if (atStart) listOf(solid, solid, solid.copy(alpha = 0f)) else listOf(solid.copy(alpha = 0f), solid, solid)
    // matchParentSize: a faixa acompanha a altura da fileira sem aumentá-la (fillMaxHeight esticaria a fileira até o fim da tela).
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier.align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(64.dp)
                .background(Brush.horizontalGradient(colors)),
            contentAlignment = if (atStart) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            IconBtn(icon, description, onClick, Modifier, size = 34.dp, iconSize = 24.dp, hoverColor = ApexColors.SurfaceHighest)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.RowArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, centerY: Dp?,
    topAlignment: Alignment, centerAlignment: Alignment, onClick: () -> Unit,
) {
    val size = 44.dp
    val position = if (centerY != null) Modifier.align(topAlignment).offset(y = (centerY - size / 2).coerceAtLeast(0.dp)) else Modifier.align(centerAlignment)
    IconBtn(
        icon, description, onClick,
        position.shadow(6.dp, CircleShape).border(1.dp, ApexColors.Outline, CircleShape),
        size = size, iconSize = 28.dp,
        background = ApexColors.SurfaceHighest, hoverColor = ApexColors.SurfaceHigh,
    )
}
