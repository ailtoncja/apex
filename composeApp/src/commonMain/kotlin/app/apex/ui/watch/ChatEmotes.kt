package app.apex.ui.watch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apex.chat.ChannelEmotes
import app.apex.chat.Emote
import app.apex.chat.EmoteRatios
import app.apex.chat.withExtensionEmotes
import app.apex.model.ChatEmote
import app.apex.model.ChatMessage
import app.apex.theme.ApexColors
import app.apex.ui.components.EmoteImage
import app.apex.ui.components.linkify

/** A altura dos emotes no chat (o texto tem 14 sp): como na Twitch, um pouco mais alto que a letra. */
private const val EMOTE_SP = 24f

/** De onde veio o emote, pelo endereço da imagem (aparece ao passar o mouse). */
internal fun emoteSource(url: String): String = when {
    "7tv.app" in url -> "7TV"
    "frankerfacez" in url -> "FrankerFaceZ"
    "betterttv" in url -> "BetterTTV"
    "jtvnw.net" in url -> "Twitch"
    "kick.com" in url -> "Kick"
    else -> ""
}

/**
 * O texto da mensagem com os emotes (os da plataforma e os das extensões que estão em [known]) no lugar das palavras e os links clicáveis.
 * Copiar a mensagem copia o nome de cada emote. Devolve também o espaço reservado de cada imagem.
 */
internal fun chatLineText(
    m: ChatMessage, emotes: List<ChatEmote>, authorColor: Color, linkColor: Color, ratios: Map<String, Float>, onLink: (String) -> Unit,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = authorColor, fontWeight = FontWeight.Bold)) { append(m.author) }
        append("  ")
        var at = 0
        for (e in emotes) {
            if (e.start < at || e.end > m.text.length || e.start >= e.end) continue
            if (e.start > at) append(linkify(m.text.substring(at, e.start), linkColor, onLink))
            appendInlineContent("emote|${e.url}", e.name)
            at = e.end
        }
        if (at < m.text.length) append(linkify(m.text.substring(at), linkColor, onLink))
    }
    val inline = emotes.distinctBy { it.url }.associate { e ->
        val ratio = (ratios[e.url] ?: e.ratio).coerceIn(0.25f, 4f)
        "emote|${e.url}" to InlineTextContent(Placeholder((EMOTE_SP * ratio).sp, EMOTE_SP.sp, PlaceholderVerticalAlign.Center)) {
            EmoteWithName(e.name, e.url)
        }
    }
    return text to inline
}

/** Uma linha do chat: quem escreveu, na cor dela, e a mensagem com os emotes. */
@Composable
internal fun ChatLine(m: ChatMessage, known: Map<String, Emote>, authorColor: Color, onLink: (String) -> Unit) {
    val emotes = remember(m, known) { withExtensionEmotes(m.text, m.emotes, known) }
    val (text, inline) = chatLineText(m, emotes, authorColor, ApexColors.Accent, EmoteRatios.known, onLink)
    Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyMedium)
}

/** A imagem do emote; com o mouse em cima, o nome dele e de onde vem (Twitch, 7TV, BetterTTV, FrankerFaceZ). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmoteWithName(name: String, url: String) {
    TooltipArea(
        tooltip = {
            Text(
                listOf(name, emoteSource(url)).filter { it.isNotEmpty() }.joinToString(" • "),
                Modifier.clip(RoundedCornerShape(6.dp)).background(ApexColors.SurfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        },
        delayMillis = 300,
    ) {
        EmoteImage(url, Modifier.fillMaxSize())
    }
}

/** A aba do seletor que ficou aberta por último (vale para todos os chats enquanto o app está aberto). */
private var lastPickerTab by mutableStateOf(0)

/**
 * O seletor do chat, com duas abas. "Emoji": todos os emojis do Unicode por grupo (Rostos, Comida, Bandeiras…), com busca em português ou
 * inglês. "Emotes": os da própria plataforma (Twitch, Kick) e os das extensões (7TV, BetterTTV, FrankerFaceZ): os do canal, os que a conta pode
 * usar e os globais. Clicar põe na mensagem: o emoji, ou o nome do emote (a Kick recebe o código dela na hora de enviar).
 */
