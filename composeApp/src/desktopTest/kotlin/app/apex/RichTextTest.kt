package app.apex

import app.apex.ui.components.findLinks
import app.apex.ui.components.linkUrl
import app.apex.ui.components.linkify
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Endereços dentro de descrições, comentários e chat viram links clicáveis; o texto em volta fica como está. */
class RichTextTest {
    @Test
    fun acha_os_enderecos_e_deixa_a_pontuacao_de_fora() {
        val text = "veja https://youtu.be/abc123, e www.twitch.tv/gaules. (ou http://kick.com/x) fim"
        val links = findLinks(text).map { text.substring(it) }
        assertEquals(listOf("https://youtu.be/abc123", "www.twitch.tv/gaules", "http://kick.com/x"), links)
        assertTrue(findLinks("sem endereço nenhum aqui").isEmpty())
        assertTrue(findLinks("e-mail@exemplo.com e 10:30 não são links").isEmpty())
    }

    @Test
    fun www_ganha_https_e_o_texto_anotado_mantem_tudo() {
        assertEquals("https://www.twitch.tv/x", linkUrl("www.twitch.tv/x"))
        assertEquals("https://a.b/c", linkUrl("https://a.b/c"))
        val opened = mutableListOf<String>()
        val annotated = linkify("ola https://a.b/c tchau", Color.Red) { opened += it }
        assertEquals("ola https://a.b/c tchau", annotated.text, "o texto não muda")
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        assertEquals("https://a.b/c", (links.single().item as LinkAnnotation.Url).url)
        assertEquals(4, links.single().start); assertEquals(17, links.single().end)
    }
}
