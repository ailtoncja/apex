package app.apex

import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.apex.chat.ChannelEmotes
import app.apex.chat.Emote
import app.apex.chat.EmoteProvider
import app.apex.chat.emotesFromFragments
import app.apex.chat.parse7tvEmotes
import app.apex.chat.parseBttvEmotes
import app.apex.chat.parseFfzEmotes
import app.apex.chat.parseKickEmotes
import app.apex.chat.parseTwitchEmotesTag
import app.apex.chat.twitchEmoteUrl
import app.apex.chat.withExtensionEmotes
import app.apex.model.ChatEmote
import app.apex.model.ChatMessage
import app.apex.source.TwitchSource
import app.apex.source.list
import app.apex.source.parseJson
import app.apex.ui.watch.EmotePicker
import app.apex.ui.watch.chatLineText
import app.apex.ui.watch.emoteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Emotes no chat: os da Twitch e da Kick, e os das extensões (7TV, BetterTTV, FrankerFaceZ) que a comunidade da Twitch usa. */
class EmotesTest {
    @Test
    fun emotes_da_twitch_pela_etiqueta_do_irc_contando_emoji_como_um_caractere() {
        // "😀" ocupa 2 posições no texto do app (UTF-16) e 1 na conta da Twitch.
        val text = "😀 Kappa oi Kappa PogChamp"
        val emotes = parseTwitchEmotesTag("25:2-6,11-15/88:17-24", text)
        assertEquals(listOf("Kappa", "Kappa", "PogChamp"), emotes.map { it.name })
        assertEquals(listOf(3 to 8, 12 to 17, 18 to 26), emotes.map { it.start to it.end })
        assertEquals(twitchEmoteUrl("25"), emotes[0].url)
        assertTrue(parseTwitchEmotesTag(null, text).isEmpty())
        assertTrue(parseTwitchEmotesTag("25:90-99", text).isEmpty(), "posição fora do texto é ignorada")
    }

    @Test
    fun emotes_em_pedacos_do_historico_e_do_chat_gravado() {
        val (text, emotes) = emotesFromFragments(listOf("muito bom " to null, "LUL" to "425618", " kkk" to null))
        assertEquals("muito bom LUL kkk", text)
        assertEquals(listOf(ChatEmote(10, 13, "LUL", twitchEmoteUrl("425618"))), emotes)

        // O formato real da Twitch no chat gravado (VideoCommentsByOffsetOrCursor).
        val comments = parseJson(
            """{"edges":[{"node":{"id":"c1","contentOffsetSeconds":3600,"commenter":{"displayName":"Fulano"},
              "message":{"fragments":[{"emote":null,"text":"oi "},{"emote":{"emoteID":"emotesv2_9520d8","from":3},"text":"catJAM"}],
              "userColor":"#FF0000","userBadges":[]}}}],"pageInfo":{"hasNextPage":false}}""",
        )!!
        val m = TwitchSource().parseVideoComments(comments).messages.single().message
        assertEquals("oi catJAM", m.text)
        assertEquals(listOf(ChatEmote(3, 9, "catJAM", twitchEmoteUrl("emotesv2_9520d8"))), m.emotes)

        // O histórico do chat (recentChatMessages), com o pedaço do emote marcado como Emote.
        val recent = parseJson(
            """{"channel":{"recentChatMessages":[{"id":"r1","sentAt":"2026-10-12T10:00:00Z","deletedAt":null,
              "content":{"text":"Kappa ok","fragments":[{"text":"Kappa","content":{"__typename":"Emote","id":"25"}},{"text":" ok","content":null}]},
              "sender":{"login":"a","displayName":"A","chatColor":null},"senderBadges":[]}]}}""",
        )
        val r = TwitchSource().parseRecentChat(recent).single()
        assertEquals(listOf(ChatEmote(0, 5, "Kappa", twitchEmoteUrl("25"))), r.emotes)
    }

    @Test
    fun figurinhas_da_kick_viram_emotes() {
        val (text, emotes) = parseKickEmotes("oi [emote:37227:KEKW] tudo [emote:1:catJAM]")
        assertEquals("oi KEKW tudo catJAM", text)
        assertEquals(listOf(3 to 7, 13 to 19), emotes.map { it.start to it.end })
        assertEquals("https://files.kick.com/emotes/37227/fullsize", emotes[0].url)
    }

