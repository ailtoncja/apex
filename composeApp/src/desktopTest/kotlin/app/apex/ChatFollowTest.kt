package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.chat.ReplayPage
import app.apex.chat.ReplaySource
import app.apex.model.ChatMessage
import app.apex.model.ReplayMessage
import app.apex.ui.watch.ReplayChatPanel
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/** O chat acompanha as mensagens novas; rolar para cima para de acompanhar e o botão "mensagens novas" volta ao fim. */
class ChatFollowTest {
    /** Uma mensagem por segundo do vídeo ("mensagem 0", "mensagem 1"…). */
    private class Source : ReplaySource {
        override suspend fun load(fromMs: Long, token: String?): ReplayPage {
            val msgs = (0 until 2_000).map { it * 1000L }.filter { it >= fromMs && it < fromMs + 5_000 }
                .map { ReplayMessage(it, ChatMessage("m${it / 1000}", "u${it / 1000}", "mensagem ${it / 1000}")) }
            return ReplayPage(msgs, nextFromMs = fromMs + 5_000, end = false)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun acompanha_para_ao_rolar_para_cima_e_o_botao_volta_para_as_recentes() = runComposeUiTest {
        val (app, _) = newTestApp()
        val position = AtomicLong(40_000)
        setContent {
            CompositionLocalProvider(LocalApp provides app) {
                Box(Modifier.requiredSize(360.dp, 420.dp)) { ReplayChatPanel(Source(), "vod", { position.get() }) }
            }
        }
        // Cada linha tem o autor e o texto ("u91  mensagem 91").
        fun shown(n: Long) = onAllNodesWithText("mensagem $n", substring = true).fetchSemanticsNodes()
            .any { node -> node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().any { it.text.endsWith(" mensagem $n") } }
        // O replay lê em blocos: a mais nova na tela fica até uns segundos atrás da posição.
        fun recentShown() = (0L..5L).any { shown(position.get() / 1000 - it) }
        val running = thread(isDaemon = true) {
            try { while (true) { Thread.sleep(150); position.addAndGet(1_000) } } catch (_: InterruptedException) {}
        }
        try {
            // Acompanha: a mensagem mais nova está sempre na tela, sem botão.
            runCatching { waitUntil(timeoutMillis = 8_000) { recentShown() } }.onFailure {
                val texts: List<String> = onAllNodesWithText("mensagem", substring = true).fetchSemanticsNodes()
                    .map { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().joinToString { s -> s.text } }
                error("posição ${position.get()}; na tela: ${texts.take(3)} … ${texts.takeLast(3)} (${texts.size})")
            }
            assertTrue(onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isEmpty(), "nas recentes não tem botão")

            // Rolar para cima: para de acompanhar; chegam mensagens e o botão conta as novas.
            onNodeWithTag("chat-messages").performMouseInput { moveTo(center); repeat(5) { scroll(-3f) } }
            waitUntil(timeoutMillis = 4_000) { onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 6_000) { onAllNodesWithText("mensagens novas", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(!recentShown(), "rolado para cima, as novas não puxam a lista para baixo")

            // O botão leva às recentes e volta a acompanhar (e continua acompanhando as que chegam depois).
            onNodeWithTag("chat-jump-latest").performClick()
            waitUntil(timeoutMillis = 4_000) { onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isEmpty() }
            waitUntil(timeoutMillis = 6_000) { recentShown() }
            Thread.sleep(1_500)
            waitUntil(timeoutMillis = 6_000) { recentShown() }
            assertTrue(onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isEmpty())
        } finally {
            running.interrupt()
        }
    }
}
