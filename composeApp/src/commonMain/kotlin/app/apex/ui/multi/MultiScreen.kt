package app.apex.ui.multi

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import app.apex.model.Channel
import app.apex.model.Platform
import app.apex.multi.MultiStream
import app.apex.multi.Tile
import app.apex.player.LoadState
import app.apex.theme.ApexColors
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ChipBar
import app.apex.ui.components.EmptyState
import app.apex.ui.components.IconBtn
import app.apex.ui.watch.ChatPanel
import app.apex.ui.watch.LiveEdgeButton
import app.apex.util.Timing
import app.apex.util.formatCount

/**
 * O Multi: várias lives ao mesmo tempo, de qualquer plataforma, numa grade; som nas que a pessoa escolher, um chat ao lado. Enquanto a tela
 * está aberta os players rodam; ao sair, param (a lista fica guardada).
 */
@Composable
fun MultiScreen() {
    val app = LocalApp.current
    val multi = app.multi
    DisposableEffect(multi) {
        multi.start()
        onDispose { multi.stop() }
    }
    val tiles by multi.tiles.collectAsState()
    val audio by multi.audio.collectAsState()
    val chatKey by multi.chat.collectAsState()
    val focus by multi.focus.collectAsState()
    val chatVisible = app.ui.multiChat

    Column(Modifier.fillMaxSize()) {
        Header(tiles, audio)
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (tiles.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.GridView, "Nenhuma live no Multi",
                        "Digite o nome de um canal que você segue (ou cole um link) no campo acima, ou use o botão direito em qualquer live: \"Assistir no Multi\". Cabem até ${MultiStream.MAX}, de qualquer plataforma, misturadas.",
                    )
                } else {
                    TileGrid(tiles, focus, audio, chatKey)
                }
            }
            if (chatVisible && tiles.isNotEmpty()) MultiChat(tiles, chatKey, Modifier.width(360.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun Header(tiles: List<Tile>, audio: Set<String>) {
    val app = LocalApp.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text("Multi", style = MaterialTheme.typography.headlineMedium)
            val withSound = tiles.filter { it.key in audio }.map { it.media.channel?.name ?: it.media.id }
            val count = "${tiles.size} ${if (tiles.size == 1) "live" else "lives"}"
            Text(
                when {
                    tiles.isEmpty() -> "Várias lives ao mesmo tempo"
                    withSound.isEmpty() -> "$count • sem som"
                    else -> "$count • som: ${withSound.joinToString(", ")}"
                },
                style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        AddBox()
        IconBtn(Icons.Rounded.Chat, if (app.ui.multiChat) "Esconder o chat" else "Mostrar o chat", { app.ui.multiChat = !app.ui.multiChat }, Modifier.testTag("multi-chat-toggle"), tint = if (app.ui.multiChat) ApexColors.Accent else ApexColors.OnSurface)
        if (tiles.isNotEmpty()) ActionButton("Limpar", { app.multi.clear() }, Modifier.testTag("multi-clear"), icon = Icons.Rounded.Close)
    }
}

/**
 * O campo de adicionar: enquanto a pessoa digita, os canais que ela segue com aquele nome aparecem embaixo (os ao vivo primeiro); Enter pega o
 * primeiro. Sem sugestão, vale um link (de qualquer plataforma) ou o nome de um canal da plataforma escolhida.
 */
@Composable
private fun AddBox() {
    val app = LocalApp.current
    var text by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf(Platform.Twitch) }
    val subs by app.data.subscriptions.collectAsState()
    val liveKeys by app.liveWatcher.liveKeys.collectAsState()
    val tiles by app.multi.tiles.collectAsState()
    val suggestions = remember(text, subs, liveKeys, tiles) {
        matchChannels(text, subs, liveKeys).filter { c -> tiles.none { it.media.channel?.key == c.key || (it.media.platform == c.platform && it.media.id == c.id) } }
    }
    var focused by remember { mutableStateOf(false) }
    val popupSource = remember { MutableInteractionSource() }
    val popupHover by popupSource.collectIsHoveredAsState()
    val fieldSource = remember { MutableInteractionSource() }
    val fieldHover by fieldSource.collectIsHoveredAsState()
    var fieldWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    // Um clique fora do campo e da lista tira o cursor do campo, e a lista some.
    LaunchedEffect(app.ui.pressTick) { if (app.ui.pressTick > 0 && focused && !fieldHover && !popupHover) focusManager.clearFocus() }

    fun pick(channel: Channel) {
        Timing.mark("multi: sugestão ${channel.key}")
        if (app.addChannelToMulti(channel)) text = ""
    }

    fun submit() {
        val t = text.trim()
        Timing.mark("multi: campo enviou \"$t\" (${platform.label})")
        if (t.isEmpty()) return
        suggestions.firstOrNull()?.let { pick(it); return }
        if (app.addToMulti(t, platform)) text = ""
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(300.dp).onSizeChanged { fieldWidth = it.width }.hoverable(fieldSource).clip(RoundedCornerShape(50)).background(ApexColors.Surface).padding(horizontal = 14.dp, vertical = 9.dp)) {
            if (text.isEmpty()) Text("Canal que você segue, nome ou link", color = ApexColors.Faint, fontSize = 14.sp, maxLines = 1)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 14.sp), cursorBrush = SolidColor(ApexColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("multi-add-field")
                    .onFocusChanged { focused = it.isFocused; app.ui.typing = it.isFocused }
                    .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { submit(); true } else false },
            )
            if ((focused || popupHover) && suggestions.isNotEmpty()) {
                Popup(popupPositionProvider = BelowField, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
                    Column(
                        Modifier.width(with(density) { fieldWidth.toDp() }).clip(RoundedCornerShape(14.dp)).background(ApexColors.SurfaceHigh)
                            .border(1.dp, ApexColors.Outline, RoundedCornerShape(14.dp)).hoverable(popupSource).padding(vertical = 6.dp)
                            .testTag("multi-suggestions"),
                    ) {
                        suggestions.forEach { c -> SuggestionRow(c, c.key in liveKeys) { pick(c) } }
                    }
                }
            }
        }
        // O nome sozinho (sem sugestão) vale para a plataforma escolhida (no YouTube, o @handle ou o nome do canal: a live dele é procurada).
        for (p in listOf(Platform.Twitch, Platform.Kick, Platform.YouTube)) {
            ActionButton(p.label, { platform = p }, Modifier.testTag("multi-platform-${p.name}"), active = platform == p)
        }
        IconBtn(Icons.Rounded.Add, "Adicionar", { submit() }, Modifier.testTag("multi-add"), tint = ApexColors.Accent)
    }
}

