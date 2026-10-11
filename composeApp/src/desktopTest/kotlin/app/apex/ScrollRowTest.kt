package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.ui.components.MediaRow
import kotlin.test.Test

/** As fileiras horizontais (canais ao vivo, categorias…) têm setas para chegar nos cartões que não cabem na tela. */
@OptIn(ExperimentalTestApi::class)
class ScrollRowTest {
    private val left = "Rolar para a esquerda"
    private val right = "Rolar para a direita"

    private fun live(i: Int): Media {
        val channel = Channel(Platform.Twitch, "canal$i", "Canal $i")
        return Media(Platform.Twitch, "canal$i", "ao vivo $i", channel, isLive = true, url = "https://www.twitch.tv/canal$i")
    }

    @Test
    fun as_setas_levam_ate_o_ultimo_canal_ao_vivo_e_somem_nas_pontas() = runComposeUiTest {
        val (container, _) = newTestApp()
        val lives = (0 until 10).map(::live)
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1000.dp, 400.dp)) { MediaRow(lives) }
            }
        }
        waitForIdle()
        onNodeWithText("ao vivo 0").assertIsDisplayed()
        onNodeWithText("ao vivo 9").assertDoesNotExist()
        onNodeWithContentDescription(left).assertDoesNotExist() // já está no começo
        onNodeWithContentDescription(right).assertIsDisplayed()

        // 10 cartões de 290 dp: poucos cliques bastam; o limite evita repetir para sempre se algo quebrar
        var clicks = 0
        while (clicks < 10 && onAllNodesWithContentDescription(right).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithContentDescription(right).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            clicks++
        }
        onNodeWithText("ao vivo 9").assertIsDisplayed()
        onNodeWithContentDescription(right).assertDoesNotExist() // chegou no fim
        onNodeWithContentDescription(left).assertIsDisplayed()
        check(clicks in 2..6) { "esperava poucos cliques, deu $clicks" }

        // e dá para voltar
        onNodeWithContentDescription(left).performClick()
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        onNodeWithContentDescription(right).assertIsDisplayed()
    }

    @Test
    fun com_poucos_cartoes_nao_ha_setas() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1000.dp, 400.dp)) { MediaRow((0 until 2).map(::live)) }
            }
        }
        waitForIdle()
        onNodeWithText("ao vivo 1").assertIsDisplayed()
        onNodeWithContentDescription(left).assertDoesNotExist()
        onNodeWithContentDescription(right).assertDoesNotExist()
    }
    @Test
    fun a_barra_de_filtros_tem_setinhas_so_quando_os_filtros_nao_cabem() = runComposeUiTest {
        val labels = (1..12).map { "Filtro $it" }
        var wide by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            Box(Modifier.requiredSize(if (wide) 2000.dp else 400.dp, 80.dp)) { app.apex.ui.components.ChipRow(labels, 0, {}) }
        }
        waitForIdle()
        onNodeWithText("Filtro 1").assertIsDisplayed()
        onNodeWithContentDescription(left).assertDoesNotExist()
        onNodeWithContentDescription(right).assertIsDisplayed()

        var clicks = 0
        while (clicks < 12 && onAllNodesWithContentDescription(right).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithContentDescription(right).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            clicks++
        }
        onNodeWithText("Filtro 12").assertIsDisplayed()
        onNodeWithContentDescription(left).assertIsDisplayed()

        // numa janela larga, tudo cabe e as setas somem
        wide = true
        waitForIdle()
        onNodeWithContentDescription(left).assertDoesNotExist()
        onNodeWithContentDescription(right).assertDoesNotExist()
    }

    @Test
    fun a_barra_de_filtros_nao_estica_na_altura() = runComposeUiTest {
        // Dentro de uma coluna (como na tela inicial) a barra, com as setinhas, tem de ficar do tamanho dos filtros.
        val labels = (1..12).map { "Filtro $it" }
        setContent {
            androidx.compose.foundation.layout.Column(Modifier.requiredSize(400.dp, 600.dp)) {
                app.apex.ui.components.ChipRow(labels, 0, {})
                androidx.compose.material3.Text("conteudo abaixo")
            }
        }
        waitForIdle()
        onNodeWithContentDescription(right).assertIsDisplayed()
        val top = onNodeWithText("conteudo abaixo").getBoundsInRoot().top
        kotlin.test.assertTrue(top < 150.dp, "o conteúdo começa logo abaixo da barra, não $top")
    }
}
