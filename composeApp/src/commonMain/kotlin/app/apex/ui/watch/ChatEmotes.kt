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

/**
 * A lista de emotes para escrever (7TV, BetterTTV e FrankerFaceZ, como as extensões oferecem no navegador): os do canal primeiro, depois os
 * globais, com busca pelo nome. Clicar escreve o nome na mensagem; quem tem a extensão (e o app) vê a imagem.
 */
@Composable
internal fun EmotePicker(emotes: ChannelEmotes, onPick: (Emote) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    fun List<Emote>.matching() = distinctBy { it.name }.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    val channel = emotes.channel.matching()
    val global = emotes.global.matching().filter { g -> channel.none { it.name == g.name } }
    Column(
        modifier.width(340.dp).height(320.dp).clip(RoundedCornerShape(12.dp)).background(ApexColors.SurfaceHigh)
            .border(1.dp, ApexColors.Outline, RoundedCornerShape(12.dp)).testTag("emote-picker"),
    ) {
        Box(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(50)).background(ApexColors.Background).padding(horizontal = 12.dp, vertical = 7.dp)) {
            if (query.isEmpty()) Text("Buscar emote", color = ApexColors.Faint, fontSize = 13.sp)
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = TextStyle(color = ApexColors.OnSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(ApexColors.Accent), modifier = Modifier.fillMaxWidth().testTag("emote-search"),
            )
        }
        if (channel.isEmpty() && global.isEmpty()) {
            Text(
                if (emotes.channel.isEmpty() && emotes.global.isEmpty()) "Carregando os emotes…" else "Nenhum emote com esse nome",
                Modifier.padding(14.dp), color = ApexColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(40.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (channel.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { PickerHeader("Deste canal") }
                items(channel, key = { "c-" + it.name }) { PickerEmote(it, onPick) }
            }
            if (global.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { PickerHeader("Globais") }
                items(global, key = { "g-" + it.name }) { PickerEmote(it, onPick) }
            }
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
