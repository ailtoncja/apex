package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import app.apex.ui.shell.ACCELERATING
import app.apex.ui.shell.RALLY_DURATION_MS
import app.apex.ui.shell.RallyIntro
import app.apex.ui.shell.rallyCarPath
import app.apex.ui.shell.rallyCarWidth
import app.apex.ui.shell.rallyCarX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A silhueta do carro de rali que acelera pela tela ao trocar para um tema Subaru. */
class RallyIntroTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun o_carro_aparece_so_ao_trocar_para_um_tema_subaru_e_some_quando_termina() = runComposeUiTest {
        mainClock.autoAdvance = false
        var theme by mutableStateOf("dark")
        setContent { Box(Modifier.fillMaxSize()) { RallyIntro(theme) } }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("rally-intro").assertDoesNotExist()

        theme = "subaru-azul"
        mainClock.advanceTimeBy(100)
        onNodeWithTag("rally-intro").assertExists("a troca para o Subaru azul dispara o carro")
        mainClock.advanceTimeBy(RALLY_DURATION_MS + 500L)
        onNodeWithTag("rally-intro").assertDoesNotExist()

        theme = "subaru-preto"
        mainClock.advanceTimeBy(100)
        onNodeWithTag("rally-intro").assertExists("de um Subaru para o outro também")
        mainClock.advanceTimeBy(RALLY_DURATION_MS + 500L)

        theme = "light"
        mainClock.advanceTimeBy(100)
        onNodeWithTag("rally-intro").assertDoesNotExist()
        theme = "dark"
        mainClock.advanceTimeBy(100)
        onNodeWithTag("rally-intro").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun abrir_o_app_ja_no_tema_subaru_nao_dispara_o_carro() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { Box(Modifier.fillMaxSize()) { RallyIntro("subaru-preto") } }
        mainClock.advanceTimeBy(200)
        onNodeWithTag("rally-intro").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun no_app_inteiro_escolher_um_tema_subaru_nos_ajustes_dispara_o_carro() = runComposeUiTest {
        mainClock.autoAdvance = false
        val (container, _) = newTestApp()
        container.nav.goRoot(app.apex.nav.Route.Settings)
        setContent { androidx.compose.runtime.CompositionLocalProvider(LocalApp provides container) { ApexApp() } }
        try {
            mainClock.advanceTimeBy(300)
            onNodeWithTag("rally-intro").assertDoesNotExist()
            container.data.updateSettings { it.copy(theme = "subaru-preto") }
            mainClock.advanceTimeBy(200)
            onNodeWithTag("rally-intro").assertExists("o carro passa por cima do app inteiro")
            mainClock.advanceTimeBy(RALLY_DURATION_MS + 500L)
            onNodeWithTag("rally-intro").assertDoesNotExist()
        } finally {
            app.apex.theme.ApexColors.palette = app.apex.theme.Themes.Dark
        }
    }

    @Test
    fun a_silhueta_tem_corpo_asa_e_recortes_das_rodas_e_das_janelas() {
        val path = rallyCarPath(10f)
        val b = path.getBounds()
        assertTrue(b.left >= 0f && b.right <= 1000f && b.top >= 0f && b.bottom <= 280f, "cabe na caixa de 100 x 28: $b")
        assertTrue(b.right - b.left > 950f, "ocupa a largura toda (da asa ao nariz)")
        // Um ponto no meio do vidro da porta da frente é recorte; um no capô e um na asa são carro; um no arco da roda é recorte.
        val skia = path.asSkiaPath()
        fun inside(x: Float, y: Float): Boolean = skia.contains(x * 10f, y * 10f)
        assertTrue(inside(70f, 15f), "capô (longe do arco da roda)")
        assertTrue(inside(8f, 6f), "asa")
        assertTrue(inside(45.7f, 7f), "pilar entre os vidros")
        assertTrue(!inside(54f, 8f), "vidro da frente é recorte")
        assertTrue(!inside(36f, 7f), "vidro de trás é recorte")
        assertTrue(!inside(82.8f, 21.2f), "o arco da roda é recorte (a roda é desenhada por cima)")
    }

    @Test
    fun o_carro_entra_pela_esquerda_sai_pela_direita_e_acelera() {
        val width = 1360f
        val car = rallyCarWidth(width)
        assertEquals(408f, car, 0.01f, "30% da janela")
        assertEquals(240f, rallyCarWidth(500f), "nunca menor que 240 px")
        assertEquals(560f, rallyCarWidth(4000f), "nem maior que 560 px")
        assertTrue(rallyCarX(0f, width, car) - car < 0f, "no começo o carro inteiro está fora, à esquerda")
        assertTrue(rallyCarX(1f, width, car) - car > width, "no fim o carro inteiro já saiu pela direita")
        var last = rallyCarX(0f, width, car)
        for (i in 1..100) {
            val x = rallyCarX(ACCELERATING.transform(i / 100f), width, car)
            assertTrue(x > last, "só anda para a frente")
            last = x
        }
        assertTrue(ACCELERATING.transform(0.5f) < 0.25f, "na metade do tempo andou menos de um quarto do caminho: ${ACCELERATING.transform(0.5f)}")
        assertTrue(ACCELERATING.transform(0.9f) < 0.8f, "e termina em disparada")
    }
}
