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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
import app.apex.chat.ChatClient
import app.apex.chat.KickChat
import app.apex.chat.TwitchChat
import app.apex.chat.YouTubeChat
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.theme.ApexColors
import app.apex.ui.components.IconBtn
import kotlinx.coroutines.launch

@Composable
fun ChatPanel(media: Media, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client: ChatClient = remember(media.key) {
        when (media.platform) {
            Platform.Twitch -> TwitchChat(
                app.scope, media.id,
                token = { app.twitch.authToken },
                accountLogin = { app.data.account(Platform.Twitch)?.displayName },
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
    val listState = rememberLazyListState()
    var text by remember(media.key) { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (messages.isNotEmpty() && last >= info.totalItemsCount - 3) listState.scrollToItem(messages.lastIndex)
    }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty() || !client.canSend) return
        text = ""
        scope.launch { if (!client.send(t)) app.toast("Não foi possível enviar a mensagem") }
    }

    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(ApexColors.Surface)
            .border(1.dp, ApexColors.Outline, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(if (status == "Chat ao vivo") ApexColors.Live else ApexColors.Faint, CircleShape))
            Text("Chat da live", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1)
            // Oculta o chat inteiro (e para de ler as mensagens); "Mostrar chat" volta pela coluna ao lado do vídeo.
            IconBtn(Icons.Rounded.VisibilityOff, "Ocultar o chat", { app.data.updateSettings { it.copy(showChat = false) } }, size = 28.dp, iconSize = 18.dp)
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
                        withStyle(SpanStyle(color = Color(m.color ?: 0xFFFFFFFF), fontWeight = FontWeight.Bold)) { append(m.author) }
                        append("  ")
                        append(m.text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(ApexColors.Background).padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (text.isEmpty()) {
                    Text(
                        if (client.canSend) "Enviar mensagem" else "Entre na conta para escrever no chat",
                        color = ApexColors.Faint, fontSize = 14.sp, maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text, onValueChange = { if (client.canSend) text = it }, singleLine = true,
                    textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(ApexColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { app.ui.typing = it.isFocused },
                )
            }
            IconBtn(Icons.Rounded.Send, "Enviar", { submit() }, size = 36.dp, iconSize = 18.dp, tint = if (client.canSend) ApexColors.Accent else ApexColors.Faint)
        }
    }
}