/** A lista de sugestões fica logo abaixo do campo, alinhada à esquerda dele. */
private object BelowField : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(anchorBounds.left, anchorBounds.bottom + 6)
}

@Composable
private fun SuggestionRow(c: Channel, live: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().hoverable(source).background(if (hovered) ApexColors.SurfaceHighest else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag("multi-suggestion-${c.key}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(c.platform.color(), CircleShape))
        Text(c.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.handle?.let { Text(it, color = ApexColors.Muted, fontSize = 12.sp, maxLines = 1) }
        if (live) Text("AO VIVO", color = ApexColors.Live, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        else Text(c.platform.label, color = ApexColors.Faint, fontSize = 11.sp)
    }
}

/** Os canais seguidos que combinam com [query] (nome, @handle ou login), os ao vivo primeiro, depois os que começam com o texto; até 8. */
internal fun matchChannels(query: String, subs: List<Channel>, liveKeys: Set<String>): List<Channel> {
    val q = query.trim().removePrefix("@").lowercase()
    if (q.isEmpty()) return emptyList()
    return subs
        .filter { c ->
            c.name.lowercase().contains(q) || c.handle?.removePrefix("@")?.lowercase()?.contains(q) == true ||
                (c.platform != Platform.YouTube && c.id.lowercase().contains(q))
        }
        .sortedWith(compareByDescending<Channel> { it.key in liveKeys }.thenByDescending { it.name.lowercase().startsWith(q) }.thenBy { it.name.lowercase() })
        .take(8)
}

@Composable
private fun TileGrid(tiles: List<Tile>, focus: String?, audio: Set<String>, chatKey: String?) {
    val focused = tiles.firstOrNull { it.key == focus }
    if (focused != null && tiles.size > 1) {
        // Uma em destaque, as outras numa coluna ao lado.
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(3f).fillMaxHeight()) { TileView(focused, focused.key in audio, chatKey == focused.key, true) }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in tiles) if (t.key != focused.key) Box(Modifier.weight(1f).fillMaxWidth()) { TileView(t, t.key in audio, chatKey == t.key, false) }
            }
        }
        return
    }
    val (cols, rows) = MultiStream.gridFor(tiles.size)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until cols) {
                    val t = tiles.getOrNull(r * cols + c)
                    if (t != null) Box(Modifier.weight(1f).fillMaxHeight()) { TileView(t, t.key in audio, chatKey == t.key, false) }
                    else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Uma live da grade: o vídeo, o nome por cima, o botão "AO VIVO / Voltar ao vivo" e os botões (som, chat, destaque, remover). */
@Composable
private fun TileView(tile: Tile, audioOn: Boolean, chatOn: Boolean, focused: Boolean) {
    val app = LocalApp.current
    val multi = app.multi
    val load by tile.load.collectAsState()
    val resolved by tile.resolved.collectAsState()
    val ps by tile.player.state.collectAsState()
    val media = resolved?.media ?: tile.media
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxSize().clip(shape).background(Color.Black)
            .border(2.dp, if (audioOn) ApexColors.Accent else Color.Transparent, shape).testTag("tile-${tile.key}"),
    ) {
        tile.player.Video(Modifier.fillMaxSize())
        when (val l = load) {
            is LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(34.dp), color = ApexColors.Accent, strokeWidth = 3.dp)
            is LoadState.Error -> Text(l.message, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            else -> {}
        }
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 1f to Color.Transparent)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(9.dp).background(media.platform.color(), CircleShape))
            Text(media.channel?.name ?: media.id, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            media.viewCount?.let { Text("${formatCount(it)} assistindo", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            Text(media.title, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
        }
        // "AO VIVO" quando a live está em dia; atrasada (pausa, travadas) vira "Voltar ao vivo" e reabre no ponto mais novo.
        if (load is LoadState.Ready && (resolved?.isLive ?: media.isLive)) {
            LiveEdgeButton(ps.behindLiveMs, ps.playing, Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("tile-live-${tile.key}")) { tile.player.jumpToLive() }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconBtn(
                if (audioOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (audioOn) "Silenciar" else "Som nesta live",
                { multi.toggleAudio(tile.key) }, Modifier.testTag("tile-audio-${tile.key}"), size = 32.dp, iconSize = 18.dp,
                tint = if (audioOn) ApexColors.Accent else Color.White,
            )
            IconBtn(
                Icons.Rounded.Chat, if (chatOn) "Esconder o chat" else "Chat desta live",
                { if (chatOn) app.ui.multiChat = false else { multi.setChat(tile.key); app.ui.multiChat = true } },
                Modifier.testTag("tile-chat-${tile.key}"), size = 32.dp, iconSize = 18.dp, tint = if (chatOn) ApexColors.Accent else Color.White,
            )
            IconBtn(
                if (focused) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull, if (focused) "Voltar à grade" else "Destacar",
                { multi.toggleFocus(tile.key) }, Modifier.testTag("tile-focus-${tile.key}"), size = 32.dp, iconSize = 16.dp, tint = Color.White,
            )
            IconBtn(Icons.Rounded.Close, "Remover do Multi", { multi.remove(tile.key) }, Modifier.testTag("tile-remove-${tile.key}"), size = 32.dp, iconSize = 18.dp, tint = Color.White)
        }
    }
}

/** O chat de uma das lives, com uma aba por live. */
@Composable
private fun MultiChat(tiles: List<Tile>, chatKey: String?, modifier: Modifier) {
    val app = LocalApp.current
    val current = tiles.firstOrNull { it.key == chatKey } ?: tiles.first()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipBar(Modifier.fillMaxWidth()) {
            items(tiles, key = { it.key }) { t ->
                ActionButton(t.media.channel?.name ?: t.media.id, { app.multi.setChat(t.key) }, Modifier.testTag("multi-chat-tab-${t.key}"), active = t.key == current.key)
            }
        }
        // O "ocultar" do painel esconde o chat do Multi (e não o ajuste "Chat nas lives" da página do vídeo).
        ChatPanel(current.media, Modifier.weight(1f).fillMaxWidth(), onHide = { app.ui.multiChat = false })
    }
}
