package app.apex

import app.apex.chat.EmojiCatalog
import app.apex.chat.Emote
import app.apex.chat.EmoteProvider
import app.apex.chat.encodeKickEmotes
import app.apex.chat.searchEmoji
import app.apex.source.parseJson
import app.apex.source.parseTwitchEmoteSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Os emojis do Unicode no seletor do chat, e os emotes da Twitch e da Kick para escrever. */
class EmojiTest {
    @Test
    fun a_lista_de_emojis_vem_inteira_e_por_grupo() {
        val all = EmojiCatalog.all
        assertTrue(all.size > 1800, "${all.size} emojis")
        assertEquals(listOf("Rostos", "Pessoas", "Natureza", "Comida", "Lugares", "Atividades", "Objetos", "Símbolos", "Bandeiras"), EmojiCatalog.groups)
        assertEquals("😀", all.first().emoji)
        assertTrue(all.none { "skin tone" in it.keywords.first() }, "sem as variações de tom de pele")
        assertTrue(all.any { it.emoji == "🇧🇷" }, "com as bandeiras")
    }

    @Test
    fun busca_em_portugues_ou_ingles_com_ou_sem_acento() {
        val all = EmojiCatalog.all
        assertTrue(searchEmoji(all, "coração").any { it.emoji == "❤️" })
        assertTrue(searchEmoji(all, "coracao").any { it.emoji == "❤️" }, "sem acento também acha")
        assertTrue(searchEmoji(all, "heart").any { it.emoji == "❤️" }, "em inglês também")
        assertTrue(searchEmoji(all, "risada").isNotEmpty())
        assertTrue(searchEmoji(all, "brasil").any { it.emoji == "🇧🇷" })
        assertEquals(all, searchEmoji(all, "  "))
    }

    @Test
    fun emotes_da_twitch_sem_as_carinhas_antigas() {
        val set = parseJson("""{"emotes":[{"id":"25","token":"Kappa"},{"id":"1","token":"\\:-?\\)"},{"id":"9","token":"<3"},{"id":"x","token":"PogChamp"}]}""")
        assertEquals(listOf("Kappa", "PogChamp"), parseTwitchEmoteSet(set).map { it.name })
        assertTrue(parseTwitchEmoteSet(set).all { it.url.startsWith("https://static-cdn.jtvnw.net/emoticons/v2/") })
    }

    @Test
    fun na_kick_o_emote_vai_com_o_codigo_dela() {
        val kick = mapOf("xqcLK" to Emote("xqcLK", "https://files.kick.com/emotes/1153261/fullsize", 1f, EmoteProvider.Kick, sendAs = "[emote:1153261:xqcLK]"))
        assertEquals("oi [emote:1153261:xqcLK] tudo bem 😀", encodeKickEmotes("oi xqcLK tudo bem 😀", kick))
        assertEquals("xqcLKs não é emote", encodeKickEmotes("xqcLKs não é emote", kick))
    }
}
