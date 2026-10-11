package app.apex.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apex.LocalApp
import app.apex.theme.ApexColors

/** Chip que abre uma lista de opções ("Idioma: Português ▾"). */
@Composable
fun <T> ChipMenu(label: String, options: List<T>, selected: T, name: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ApexChip("$label: ${name(selected)} ▾", false, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(name(o), color = if (o == selected) ApexColors.Accent else ApexColors.OnSurface) },
                    onClick = { open = false; onSelect(o) },
                )
            }
        }
    }
}

/** Campo de texto para filtrar uma lista ao digitar (não dispara os atalhos do player enquanto a pessoa escreve). */
@Composable
fun FilterTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(50)).background(ApexColors.SurfaceHigh)
            .border(1.dp, if (focused) ApexColors.Accent.copy(alpha = 0.8f) else ApexColors.Outline, RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = ApexColors.Muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = ApexColors.Faint, fontSize = 14.sp, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(ApexColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; app.ui.typing = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, "Limpar", tint = ApexColors.Muted,
                modifier = Modifier.size(16.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}


/** O botão "Filtros" do canto direito, como o do YouTube (com quantos filtros estão ligados). */
@Composable
fun FiltersButton(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier.clip(RoundedCornerShape(50)).hoverable(source)
            .background(if (hovered) ApexColors.SurfaceHighest else ApexColors.SurfaceHigh)
            .clickable(interactionSource = source, indication = null, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = ApexColors.OnSurface)
        Text(if (count > 0) "Filtros ($count)" else "Filtros", style = MaterialTheme.typography.labelLarge, color = ApexColors.OnSurface)
    }
}

/**
 * A janela de filtros, como a do YouTube: o [title], os botões "Limpar tudo" (só se há algo ligado) e "Concluído", e embaixo as colunas
 * ([FilterColumn]) que a tela mandar em [content].
 */
@Composable
fun FiltersSheet(title: String, anyActive: Boolean, onClear: () -> Unit, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = ApexColors.SurfaceHigh, modifier = Modifier.widthIn(max = 940.dp).padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (anyActive) {
                        ActionButton("Limpar tudo", onClear)
                        Box(Modifier.width(8.dp))
                    }
                    ActionButton("Concluído", onClose, primary = true)
                }
                content()
            }
        }
    }
}

/** Uma coluna da janela de filtros: o nome do grupo em cima e as opções embaixo. */
@Composable
fun FilterColumn(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

/** Uma opção da coluna, com o visto quando está ligada. */
@Composable
fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(18.dp)) { if (selected) Icon(Icons.Rounded.Check, null, tint = ApexColors.Accent, modifier = Modifier.width(18.dp)) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) ApexColors.OnSurface else ApexColors.OnSurface.copy(alpha = 0.8f), maxLines = 1)
    }
}
