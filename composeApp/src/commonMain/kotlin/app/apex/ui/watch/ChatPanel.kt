package app.apex.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apex.LocalApp
import app.apex.ui.components.ActionButton
import app.apex.model.ChatMessage
import app.apex.chat.replaySourceFor
import app.apex.chat.ReplaySource
import app.apex.chat.ChatReplay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.produceState
import app.apex.chat.ChatClient
import app.apex.chat.KickChat
import app.apex.chat.TwitchChat
import app.apex.chat.YouTubeChat
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.theme.ApexColors
import app.apex.theme.readableOn
import app.apex.ui.components.IconBtn
import kotlinx.coroutines.launch

/** O chat de uma live (ao vivo). */
@Composable
fun ChatPanel(media: Media, modifier: Modifier = Modifier, onHide: (() -> Unit)? = null) {
    val app = LocalApp.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client: ChatClient = remember(media.key) {
        when (media.platform) {
            Platform.Twitch -> TwitchChat(
                app.scope, media.id,
                token = { app.twitch.authToken },
                accountLogin = { app.data.account(Platform.Twitch)?.displayName },
                history = { app.twitch.recentChatMessages(media.id) },
            )
            Platform.Kick -> KickChat(app.scope, media.id, app.kick)
            Platform.YouTube -> YouTubeChat(app.scope, media.id, app.youtube)
        }
    }
    DisposableEffect(client) {
        client.start()
        onDispose { client.stop() }
    }
    val messages by client.messages.collectAsState()
    val status by client.status.collectAsState()
    var text by remember(media.key) { mutableStateOf("") }
    // Enter na página da live (ver App.kt) leva o cursor para cá.
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(app.ui.focusChatTick) { if (app.ui.focusChatTick > 0) runCatching { inputFocus.requestFocus() } }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty() || !client.canSend) return
        text = ""
        scope.launch { if (!client.send(t)) app.toast(client.lastSendError ?: "Não foi possível enviar a mensagem") }
    }

    ChatSurface("Chat da live", status == "Chat ao vivo", status, messages, modifier, onHide) {
        Row(
            Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(ApexColors.Background).padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (text.isEmpty()) {
                    Text(
                        if (client.canSend) "Enviar mensagem" else client.sendHint,
                        color = ApexColors.Faint, fontSize = 14.sp, maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text, onValueChange = { if (client.canSend) text = it }, singleLine = true,
                    textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(ApexColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).onFocusChanged { app.ui.typing = it.isFocused }.onPreviewKeyEvent {
                        // Enter envia (a ação "enviar" do teclado nem sempre dispara no desktop).
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { submit(); true } else false
                    },
                )
            }
            IconBtn(Icons.Rounded.Send, "Enviar", { submit() }, size = 36.dp, iconSize = 18.dp, tint = if (client.canSend) ApexColors.Accent else ApexColors.Faint)
        }
    }
}

/**
 * O chat gravado de um vídeo que já foi ao ar (VOD da Twitch e da Kick, live encerrada do YouTube), junto com o vídeo.
 * Não aparece nada se o vídeo não tem chat gravado.
 */
@Composable
fun ReplayChatSection(media: Media, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val settings by app.data.settings.collectAsState()
    // null = ainda conferindo ou sem chat gravado: nada na tela (o painel entra só quando existe).
    val source by produceState<ReplaySource?>(null, media.key) { value = app.replaySourceFor(media) }
    val found = source ?: return
    if (settings.showChat) {
        ReplayChatPanel(found, media.key, { app.player.state.value.positionMs }, modifier.fillMaxWidth().height(520.dp))
    } else {
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ApexColors.Surface)
                .border(1.dp, ApexColors.Outline, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chat oculto", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = ApexColors.Muted)
            ActionButton("Mostrar chat", { app.data.updateSettings { it.copy(showChat = true) } }, icon = Icons.Rounded.Visibility)
        }
    }
}

/** O painel do chat gravado: as mensagens acompanham a posição do vídeo (e voltam a ler se a pessoa pular no vídeo). */
@Composable
internal fun ReplayChatPanel(source: ReplaySource, key: Any, position: () -> Long, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val replay = remember(source, key) { ChatReplay(app.scope, source, position) }
    DisposableEffect(replay) {
        replay.start()
        onDispose { replay.stop() }
    }
    val messages by replay.messages.collectAsState()
    val status by replay.status.collectAsState()
    ChatSurface("Replay do chat", false, status, messages, modifier) {
        Text(
            "Chat gravado: as mensagens acompanham o vídeo.",
            Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1,
        )
    }
}

/** A moldura do chat (ao vivo ou gravado): título, estado, a lista de mensagens que desce sozinha e o rodapé de cada um. */
@Composable
private fun ChatSurface(
    title: String, active: Boolean, status: String, messages: List<ChatMessage>, modifier: Modifier, onHide: (() -> Unit)? = null,
    footer: @Composable () -> Unit,
) {
    val app = LocalApp.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (messages.isNotEmpty() && last >= info.totalItemsCount - 3) listState.scrollToItem(messages.lastIndex)
    }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(ApexColors.Surface)
            .border(1.dp, ApexColors.Outline, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(if (active) ApexColors.Live else ApexColors.Faint, CircleShape))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
            Text(
                status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            // Oculta o chat inteiro (e para de ler as mensagens); "Mostrar chat" volta pela coluna ao lado do vídeo.
            IconBtn(Icons.Rounded.VisibilityOff, "Ocultar o chat", onHide ?: { app.data.updateSettings { it.copy(showChat = false) } }, Modifier.testTag("chat-hide"), size = 28.dp, iconSize = 18.dp)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = authorColor(m.color), fontWeight = FontWeight.Bold)) { append(m.author) }
                        append("  ")
                        append(m.text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        footer()
    }
}

/** O nome de quem escreveu tem a cor que a pessoa escolheu na plataforma, ajustada para ler bem sobre o fundo do chat (em qualquer tema). */
private fun authorColor(argb: Long?): Color = readableOn(if (argb != null) Color(argb) else ApexColors.OnSurface, ApexColors.Surface)
