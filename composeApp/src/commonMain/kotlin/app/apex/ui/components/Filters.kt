package app.apex.ui.components

import androidx.compose.foundation.background
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