@Composable
internal fun EmotePicker(
    extensions: ChannelEmotes, natives: ChannelEmotes, onEmoji: (String) -> Unit, onPick: (Emote) -> Unit, modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val tab = lastPickerTab
    Column(
        modifier.width(360.dp).height(380.dp).clip(RoundedCornerShape(12.dp)).background(ApexColors.SurfaceHigh)
            .border(1.dp, ApexColors.Outline, RoundedCornerShape(12.dp)).testTag("emote-picker"),
    ) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Emoji", "Emotes").forEachIndexed { i, label ->
                val on = tab == i
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) ApexColors.Accent else ApexColors.Background)
                        .clickable { lastPickerTab = i }.padding(horizontal = 14.dp, vertical = 6.dp).testTag("picker-tab-$label"),
                    color = if (on) Color.White else ApexColors.OnSurface, style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(50)).background(ApexColors.Background).padding(horizontal = 12.dp, vertical = 7.dp)) {
            if (query.isEmpty()) Text(if (tab == 0) "Buscar emoji (ex.: coração, risada)" else "Buscar emote", color = ApexColors.Faint, fontSize = 13.sp)
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(ApexColors.Accent), modifier = Modifier.fillMaxWidth().testTag("emote-search"),
            )
        }
        if (tab == 0) EmojiGrid(query, onEmoji) else EmoteGrid(extensions, natives, query, onPick)
    }
}

/** Os emojis do Unicode, por grupo, filtrados por [query]. */
@Composable
private fun EmojiGrid(query: String, onEmoji: (String) -> Unit) {
    val found = remember(query) { app.apex.chat.searchEmoji(app.apex.chat.EmojiCatalog.all, query) }
    if (found.isEmpty()) {
        Text(
            if (app.apex.chat.EmojiCatalog.all.isEmpty()) "A lista de emojis não carregou." else "Nenhum emoji com esse nome",
            Modifier.padding(14.dp), color = ApexColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val byGroup = remember(found) { found.groupBy { it.group } }
    LazyVerticalGrid(
        GridCells.Adaptive(36.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp).testTag("emoji-grid"),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        byGroup.forEach { (group, list) ->
            item(key = "h-$group", span = { GridItemSpan(maxLineSpan) }) { PickerHeader(group) }
            items(list, key = { "e-" + it.emoji }) { e -> PickerEmoji(e, onEmoji) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerEmoji(entry: app.apex.chat.EmojiEntry, onEmoji: (String) -> Unit) {
    TooltipArea(
        tooltip = {
            Text(
                entry.name,
                Modifier.clip(RoundedCornerShape(6.dp)).background(ApexColors.SurfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        },
        delayMillis = 400,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { onEmoji(entry.emoji) },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(entry.emoji, fontSize = 22.sp)
        }
    }
}

/** Os emotes da plataforma e das extensões: os do canal, os da conta e os globais (sem repetir nomes), filtrados por [query]. */
@Composable
private fun EmoteGrid(extensions: ChannelEmotes, natives: ChannelEmotes, query: String, onPick: (Emote) -> Unit) {
    val q = query.trim()
    fun List<Emote>.matching() = distinctBy { it.name }.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    val channel = (natives.channel + extensions.channel).matching()
    val mine = natives.mine.matching().filter { m -> channel.none { it.name == m.name } }
    val global = (natives.global + extensions.global).matching().filter { g -> channel.none { it.name == g.name } && mine.none { it.name == g.name } }
    if (channel.isEmpty() && mine.isEmpty() && global.isEmpty()) {
        val nothing = natives.channel.isEmpty() && natives.global.isEmpty() && extensions.channel.isEmpty() && extensions.global.isEmpty()
        Text(
            if (nothing) "Carregando os emotes…" else "Nenhum emote com esse nome",
            Modifier.padding(14.dp), color = ApexColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(40.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (channel.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { PickerHeader("Deste canal") }
            items(channel, key = { "c-" + it.name }) { PickerEmote(it, onPick) }
        }
        if (mine.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { PickerHeader("Seus emotes") }
            items(mine, key = { "m-" + it.name }) { PickerEmote(it, onPick) }
        }
        if (global.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { PickerHeader("Globais") }
            items(global, key = { "g-" + it.name }) { PickerEmote(it, onPick) }
        }
    }
}

@Composable
private fun PickerHeader(title: String) {
    Text(title, Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = ApexColors.Muted)
}

@Composable
private fun PickerEmote(emote: Emote, onPick: (Emote) -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(emote) }.padding(4.dp).testTag("emote-" + emote.name)) {
        EmoteWithName(emote.name, emote.url)
    }
}