    @Test
    fun extensoes_trocam_a_palavra_exata_sem_passar_por_cima_da_twitch() {
        val known = mapOf(
            "KEKW" to Emote("KEKW", "https://cdn.7tv.app/emote/a/2x.webp", 1f, EmoteProvider.SevenTv),
            "Clap" to Emote("Clap", "https://cdn.betterttv.net/emote/b/2x", 2f, EmoteProvider.Bttv),
        )
        val text = "KEKW kekw Clap, Clap KEKW"
        val native = listOf(ChatEmote(21, 25, "KEKW", twitchEmoteUrl("1")))
        val all = withExtensionEmotes(text, native, known)
        // "kekw" (minúsculas) e "Clap," (com vírgula) não são o emote; o último KEKW já era da Twitch.
        assertEquals(listOf(0 to 4, 16 to 20, 21 to 25), all.map { it.start to it.end })
        assertEquals(listOf("7tv.app", "betterttv", "jtvnw"), all.map { e -> listOf("7tv.app", "betterttv", "jtvnw").first { it in e.url } })
        assertEquals(2f, all[1].ratio)
    }

    @Test
    fun listas_da_7tv_bttv_e_ffz_no_formato_real() {
        val seven = parse7tvEmotes(parseJson(
            """{"emotes":[{"id":"x","name":"catJAM","data":{"animated":true,"host":{"url":"//cdn.7tv.app/emote/01ABC",
              "files":[{"name":"1x.avif","width":32,"height":32},{"name":"2x.webp","width":128,"height":64}]}}}]}""",
        ))
        assertEquals(Emote("catJAM", "https://cdn.7tv.app/emote/01ABC/2x.webp", 2f, EmoteProvider.SevenTv, animated = true), seven.single())

        val bttv = parseBttvEmotes(parseJson("""[{"id":"55b6f480e66682f576dd94f5","code":"Clap","imageType":"gif","animated":true}]""").list())
        assertEquals("https://cdn.betterttv.net/emote/55b6f480e66682f576dd94f5/2x", bttv.single().url)
        assertTrue(bttv.single().animated)

        val ffz = parseFfzEmotes(parseJson(
            """[{"id":9,"code":"ZrehplaR","images":{"1x":"https://cdn.betterttv.net/frankerfacez_emote/9/1","2x":"https://cdn.betterttv.net/frankerfacez_emote/9/2"},"animated":false}]""",
        ).list())
        assertEquals("https://cdn.betterttv.net/frankerfacez_emote/9/2", ffz.single().url)
        assertEquals("FrankerFaceZ", emoteSource(ffz.single().url))
        assertEquals("BetterTTV", emoteSource(bttv.single().url))
        assertEquals("7TV", emoteSource(seven.single().url))
    }

    @Test
    fun o_do_canal_vale_mais_que_o_global_e_a_7tv_mais_que_as_outras() {
        fun e(name: String, p: EmoteProvider) = Emote(name, "https://${p.name}/$name", 1f, p)
        val set = ChannelEmotes(
            channel = listOf(e("KEKW", EmoteProvider.SevenTv), e("KEKW", EmoteProvider.Bttv), e("Pog", EmoteProvider.Ffz)),
            global = listOf(e("Pog", EmoteProvider.SevenTv), e("EZ", EmoteProvider.Bttv)),
        )
        assertEquals(EmoteProvider.SevenTv, set.byName["KEKW"]!!.provider)
        assertEquals(EmoteProvider.Ffz, set.byName["Pog"]!!.provider, "o do canal ganha do global, mesmo de outra extensão")
        assertEquals(EmoteProvider.Bttv, set.byName["EZ"]!!.provider)
    }

