package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import app.apex.chat.ChatReplay
import app.apex.chat.ReplayPage
import app.apex.chat.ReplaySource
import app.apex.model.ChatMessage
import app.apex.model.ReplayMessage
import app.apex.source.InnerTube
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import app.apex.source.YouTubeSource
import app.apex.source.parseJson
import app.apex.ui.watch.ReplayChatPanel
import app.apex.util.formatIsoMillis
import app.apex.util.parseIsoMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** O chat gravado (VOD da Twitch/Kick e live encerrada do YouTube) acompanhando o vídeo. */
class ChatReplayTest {
    /** Uma mensagem por segundo do vídeo (m0 em 0 s, m1 em 1 s…), lidas em janelas de 5 s como a Kick faz. */
    private class FakeSource(val total: Int = 900, val failFirst: Int = 0, val endsAt: Int? = null) : ReplaySource {
        val requestedFrom = CopyOnWriteArrayList<Long>()
        private val attempts = AtomicInteger()
        override suspend fun load(fromMs: Long, token: String?): ReplayPage {
            requestedFrom += fromMs
            if (attempts.incrementAndGet() <= failFirst) error("sem rede")
            val msgs = (0 until total).map { it * 1000L }.filter { it >= fromMs && it < fromMs + 5_000 }
                .map { ReplayMessage(it, ChatMessage("m${it / 1000}", "u${it / 1000}", "mensagem ${it / 1000}")) }
            val end = endsAt != null && fromMs + 5_000 >= endsAt * 1000L
            return ReplayPage(msgs, nextFromMs = fromMs + 5_000, end = end)
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 4_000, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < timeoutMs) { delay(20); waited += 20 }
        assertTrue(cond(), "a condição não chegou a valer em ${timeoutMs}ms")
    }

