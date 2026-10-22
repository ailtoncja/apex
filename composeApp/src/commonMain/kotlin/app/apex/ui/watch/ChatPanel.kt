package app.apex.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Link
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.apex.AppContainer
import app.apex.chat.ChannelEmotes
import app.apex.chat.LinkedChat
import app.apex.chat.UnifiedChat
import app.apex.chat.liveChannelKey
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
import app.apex.theme.color
import app.apex.ui.components.IconBtn
import app.apex.ui.components.linkify
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.launch

/** O chat de uma plataforma: [chatId] é o login (Twitch), o slug (Kick) ou o id do vídeo ao vivo (YouTube); [channelId], o canal (para os emotes). */
private fun newChatClient(app: AppContainer, platform: Platform, chatId: String, channelId: String?): ChatClient = when (platform) {
    Platform.Twitch -> TwitchChat(
        app.scope, chatId,
        token = { app.twitch.authToken },
        accountLogin = { app.data.account(Platform.Twitch)?.displayName },
        history = { app.twitch.recentChatMessages(chatId) },
        extensions = { id -> app.extensionEmotes.forTwitchChannel(id) },
        natives = { ChannelEmotes(emptyList(), app.twitch.globalEmotes(), app.twitch.myEmotes()) },
    )
    Platform.Kick -> KickChat(app.scope, chatId, app.kick, extensions = { id -> app.extensionEmotes.forKickChannel(id) })
    Platform.YouTube -> YouTubeChat(app.scope, chatId, app.youtube, channelId = channelId, extensions = { id -> app.extensionEmotes.forYouTubeChannel(id) })
}

/**
 * O chat de uma live (ao vivo). Se o mesmo streamer está ao vivo em outras plataformas (vinculadas à mão, ou achadas pelo mesmo nome e
 * confirmadas), os chats delas entram juntos: a aba "Todos" mostra tudo numa coluna (cada mensagem com a cor da plataforma) e cada plataforma
 * tem a sua aba para ver só aquele chat.
 */
