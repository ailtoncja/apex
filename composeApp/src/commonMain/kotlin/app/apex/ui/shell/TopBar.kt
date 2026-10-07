package app.apex.ui.shell

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.apex.LocalApp
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.theme.ApexColors
import app.apex.ui.components.Avatar
import app.apex.ui.components.IconBtn
import kotlinx.coroutines.delay

@Composable
fun TopBar(onToggleSidebar: () -> Unit) {
    val app = LocalApp.current
    Row(
        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBtn(Icons.Rounded.Menu, "Menu", onToggleSidebar)
        Logo(Modifier.clickable { app.nav.goRoot(Route.Home) }.padding(horizontal = 6.dp))
        if (app.nav.canGoBack) IconBtn(Icons.Rounded.ArrowBack, "Voltar", { app.nav.back() })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SearchField(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp)) }
        IconBtn(Icons.Rounded.Refresh, "Atualizar", { refreshCurrent(app) })
        AccountButton()
    }
}

private fun refreshCurrent(app: app.apex.AppContainer) {
    when (app.nav.current) {
        is Route.Home -> app.screens.home.refresh()
        is Route.Live -> app.screens.live.refresh()
        is Route.Subscriptions -> { app.screens.subs.refreshFeed(); app.screens.subs.refreshLive() }
        else -> {}
    }
    app.toast("Atualizando…")
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(ApexColors.Accent, CircleShape))
        Text("APEX", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, color = ApexColors.OnSurface)
    }
}

@Composable
private fun SearchField(modifier: Modifier) {
    val app = LocalApp.current
    val settings by app.data.settings.collectAsState()
    val route = app.nav.current
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var popupHover by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(route) { text = if (route is Route.Search) route.query else "" }
    LaunchedEffect(text, focused) {
        if (!focused) return@LaunchedEffect
        delay(160)
        suggestions = if (text.isBlank()) emptyList() else runCatching { app.youtube.suggestions(text) }.getOrDefault(emptyList())
    }

    fun submit(q: String = text) {
        if (q.isBlank()) return
        text = q
        focused = false
        popupHover = false
        app.ui.typing = false
        app.search(q)
    }

    var fieldWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(modifier.onSizeChanged { fieldWidth = it.width }) {
        Row(
            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(50))
                .background(ApexColors.SurfaceHigh)
                .border(1.dp, if (focused) ApexColors.Accent.copy(alpha = 0.8f) else ApexColors.Outline, RoundedCornerShape(50))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = ApexColors.Muted, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text("Buscar vídeos, lives e canais", color = ApexColors.Faint, fontSize = 15.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(ApexColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused; app.ui.typing = it.isFocused }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { submit(); true } else false
                        },
                )
            }
            if (text.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Close, "Limpar", tint = ApexColors.Muted,
                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { text = "" },
                )
            }
        }

        val shown = if (text.isBlank()) settings.searchHistory.take(8) else suggestions.take(9)
        if ((focused || popupHover) && shown.isNotEmpty()) {
            Popup(
                popupPositionProvider = BelowAnchor,
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Column(
                    Modifier.width(with(density) { fieldWidth.toDp() })
                        .clip(RoundedCornerShape(16.dp)).background(ApexColors.SurfaceHigh)
                        .border(1.dp, ApexColors.Outline, RoundedCornerShape(16.dp))
                        .hoverableFlag { popupHover = it }
                        .padding(vertical = 8.dp),
                ) {
                    shown.forEach { s ->
                        val source = remember { MutableInteractionSource() }
                        val hovered by source.collectIsHoveredAsState()
                        Row(
                            Modifier.fillMaxWidth().hoverable(source).background(if (hovered) ApexColors.SurfaceHighest else Color.Transparent)
                                .clickable(interactionSource = source, indication = null) { submit(s) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(if (text.isBlank()) Icons.Rounded.History else Icons.Rounded.Search, null, tint = ApexColors.Muted, modifier = Modifier.size(18.dp))
                            Text(s, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            if (text.isBlank()) {
                                Icon(
                                    Icons.Rounded.Close, "Remover", tint = ApexColors.Muted,
                                    modifier = Modifier.size(16.dp).clickable { app.data.removeSearchHistory(s) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(anchorBounds.left, anchorBounds.bottom + 8)
}

@Composable
private fun Modifier.hoverableFlag(onChange: (Boolean) -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onChange(hovered) }
    return this.hoverable(source)
}

@Composable
private fun AccountButton() {
    val app = LocalApp.current
    val accounts by app.data.accounts.collectAsState()
    val youtube = accounts[Platform.YouTube.name]
    val any = youtube ?: accounts.values.firstOrNull()
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
            if (any != null) Avatar(any.avatarUrl, any.displayName, 32.dp)
            else Icon(Icons.Rounded.AccountCircle, "Conta", tint = ApexColors.Muted, modifier = Modifier.size(30.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (accounts.isEmpty()) {
                DropdownMenuItem(text = { Text("Entrar nas contas") }, onClick = { menu = false; app.nav.goRoot(Route.Settings) })
            } else {
                accounts.values.forEach {
                    DropdownMenuItem(
                        text = { Text("${it.platform.label}: ${it.displayName}") },
                        leadingIcon = { Avatar(it.avatarUrl, it.displayName, 24.dp) },
                        onClick = { menu = false; app.nav.goRoot(Route.Settings) },
                    )
                }
            }
            DropdownMenuItem(text = { Text("Ajustes") }, onClick = { menu = false; app.nav.goRoot(Route.Settings) })
        }
    }
}