    private fun newReplay(source: ReplaySource, position: AtomicLong): Pair<ChatReplay, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return ChatReplay(scope, source, { position.get() }, tickMs = 10) to scope
    }

    private fun ChatReplay.last(): Int? = messages.value.lastOrNull()?.id?.removePrefix("m")?.toIntOrNull()

    // ------------------------------------------------------------------ o controlador

    @Test
    fun mostra_so_o_que_ja_aconteceu_ate_a_posicao_do_video_e_acompanha_quando_ela_avanca() = runBlocking {
        val position = AtomicLong(30_000)
        val (replay, scope) = newReplay(FakeSource(), position)
        replay.start()
        waitFor { replay.last() == 30 }
        assertTrue(replay.messages.value.all { it.id.removePrefix("m").toInt() <= 30 }, "nada do futuro aparece")
        assertTrue(replay.messages.value.first().id.removePrefix("m").toInt() <= 12, "um pouco do que veio antes entra como contexto")
        position.set(45_000)
        waitFor { replay.last() == 45 }
        assertTrue(replay.messages.value.map { it.id }.let { it.size == it.toSet().size }, "sem mensagens repetidas")
        scope.cancel()
    }

    @Test
    fun pausado_nao_fica_lendo_mais_do_que_precisa() = runBlocking {
        val source = FakeSource()
        val position = AtomicLong(60_000)
        val (replay, scope) = newReplay(source, position)
        replay.start()
        waitFor { replay.last() == 60 }
        delay(300)
        val calls = source.requestedFrom.size
        delay(500)
        assertEquals(calls, source.requestedFrom.size, "vídeo parado: a leitura também para")
        scope.cancel()
    }

    @Test
    fun pular_para_tras_ou_para_longe_recomeca_a_leitura_dali() = runBlocking {
        val source = FakeSource()
        val position = AtomicLong(400_000)
        val (replay, scope) = newReplay(source, position)
        replay.start()
        waitFor { replay.last() == 400 }
        // para trás, antes do que ainda estava guardado
        source.requestedFrom.clear()
        position.set(50_000)
        waitFor { replay.last() == 50 }
        assertTrue(replay.messages.value.all { it.id.removePrefix("m").toInt() <= 50 }, "depois de voltar, nada do que estava à frente")
        assertEquals(30_000L, source.requestedFrom.first(), "recomeça 20 s antes do ponto (contexto)")
        // para bem à frente
        source.requestedFrom.clear()
        position.set(700_000)
        waitFor { replay.last() == 700 }
        assertEquals(680_000L, source.requestedFrom.first())
        assertTrue(replay.messages.value.none { it.id.removePrefix("m").toInt() < 680 }, "só o trecho novo")
        scope.cancel()
    }

    @Test
    fun falha_de_rede_mostra_aviso_e_se_recupera_sozinha() = runBlocking {
        val position = AtomicLong(20_000)
        val (replay, scope) = newReplay(FakeSource(failFirst = 2), position)
        replay.start()
        waitFor { replay.status.value.contains("Sem conexão") }
        waitFor(8_000) { replay.last() == 20 }
        assertEquals("Chat gravado", replay.status.value)
        scope.cancel()
    }

    @Test
    fun no_fim_do_chat_gravado_para_de_ler() = runBlocking {
        val source = FakeSource(endsAt = 60)
        val position = AtomicLong(55_000)
        val (replay, scope) = newReplay(source, position)
        replay.start()
        waitFor { replay.last() == 55 }
        delay(300)
        val calls = source.requestedFrom.size
        delay(400)
        assertEquals(calls, source.requestedFrom.size, "acabou: não lê mais")
        assertFalse(replay.messages.value.any { it.id == "m70" })
        scope.cancel()
    }

    // ------------------------------------------------------------------ o formato de cada plataforma

    @Test
    fun twitch_mensagens_do_vod() {
        val comments = parseJson(
            """{"edges":[
              {"cursor":"c1","node":{"id":"a1","commenter":{"displayName":"Ana","login":"ana"},"contentOffsetSeconds":587,
                "message":{"fragments":[{"text":"oi "},{"text":"Kappa","emote":{"emoteID":"25"}}],"userColor":"#FF7F50","userBadges":[{"setID":"subscriber"},{"setID":""}]}}},
              {"cursor":"c2","node":{"id":"a2","commenter":null,"contentOffsetSeconds":590,"message":{"fragments":[{"text":"anônimo"}],"userBadges":[]}}},
              {"cursor":"c3","node":{"id":"a3","commenter":{"login":"x"},"contentOffsetSeconds":591,"message":{"fragments":[],"userBadges":[]}}}],
              "pageInfo":{"hasNextPage":true}}""",
        )!!
        val page = TwitchSource().parseVideoComments(comments)
        assertEquals(2, page.messages.size, "a mensagem sem texto não entra")
        val ana = page.messages[0]
        assertEquals(587_000L, ana.offsetMs, "o ponto do vídeo vem em segundos e vira ms")
        assertEquals("Ana", ana.message.author)
        assertEquals("oi Kappa", ana.message.text)
        assertEquals(0xFFFF7F50, ana.message.color)
        assertEquals(listOf("subscriber"), ana.message.badges)
        assertEquals("?", page.messages[1].message.author, "conta apagada")
        assertTrue(page.hasNext)
    }

    @Test
    fun kick_mensagens_do_vod_pelo_horario_da_transmissao() {
        val start = parseIsoMillis("2026-10-07 13:23:28")!!
        val msg = parseJson(
            """{"id":"k1","content":"oi [emote:37226:KEKW] galera","created_at":"2026-10-07T13:33:30Z","sender":{"username":"lunow","identity":{"color":"#F2708A","badges":[{"type":"moderator"}]}}}""",
        )
        val r = KickSource().replayMessage(msg, start)!!
        assertEquals(602_000L, r.offsetMs, "13:33:30 - 13:23:28 = 10 min 2 s")
        assertEquals("oi KEKW galera", r.message.text)
        assertEquals("lunow", r.message.author)
        assertEquals(listOf("moderator"), r.message.badges)
        val before = parseJson("""{"id":"k2","content":"x","created_at":"2026-10-07T13:20:00Z","sender":{"username":"a"}}""")
        assertEquals(0L, KickSource().replayMessage(before, start)!!.offsetMs, "nunca negativo")
    }

    @Test
    fun youtube_mensagens_da_live_encerrada_e_proxima_pagina() {
        val resp = parseJson(
            """{"continuationContents":{"liveChatContinuation":{"actions":[
              {"replayChatItemAction":{"actions":[{"addChatItemAction":{"item":{"liveChatViewerEngagementMessageRenderer":{"id":"e","message":{"runs":[{"text":"aviso do YouTube"}]}}}}}],"videoOffsetTimeMsec":"0"}},
              {"replayChatItemAction":{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"m1","authorName":{"simpleText":"@ana"},
                 "message":{"runs":[{"text":"oi "},{"emoji":{"shortcuts":[":joia:"]}}]},"authorBadges":[{"liveChatAuthorBadgeRenderer":{}}]}}}}],"videoOffsetTimeMsec":"12345"}},
              {"replayChatItemAction":{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"m0","authorName":{"simpleText":"@bia"},"message":{"runs":[{"text":"antes"}]}}}}}],"videoOffsetTimeMsec":"2000"}}],
              "continuations":[{"liveChatReplayContinuationData":{"timeUntilLastMessageMsec":5000,"continuation":"PROXIMA"}}]}}}""",
        )!!
        val page = YouTubeSource(InnerTube()).parseChatReplay(resp)
        assertEquals(listOf("m0", "m1"), page.messages.map { it.message.id }, "só mensagens de texto, por ordem do vídeo")
        assertEquals(listOf(2_000L, 12_345L), page.messages.map { it.offsetMs })
        assertEquals("oi :joia:", page.messages[1].message.text)
        assertEquals(listOf("moderator"), page.messages[1].message.badges)
        assertEquals("PROXIMA", page.next)
        assertEquals(null, YouTubeSource(InnerTube()).parseChatReplay(parseJson("""{"continuationContents":{}}""")!!).next, "sem continuação = acabou")
    }

    @Test
    fun hora_em_texto_ida_e_volta() {
        for (text in listOf("2026-10-07T13:33:28.000Z", "1970-01-01T00:00:00.000Z", "2024-02-29T23:59:59.000Z", "2000-12-31T00:00:01.000Z", "2026-03-01T12:00:00.000Z")) {
            val ms = parseIsoMillis(text)!!
            assertEquals(text, formatIsoMillis(ms), text)
        }
        assertEquals("2026-10-07T13:43:28.000Z", formatIsoMillis(parseIsoMillis("2026-10-07 13:23:28")!! + 20 * 60_000))
    }

    // ------------------------------------------------------------------ interface

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun painel_do_replay_mostra_as_mensagens_do_ponto_do_video() = runComposeUiTest {
        val (container, _) = newTestApp()
        val position = AtomicLong(30_000)
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                ReplayChatPanel(FakeSource(), "vod-1", { position.get() })
            }
        }
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("mensagem 30", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Replay do chat").fetchSemanticsNodes().isNotEmpty(), "o título diz que é replay")
        assertTrue(onAllNodesWithText("mensagem 45", substring = true).fetchSemanticsNodes().isEmpty(), "o que ainda não aconteceu não aparece")
        assertNotNull(onAllNodesWithText("Chat gravado", substring = true).fetchSemanticsNodes().firstOrNull())
        position.set(50_000)
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("mensagem 50", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
