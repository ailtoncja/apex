package app.apex.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apex.model.Platform
import app.apex.model.SupportKind
import app.apex.theme.ApexColors
import app.apex.theme.color
import coil3.compose.AsyncImage
import kotlin.math.abs

/**
 * Imagem da internet. A imagem é decodificada no tamanho original (até [MAX_IMAGE_PX]) e reduzida para a tela com suavização de
 * verdade ([smoothPainter]): se o Coil reduzisse antes, ou o Compose reduzisse sozinho, as miniaturas e capas ficariam serrilhadas.
 */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(ApexColors.SurfaceHigh)) {
        if (!url.isNullOrBlank()) {
            val context = coil3.compose.LocalPlatformContext.current
            val request = remember(url, context) { coil3.request.ImageRequest.Builder(context).data(url).size(coil3.size.Size(MAX_IMAGE_PX, MAX_IMAGE_PX)).build() }
            val painter = coil3.compose.rememberAsyncImagePainter(request, contentScale = contentScale, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
            val state by painter.state.collectAsState()
            // Já carregou: desenha com a suavização de verdade (o painter do Coil só serve enquanto carrega ou se o tipo for outro).
            val smooth = (state as? coil3.compose.AsyncImagePainter.State.Success)?.result?.image
                ?.let { loaded -> remember(loaded) { smoothPainter(loaded) } }
            androidx.compose.foundation.Image(painter = smooth ?: painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** Imagens maiores que isso (em qualquer lado) são reduzidas ao decodificar; as miniaturas e capas ficam abaixo. */
private const val MAX_IMAGE_PX = 1600

@Composable
fun Avatar(url: String?, name: String, size: Dp = 36.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val base = Modifier.size(size).clip(CircleShape)
    val ringed = if (ring != null) modifier.border(2.dp, ring, CircleShape).padding(3.dp) else modifier
    Box(ringed) {
        if (url.isNullOrBlank()) {
            val hue = (abs(name.hashCode()) % 360).toFloat()
            Box(
                base.background(Color.hsv(hue, 0.45f, 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.trim().firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp,
                )
            }
        } else {
            RemoteImage(url, base)
        }
    }
}

@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    return this.alpha(a).background(ApexColors.SurfaceHigh, RoundedCornerShape(8.dp))
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).shimmer())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).shimmer())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(14.dp).shimmer())
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).shimmer())
            }
        }
    }
}

@Composable
fun Modifier.hoverBackground(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    color: Color = ApexColors.SurfaceHigh,
    onClick: (() -> Unit)? = null,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var m = this.clip(shape).hoverable(source).background(if (hovered) color else Color.Transparent)
    if (onClick != null) m = m.clickable(interactionSource = source, indication = null, onClick = onClick)
    return m
}

@Composable
fun ApexChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Color? = null) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        selected -> ApexColors.OnSurface
        hovered -> ApexColors.SurfaceHighest
        else -> ApexColors.SurfaceHigh
    }
    val fg = if (selected) ApexColors.Background else ApexColors.OnSurface
    Row(
        modifier.clip(RoundedCornerShape(50)).hoverable(source).background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = ApexColors.OnSurface,
    background: Color = Color.Transparent,
    hoverColor: Color = ApexColors.SurfaceHighest,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier.size(size).clip(CircleShape).hoverable(source)
            .background(if (hovered) hoverColor else background)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    active: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        primary -> if (hovered) ApexColors.AccentPressed else ApexColors.Accent
        active -> if (hovered) ApexColors.OnSurface.copy(alpha = 0.85f) else ApexColors.OnSurface
        else -> if (hovered) ApexColors.SurfaceHighest else ApexColors.SurfaceHigh
    }
    val fg = if (primary) Color.White else if (active) ApexColors.Background else ApexColors.OnSurface
    Row(
        modifier.clip(RoundedCornerShape(50)).hoverable(source).background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        if (label.isNotEmpty()) Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun PlatformTag(platform: Platform, modifier: Modifier = Modifier) {
    Text(
        platform.label.uppercase(),
        modifier.background(platform.color(), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (platform == Platform.Kick) Color.Black else Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** "SUB" (Twitch) ou "MEMBRO" (YouTube): marca os canais que a pessoa paga. */
@Composable
fun SupportBadge(kind: SupportKind, modifier: Modifier = Modifier) {
    Row(
        modifier.background(ApexColors.Support.copy(alpha = 0.18f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(Icons.Rounded.Star, null, Modifier.size(11.dp), tint = ApexColors.Support)
        Text(kind.label.uppercase(), color = ApexColors.Support, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun LiveTag(modifier: Modifier = Modifier) {
    Row(
        modifier.background(ApexColors.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        Text("AO VIVO", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).background(ApexColors.SurfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ApexColors.Muted, modifier = Modifier.size(34.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = ApexColors.Accent, modifier = Modifier.size(36.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = ApexColors.SurfaceHighest, contentColor = ApexColors.OnSurface),
            ) { Text("Tentar de novo") }
        }
    }
}

@Composable
fun VerifiedMark(modifier: Modifier = Modifier) {
    Icon(Icons.Rounded.CheckCircle, "Verificado", tint = ApexColors.Muted, modifier = modifier.size(13.dp))
}

/** Dispara [onLoadMore] quando a rolagem chega perto do fim. */
@Composable
fun OnNearEnd(state: LazyGridState, buffer: Int = 8, onLoadMore: () -> Unit) {
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - buffer
        }.collect { if (it) onLoadMore() }
    }
}

@Composable
fun OnNearEnd(state: LazyListState, buffer: Int = 6, onLoadMore: () -> Unit) {
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - buffer
        }.collect { if (it) onLoadMore() }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ApexColors.Outline))
}