@Composable
fun ChatPanel(media: Media, modifier: Modifier = Modifier, onHide: (() -> Unit)? = null) {
    val app = LocalApp.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by app.data.settings.collectAsState()
    val primary: ChatClient = remember(media.key) { newChatClient(app, media.platform, media.id, media.channel?.id) }
    val ownKey = remember(media.key) { liveChannelKey(media) }
    // Os outros chats do mesmo streamer ao vivo agora: os vinculados e (uma de cada vez) a sugestão pelo mesmo nome.
    val linked by produceState(emptyList<LinkedChat>(), media.key, settings.chatLinks, settings.chatLinkRejected) {
        value = runCatching { app.linkedChats(media) }.getOrDefault(emptyList())
    }
    val confirmed = linked.filter { it.confirmed }
    val suggestion = linked.firstOrNull { !it.confirmed }
    val others = remember(media.key, confirmed.map { it.channelKey + "|" + it.chatId }) { confirmed.map { newChatClient(app, it.platform, it.chatId, it.channelId) } }
    val clients = remember(primary, others) { listOf(primary) + others }
    val unified = remember(clients) { UnifiedChat(app.scope, clients) }
    DisposableEffect(unified) {
        unified.start()
        onDispose { unified.stop() }
    }
    // null = "Todos" (só existe com mais de um chat).
    var tab by remember(media.key) { mutableStateOf<Platform?>(null) }
    val shownTab = tab?.takeIf { p -> clients.any { it.platform == p } }
    val single = clients.size == 1 || shownTab != null
    val shownClient = clients.firstOrNull { it.platform == (shownTab ?: media.platform) } ?: primary
    // Onde a mensagem vai: a plataforma da aba aberta; na aba "Todos", a escolhida no campo (de início, a desta página).
    var target by remember(media.key) { mutableStateOf(media.platform) }
    val sendClient = if (shownTab != null) shownClient else clients.firstOrNull { it.platform == target } ?: primary

    val unifiedMessages by unified.messages.collectAsState()
    val singleMessages by shownClient.messages.collectAsState()
    val messages = if (single) singleMessages else unifiedMessages
    val statuses = clients.map { c -> c to c.status.collectAsState().value }
    val status = if (single) statuses.first { it.first === shownClient }.second
    else statuses.filter { it.second == "Chat ao vivo" }.map { it.first.platform.label }.let { live ->
        if (live.isEmpty()) statuses.first().second else "Ao vivo: ${live.joinToString(", ")}"
    }
    val active = statuses.any { it.second == "Chat ao vivo" }
    val extensions = clients.associate { c -> c.platform to (if (settings.chatExtensionEmotes) c.extensionEmotes.collectAsState().value else ChannelEmotes.Empty) }
    val natives by sendClient.nativeEmotes.collectAsState()
    val client = sendClient
    var pickerOpen by remember(media.key) { mutableStateOf(false) }
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

    ChatSurface(
        "Chat da live", active, status, messages, modifier, onHide, extensions[shownClient.platform] ?: ChannelEmotes.Empty,
        emotesFor = if (single) null else { p -> extensions[p] ?: ChannelEmotes.Empty },
        showPlatform = !single,
        actions = { if (ownKey != null) LinkChatsButton(media, ownKey, confirmed) },
        top = {
            if (clients.size > 1) ChatTabs(clients.map { it.platform }, shownTab) { tab = it }
            suggestion?.let { s -> if (ownKey != null) LinkSuggestion(media, ownKey, s) }
        },
    ) {
        if (!single) SendTarget(clients, sendClient.platform) { target = it }
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
            // Emojis e emotes para escrever (da plataforma e das extensões), em qualquer chat em que dá para escrever.
            if (client.canSend) {
                Box {
                    IconBtn(
                        Icons.Rounded.EmojiEmotions, "Emotes", { pickerOpen = !pickerOpen }, Modifier.testTag("emote-button"),
                        size = 36.dp, iconSize = 20.dp, tint = if (pickerOpen) ApexColors.Accent else ApexColors.OnSurface,
                    )
                    if (pickerOpen) {
                        Popup(
                            alignment = Alignment.BottomEnd, offset = IntOffset(0, -44),
                            onDismissRequest = { pickerOpen = false }, properties = PopupProperties(focusable = true),
                        ) {
                            EmotePicker(
                                extensions[client.platform] ?: ChannelEmotes.Empty, natives,
                                // O emoji entra colado (como num teclado); o emote, como palavra separada (é assim que ele vira imagem).
                                onEmoji = { e -> text += e },
                                onPick = { e -> text = (text.trimEnd() + " " + e.name + " ").trimStart() },
                            )
                        }
                    }
                }
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
    val vodEmotes by produceState(ChannelEmotes.Empty, media.key) {
        if (media.platform == Platform.Twitch) value = app.extensionEmotesForLogin(media.channel?.id)
    }
    if (settings.showChat) {
        ReplayChatPanel(
            found, media.key, { app.player.state.value.positionMs }, modifier.fillMaxWidth().height(520.dp),
            if (settings.chatExtensionEmotes) vodEmotes else ChannelEmotes.Empty,
        )
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
internal fun ReplayChatPanel(source: ReplaySource, key: Any, position: () -> Long, modifier: Modifier = Modifier, emotes: ChannelEmotes = ChannelEmotes.Empty) {
    val app = LocalApp.current
    val replay = remember(source, key) { ChatReplay(app.scope, source, position) }
    DisposableEffect(replay) {
        replay.start()
        onDispose { replay.stop() }
    }
    val messages by replay.messages.collectAsState()
    val status by replay.status.collectAsState()
    ChatSurface("Replay do chat", false, status, messages, modifier, emotes = emotes) {
        Text(
            "Chat gravado: as mensagens acompanham o vídeo.",
            Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted, maxLines = 1,
        )
    }
}

/**
 * A moldura do chat (ao vivo ou gravado): título, estado, a lista de mensagens que desce sozinha e o rodapé de cada um. No chat unificado
 * [emotesFor] dá os emotes da plataforma de cada mensagem e [showPlatform] põe a cor dela ao lado; [actions] entra no título e [top], logo abaixo.
 */
@Composable
private fun ChatSurface(
    title: String, active: Boolean, status: String, messages: List<ChatMessage>, modifier: Modifier, onHide: (() -> Unit)? = null,
    emotes: ChannelEmotes = ChannelEmotes.Empty,
    emotesFor: ((Platform?) -> ChannelEmotes)? = null,
    showPlatform: Boolean = false,
    actions: @Composable () -> Unit = {},
    top: @Composable () -> Unit = {},
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
            actions()
            // Oculta o chat inteiro (e para de ler as mensagens); "Mostrar chat" volta pela coluna ao lado do vídeo.
            IconBtn(Icons.Rounded.VisibilityOff, "Ocultar o chat", onHide ?: { app.data.updateSettings { it.copy(showChat = false) } }, Modifier.testTag("chat-hide"), size = 28.dp, iconSize = 18.dp)
        }
        top()
        // As mensagens dão para selecionar e copiar; os links nelas abrem (os das plataformas dentro do app, os outros no navegador).
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // No chat unificado dois chats podem usar o mesmo id: a chave leva a plataforma.
                items(messages, key = { "${it.platform}:${it.id}" }) { m ->
                    // Os emotes da plataforma e os das extensões viram imagem; os links abrem.
                    val known = (emotesFor?.invoke(m.platform) ?: emotes).byName
                    if (showPlatform && m.platform != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.padding(top = 7.dp).size(7.dp).background(m.platform.color(), CircleShape).testTag("chat-platform-${m.platform.name}"))
                            ChatLine(m, known, authorColor(m.color)) { url -> if (!app.openLink(url)) app.system.openUrl(url) }
                        }
                    } else {
                        ChatLine(m, known, authorColor(m.color)) { url -> if (!app.openLink(url)) app.system.openUrl(url) }
                    }
                }
            }
        }
        footer()
    }
}

