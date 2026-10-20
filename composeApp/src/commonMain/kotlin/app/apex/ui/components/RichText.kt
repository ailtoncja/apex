package app.apex.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import app.apex.LocalApp
import app.apex.theme.ApexColors

/** Os endereços (`http://`, `https://` ou `www.`) dentro de um texto, como faixas de índices; a pontuação que fecha a frase não entra. */
internal fun findLinks(text: String): List<IntRange> = LINK.findAll(text).map { m ->
    var end = m.range.last
    while (end > m.range.first && text[end] in TRAILING) end--
    m.range.first..end
}.toList()

private val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`]+""")
private const val TRAILING = ".,;:!?)]}'\""

/** O endereço pronto para abrir: os que começam por `www.` ganham o `https://`. */
internal fun linkUrl(raw: String): String = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw

/** [text] com os endereços clicáveis (na cor [linkColor], sublinhados ao passar o mouse); [onOpen] recebe o endereço clicado. */
fun linkify(text: String, linkColor: Color, onOpen: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (range in findLinks(text)) {
        append(text, at, range.first)
        val raw = text.substring(range.first, range.last + 1)
        val url = linkUrl(raw)
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(style = SpanStyle(color = linkColor), hoveredStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            ) { onOpen(url) },
        ) { append(raw) }
        at = range.last + 1
    }
    append(text, at, text.length)
}

/**
 * Texto que dá para selecionar e copiar (arrastando com o mouse, com Ctrl+C ou pelo botão direito) e com os endereços clicáveis: links de
 * vídeo, live, canal ou clipe do YouTube, da Twitch e da Kick abrem dentro do app; os outros vão para o navegador.
 */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val app = LocalApp.current
    val annotated = linkify(text, ApexColors.Accent) { url -> if (!app.openLink(url)) app.system.openUrl(url) }
    SelectionContainer(modifier) { Text(annotated, style = style, color = color, maxLines = maxLines, overflow = overflow) }
}

/** Texto comum que dá para selecionar e copiar (nomes de canal, títulos). */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    SelectionContainer(modifier) { Text(text, style = style, color = color, maxLines = maxLines, overflow = overflow) }
}
