package app.apex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Media
import app.apex.theme.ApexColors
import app.apex.ui.components.IconBtn
import app.apex.ui.components.ScrollRow
import app.apex.ui.components.RemoteImage

/**
 * A faixa de abas de vídeo, acima do conteúdo (ver [app.apex.tabs.VideoTabs]). Só aparece com mais de uma aba aberta, para não roubar
 * espaço no uso comum. Clique abre a aba (o vídeo volta de onde parou; live volta ao "ao vivo"), o ✕ ou o botão do meio fecha. No fim, o
 * botão "tocar em sequência": com ele ligado, quando um vídeo acaba toca a aba seguinte.
 */
@Composable
fun TabStrip() {
    val app = LocalApp.current
    val tabs by app.tabs.list.collectAsState()
    val current by app.session.current.collectAsState()
    // Com uma aba só, ela só vale a pena ser mostrada se não é o vídeo que já está tocando (abriu algo "em nova aba" sem nada tocar).
    if (tabs.isEmpty() || (tabs.size == 1 && tabs[0].key == current?.key)) return
    val history by app.data.history.collectAsState()
    Row(
        // Na mesma cor da barra superior (e da barra de título do Windows): as abas fazem parte do topo, não são mais uma faixa.
        Modifier.fillMaxWidth().background(ApexColors.Background).padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 6.dp).testTag("tab-strip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScrollRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), fade = true) {
            items(tabs, key = { it.key }) { media ->
                val progress = remember(media.key, history) {
                    if (media.isLive) null
                    else history.firstOrNull { it.media.key == media.key }?.let { e -> if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null }
                }
                VideoTab(media, active = media.key == current?.key, progress, onOpen = { app.openMedia(media) }, onClose = { app.closeTab(media.key) })
            }
        }
        val sequence = app.ui.tabsSequence
        IconBtn(
            Icons.Rounded.PlaylistPlay, if (sequence) "Tocar em sequência: ligado (quando um vídeo acaba, toca a aba seguinte)" else "Tocar em sequência",
            { app.toggleTabsSequence() }, Modifier.testTag("tabs-sequence"), size = 30.dp, iconSize = 20.dp,
            tint = if (sequence) ApexColors.Accent else ApexColors.Muted,
        )
    }
}

/** Uma aba numa linha só (capa pequena, título, ✕), como as do navegador: a faixa toda fica com a altura de uma linha de texto. */
@Composable
private fun VideoTab(media: Media, active: Boolean, progress: Float?, onOpen: () -> Unit, onClose: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.testTag("tab-${media.key}")
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ApexColors.SurfaceHigh else if (hovered) ApexColors.Surface else Color.Transparent)
            .border(1.dp, if (active) ApexColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .hoverable(source)
            // O botão do meio fecha a aba, como no navegador.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                            event.changes.forEach { it.consume() }
                            onClose()
                        }
                    }
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(38.dp, 22.dp).clip(RoundedCornerShape(4.dp)).background(ApexColors.SurfaceHighest)) {
            RemoteImage(media.thumbnailUrl, Modifier.size(38.dp, 22.dp))
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color(0x66000000)))
                Box(Modifier.align(Alignment.BottomStart).width((38 * progress.coerceIn(0f, 1f)).dp).height(2.dp).background(ApexColors.Accent))
            }
        }
        // Só a live ganha bolinha (a vermelha do "ao vivo"): a cor da plataforma ali se confundia com ela (o YouTube também é vermelho).
        if (media.isLive) Box(Modifier.size(6.dp).clip(CircleShape).background(ApexColors.Live))
        Text(
            media.title, Modifier.widthIn(max = 180.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (active || hovered) ApexColors.OnSurface else ApexColors.Muted,
        )
        IconBtn(
            Icons.Rounded.Close, "Fechar a aba", onClose, Modifier.testTag("tab-close-${media.key}"), size = 22.dp, iconSize = 13.dp,
            tint = if (hovered || active) ApexColors.Muted else ApexColors.Faint,
        )
    }
}
