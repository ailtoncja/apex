package app.apex.ui.watch

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.apex.model.Chapter
import app.apex.theme.ApexColors
import app.apex.util.formatClock

@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    var widthPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var hoverX by remember { mutableStateOf<Float?>(null) }

    val fraction = when {
        dragging -> dragFraction
        durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val active = dragging || hoverX != null
    val trackHeight by animateDpAsState(if (active) 6.dp else 4.dp)

    Box(
        modifier.fillMaxWidth().height(28.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Exit -> if (!dragging) hoverX = null
                            PointerEventType.Move, PointerEventType.Enter -> hoverX = change.position.x
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        if (duration <= 0) continue
                        dragging = true
                        dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        seek((dragFraction * duration).toLong())
                        dragging = false
                    }
                }
            },
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().height(trackHeight)
                .clip(RoundedCornerShape(50)).background(Color(0x55FFFFFF)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(ApexColors.Accent))
        }
        if (durationMs > 0) {
            chapters.drop(1).forEach { ch ->
                val f = (ch.startSec * 1000f / durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = with(density) { (f * widthPx).toDp() })
                        .width(2.dp).height(trackHeight).background(Color.Black),
                )
            }
        }
        if (active && durationMs > 0) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = with(density) { (fraction * widthPx).toDp() - 7.dp })
                    .size(14.dp).background(ApexColors.Accent, CircleShape),
            )
        }
        hoverX?.takeIf { durationMs > 0 }?.let { x ->
            val f = (x / widthPx).coerceIn(0f, 1f)
            val ms = (f * durationMs).toLong()
            val chapter = chapters.lastOrNull { it.startSec * 1000 <= ms }?.title
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset(x = with(density) { x.toDp() - 32.dp }.coerceAtLeast(0.dp), y = (-30).dp)
                    .background(Color(0xE6000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    if (chapter != null) "${formatClock(ms)} • $chapter" else formatClock(ms),
                    color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                )
            }
        }
    }
}

/** Controle deslizante fino (volume). */
@Composable
fun MiniSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    Box(
        modifier.height(24.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        change((down.position.x / size.width).coerceIn(0f, 1f))
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val c = event.changes.first()
                            change((c.position.x / size.width).coerceIn(0f, 1f))
                            c.consume()
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
        }
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = with(density) { (value.coerceIn(0f, 1f) * widthPx).toDp() - 6.dp })
                .size(12.dp).background(Color.White, CircleShape),
        )
    }
}