    @Test
    fun a_linha_do_chat_poe_os_emotes_no_lugar_e_copiar_traz_o_nome() {
        val m = ChatMessage("1", "Fulano", "oi KEKW veja https://apex.app", emotes = emptyList())
        val emotes = listOf(ChatEmote(3, 7, "KEKW", "https://cdn.7tv.app/emote/a/2x.webp", 2f))
        val (text, inline) = chatLineText(m, emotes, Color.Red, Color.Blue, emptyMap()) {}
        // O texto (o que se copia) tem o nome do emote; a imagem ocupa o lugar dele.
        assertEquals("Fulano  oi KEKW veja https://apex.app", text.text)
        assertEquals(setOf("emote|https://cdn.7tv.app/emote/a/2x.webp"), inline.keys)
        assertEquals(48f, inline.values.single().placeholder.width.value, "emote largo: o dobro da altura")
        // Quando a imagem carrega e a proporção real é outra, o espaço segue a real.
        val (_, inline2) = chatLineText(m, emotes, Color.Red, Color.Blue, mapOf("https://cdn.7tv.app/emote/a/2x.webp" to 1f)) {}
        assertEquals(24f, inline2.values.single().placeholder.width.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun o_seletor_mostra_os_do_canal_e_os_globais_busca_e_escreve() = runComposeUiTest {
        val (app, _) = newTestApp()
        val picked = mutableListOf<String>()
        val set = ChannelEmotes(
            channel = listOf(Emote("tribo", "https://x/tribo", 1f, EmoteProvider.SevenTv)),
            global = listOf(Emote("EZ", "https://x/ez", 1f, EmoteProvider.Bttv), Emote("Clap", "https://x/clap", 1f, EmoteProvider.Bttv)),
        )
        // Os da própria plataforma: o global da Twitch e um de inscrito que a conta pode usar.
        val natives = ChannelEmotes(
            channel = emptyList(),
            global = listOf(Emote("Kappa", "https://x/kappa", 1f, EmoteProvider.Twitch)),
            mine = listOf(Emote("canalHype", "https://x/hype", 1f, EmoteProvider.Twitch)),
        )
        val emojis = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalApp provides app) { EmotePicker(set, natives, onEmoji = { emojis += it }, onPick = { picked += it.name }) }
        }
        waitForIdle()
        onNodeWithTag("picker-tab-Emotes").performClick()
        waitForIdle()
        onNodeWithText("Deste canal").assertExists()
        onNodeWithText("Seus emotes").assertExists()
        onNodeWithText("Globais").assertExists()
        onNodeWithTag("emote-Kappa").assertExists()
        onNodeWithTag("emote-canalHype").assertExists()
        onNodeWithTag("emote-tribo").performClick()
        assertEquals(listOf("tribo"), picked)
        onNodeWithTag("emote-search").performTextInput("cla")
        waitForIdle()
        onNodeWithTag("emote-Clap").assertExists()
        onNodeWithTag("emote-EZ").assertDoesNotExist()
        onNodeWithTag("emote-tribo").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emoji_sai_colorido() = runComposeUiTest {
        // O Skia usa a fonte de emoji do Windows (Segoe UI Emoji): o 😀 tem de sair amarelo, não um contorno cinza.
        setContent { androidx.compose.material3.Text("😀", androidx.compose.ui.Modifier.testTag("emoji"), fontSize = 48.sp) }
        waitForIdle()
        val pixels = onNodeWithTag("emoji").captureToImage().toPixelMap()
        var yellow = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.red > 0.7f && c.green > 0.5f && c.blue < 0.35f) yellow++
        }
        assertTrue(yellow > 50, "pontos amarelos: $yellow")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_aba_de_emoji_busca_em_portugues_e_escreve_o_emoji() = runComposeUiTest {
        val (app, _) = newTestApp()
        val emojis = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalApp provides app) {
                EmotePicker(ChannelEmotes.Empty, ChannelEmotes.Empty, onEmoji = { emojis += it }, onPick = {})
            }
        }
        waitForIdle()
        onNodeWithTag("picker-tab-Emoji").performClick()
        waitForIdle()
        onNodeWithText("Rostos").assertExists()
        onNodeWithTag("emote-search").performTextInput("bandeira brasil")
        waitForIdle()
        onNodeWithText("🇧🇷").performClick()
        assertEquals(listOf("🇧🇷"), emojis)
    }
}
