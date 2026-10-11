package app.apex.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.state.CommentBox
import app.apex.state.MAX_COMMENT_LENGTH
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.Avatar

/**
 * O campo "Adicione um comentário…" do topo dos comentários. Sem conta do YouTube mostra o convite para entrar; com os comentários desligados
 * no vídeo, o aviso. Ctrl+Enter também publica.
 */
@Composable
fun CommentComposer(media: Media) {
    val app = LocalApp.current
    val watch = app.screens.watch
    val accounts by app.data.accounts.collectAsState()
    val account = accounts[Platform.YouTube.name]
    // Entrou ou saiu da conta com o vídeo aberto: o campo acompanha.
    LaunchedEffect(media.key, account != null) { watch.syncCommentBox(media) }

    when (val box = watch.commentBox) {
        CommentBox.Loading -> {}
        CommentBox.LoggedOut -> Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Entre na sua conta do YouTube para comentar.", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
            ActionButton("Entrar", { app.nav.goRoot(Route.Settings) })
        }
        CommentBox.Disabled -> Text(
            "Não é possível comentar neste vídeo (os comentários estão desativados).",
            style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted,
        )
        is CommentBox.Ready -> Composer(media, account?.displayName ?: "Você", account?.avatarUrl)
    }
}

@Composable
private fun Composer(media: Media, name: String, avatarUrl: String?) {
    val app = LocalApp.current
    val watch = app.screens.watch
    val focusManager = LocalFocusManager.current
    var text by remember(media.key) { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val posting = watch.posting
    val canSend = text.isNotBlank() && !posting

    // Se a página sai de cena com o cursor no campo, o app volta a aceitar os atalhos do teclado.
    DisposableEffect(Unit) { onDispose { if (focused) app.ui.typing = false } }

    fun send() {
        if (!canSend) return
        watch.postComment(media, text) { ok -> if (ok) { text = ""; focusManager.clearFocus() } }
    }

    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(avatarUrl, name, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.fillMaxWidth().clip(shape).background(ApexColors.SurfaceHigh)
                    .border(1.dp, if (focused) ApexColors.Accent.copy(alpha = 0.8f) else ApexColors.Outline, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (text.isEmpty()) Text("Adicione um comentário…", style = MaterialTheme.typography.bodyMedium, color = ApexColors.Faint)
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_COMMENT_LENGTH) text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ApexColors.OnSurface),
                    cursorBrush = SolidColor(ApexColors.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("comment-input")
                        .onFocusChanged { focused = it.isFocused; app.ui.typing = it.isFocused }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && it.isCtrlPressed) { send(); true } else false
                        },
                )
            }
            if (focused || text.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // O contador só aparece perto do limite.
                    Text(
                        if (text.length > MAX_COMMENT_LENGTH - 500) "${text.length}/$MAX_COMMENT_LENGTH" else "",
                        Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                    )
                    ActionButton("Cancelar", { text = ""; focusManager.clearFocus() })
                    Box(Modifier.testTag("comment-send")) {
                        ActionButton(if (posting) "Publicando…" else "Comentar", { send() }, primary = canSend)
                    }
                }
            }
        }
    }
}