/** As abas do chat unificado: "Todos" e uma por plataforma (cada uma com a cor dela). */
@Composable
private fun ChatTabs(platforms: List<Platform>, selected: Platform?, onSelect: (Platform?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        (listOf<Platform?>(null) + platforms).forEach { p ->
            val on = p == selected
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) ApexColors.Accent else ApexColors.Background)
                    .clickable { onSelect(p) }.padding(horizontal = 10.dp, vertical = 4.dp).testTag("chat-tab-${p?.name ?: "Todos"}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (p != null) Box(Modifier.size(7.dp).background(p.color(), CircleShape))
                Text(p?.label ?: "Todos", color = if (on) Color.White else ApexColors.OnSurface, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A sugestão achada pelo nome: "também está ao vivo na Kick — juntar os chats?". Juntar vincula; "Não é o mesmo" não sugere mais esse par. */
@Composable
private fun LinkSuggestion(media: Media, ownKey: String, suggestion: LinkedChat) {
    val app = LocalApp.current
    val who = media.channel?.name ?: media.id
    Row(
        Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(start = 12.dp, end = 8.dp, bottom = 8.dp).testTag("chat-link-suggestion"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(suggestion.platform.color(), CircleShape))
        Text(
            "$who também está ao vivo na ${suggestion.platform.label} (${suggestion.name}). Juntar os chats?",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ApexColors.OnSurface, maxLines = 2,
        )
        Text(
            "Juntar",
            Modifier.clip(RoundedCornerShape(50)).background(ApexColors.Accent).clickable { app.linkChats(ownKey, suggestion.channelKey) }
                .padding(horizontal = 10.dp, vertical = 4.dp).testTag("chat-link-accept"),
            color = Color.White, style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "Não é o mesmo",
            Modifier.clip(RoundedCornerShape(50)).clickable { app.unlinkChat(suggestion.channelKey, ownKey) }
                .padding(horizontal = 6.dp, vertical = 4.dp).testTag("chat-link-reject"),
            color = ApexColors.Muted, style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * O botão de vincular, no título do chat: abre um painel para juntar o chat de outro canal do mesmo streamer (escolhendo a plataforma e escrevendo
 * o nome, o @ ou o link) e para separar os que já estão juntos.
 */
@Composable
private fun LinkChatsButton(media: Media, ownKey: String, linked: List<LinkedChat>) {
    val app = LocalApp.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember(media.key) { mutableStateOf(false) }
    Box {
        IconBtn(
            Icons.Rounded.Link, "Juntar o chat de outra plataforma", { open = !open }, Modifier.testTag("chat-link-button"),
            size = 28.dp, iconSize = 18.dp, tint = if (linked.isNotEmpty() || open) ApexColors.Accent else ApexColors.OnSurface,
        )
        if (!open) return@Box
        Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 36), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            val choices = Platform.entries - media.platform
            var platform by remember { mutableStateOf(choices.first()) }
            var name by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            Column(
                Modifier.width(320.dp).clip(RoundedCornerShape(12.dp)).background(ApexColors.SurfaceHigh)
                    .border(1.dp, ApexColors.Outline, RoundedCornerShape(12.dp)).padding(12.dp).testTag("chat-link-panel"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Juntar o chat de outra plataforma", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Quando o streamer estiver ao vivo lá também, as mensagens aparecem juntas na aba \"Todos\".",
                    style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { p ->
                        val on = p == platform
                        Text(
                            p.label,
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) ApexColors.Accent else ApexColors.Background)
                                .clickable { platform = p }.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = if (on) Color.White else ApexColors.OnSurface, style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(ApexColors.Background).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (name.isEmpty()) Text("Nome do canal na ${platform.label} (ou @, ou link)", color = ApexColors.Faint, fontSize = 13.sp)
                    BasicTextField(
                        name, { name = it }, singleLine = true, textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 13.sp),
                        cursorBrush = SolidColor(ApexColors.Accent), modifier = Modifier.fillMaxWidth().testTag("chat-link-name"),
                    )
                }
                ActionButton(if (busy) "Procurando…" else "Juntar", {
                    if (busy || name.isBlank()) return@ActionButton
                    busy = true
                    scope.launch {
                        val found = runCatching { app.findChannelForLink(platform, name) }.getOrNull()
                        busy = false
                        if (found == null) app.toast("Não achei esse canal na ${platform.label}.")
                        else {
                            app.linkChats(ownKey, found.first)
                            app.toast("Chat de ${found.second} (${platform.label}) junto. Aparece quando estiver ao vivo.")
                            name = ""
                            open = false
                        }
                    }
                }, Modifier.testTag("chat-link-save"))
                if (linked.isNotEmpty()) {
                    Text("Juntos agora", style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted)
                    linked.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).background(l.platform.color(), CircleShape))
                            Text("${l.name} (${l.platform.label})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Separar",
                                Modifier.clip(RoundedCornerShape(50)).clickable { app.unlinkChat(l.channelKey, ownKey) }.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = ApexColors.Accent, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Na aba "Todos": para qual chat vai a mensagem (só os que aceitam a conta logada podem ser escolhidos). */
@Composable
private fun SendTarget(clients: List<ChatClient>, selected: Platform, onSelect: (Platform) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh).padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Enviar em:", style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted)
        clients.forEach { c ->
            val on = c.platform == selected
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) ApexColors.Background else Color.Transparent)
                    .clickable { onSelect(c.platform) }.padding(horizontal = 8.dp, vertical = 3.dp).testTag("chat-send-${c.platform.name}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(7.dp).background(c.platform.color(), CircleShape))
                Text(
                    c.platform.label, style = MaterialTheme.typography.labelMedium,
                    color = when {
                        on -> ApexColors.OnSurface
                        c.canSend -> ApexColors.Muted
                        else -> ApexColors.Faint
                    },
                )
            }
        }
    }
}

/** O nome de quem escreveu tem a cor que a pessoa escolheu na plataforma, ajustada para ler bem sobre o fundo do chat (em qualquer tema). */
private fun authorColor(argb: Long?): Color = readableOn(if (argb != null) Color(argb) else ApexColors.OnSurface, ApexColors.Surface)
