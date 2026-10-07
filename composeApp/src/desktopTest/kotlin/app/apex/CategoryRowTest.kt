package app.apex

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.LiveCategory
import app.apex.state.Loadable
import app.apex.ui.live.CategoryRowContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fileira de categorias não some quando a carga falha: tenta de novo sozinha e, se não der, avisa com um botão. */
class CategoryRowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val minecraft = listOf(LiveCategory("1", "Minecraft", 5_000, null))

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun falha_passageira_se_resolve_sozinha() = runComposeUiTest {
        val calls = AtomicInteger(0)
        val source = Loadable(scope, emptyList<LiveCategory>()) { if (calls.getAndIncrement() == 0) error("A Twitch não respondeu") else minecraft }
        setContent { CategoryRowContent("Categorias na Twitch", source, "as categorias da Twitch") {} }
        source.loadIfNeeded()
        waitUntil(timeoutMillis = 10_000) { source.value.value.isNotEmpty() }
        waitForIdle()
        assertTrue(onAllNodesWithText("Minecraft").fetchSemanticsNodes().isNotEmpty(), "a fileira apareceu depois da nova tentativa")
        assertEquals(2, calls.get(), "uma falha e uma nova tentativa")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun falha_que_continua_mostra_aviso_e_o_botao_tenta_de_novo() = runComposeUiTest {
        val calls = AtomicInteger(0)
        val source = Loadable(scope, emptyList<LiveCategory>()) { if (calls.incrementAndGet() <= 4) error("A Twitch não respondeu") else minecraft }
        setContent { CategoryRowContent("Categorias na Twitch", source, "as categorias da Twitch") {} }
        source.loadIfNeeded()
        // 1 carga + 3 novas tentativas automáticas, todas falhando.
        waitUntil(timeoutMillis = 30_000) { calls.get() >= 4 && !source.loading.value }
        waitForIdle()
        assertTrue(onAllNodesWithText("Categorias na Twitch").fetchSemanticsNodes().isNotEmpty(), "o título fica mesmo com a falha")
        assertTrue(onAllNodesWithText("A Twitch não respondeu").fetchSemanticsNodes().isNotEmpty(), "o aviso explica o que houve")
        onAllNodesWithText("Tentar de novo").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { source.value.value.isNotEmpty() }
        waitForIdle()
        assertTrue(onAllNodesWithText("Minecraft").fetchSemanticsNodes().isNotEmpty())
    }
}
