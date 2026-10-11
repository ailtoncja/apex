package app.apex.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.theme.ApexColors
import app.apex.theme.Palette
import app.apex.theme.Themes
import app.apex.ui.components.ScrollRow

/** A escolha do tema: um cartão com uma miniatura do app em cada conjunto de cores. */
@Composable
fun ThemeSection() {
    val app = LocalApp.current
    val settings by app.data.settings.collectAsState()
    val current = Themes.byId(settings.theme)
    ScrollRow(arrowCenterY = 70.dp) {
        items(Themes.all, key = { it.id }) { palette ->
            ThemeCard(palette, selected = palette == current) { app.data.updateSettings { it.copy(theme = palette.id) } }
        }
    }
}

@Composable
private fun ThemeCard(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(176.dp).testTag("theme-${palette.id}").clip(shape)
            .border(2.dp, if (selected) ApexColors.Accent else ApexColors.Outline, shape)
            .clickable(onClick = onClick),
    ) {
        // A miniatura usa as cores do tema do cartão (não as do tema atual): barra lateral, busca, dois vídeos e o botão de destaque.
        Box(Modifier.fillMaxWidth().height(104.dp).background(palette.background)) {
            Row(Modifier.fillMaxWidth().fillMaxHeight().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(
                    Modifier.width(26.dp).fillMaxHeight().background(palette.surface, RoundedCornerShape(6.dp)).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(3) { i -> Box(Modifier.width(16.dp).height(4.dp).background(if (i == 0) palette.accent else palette.faint, CircleShape)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(10.dp).background(palette.surfaceHigh, CircleShape))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) {
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().height(38.dp).background(palette.surfaceHighest, RoundedCornerShape(5.dp)))
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.width(32.dp).height(4.dp).background(palette.onSurface.copy(alpha = 0.75f), CircleShape))
                            }
                        }
                    }
                    Box(Modifier.width(36.dp).height(10.dp).background(palette.accent, CircleShape))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(ApexColors.SurfaceHigh).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(palette.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2)
            if (selected) Icon(Icons.Rounded.CheckCircle, "Tema escolhido", tint = ApexColors.Accent, modifier = Modifier.size(18.dp))
        }
    }
}
