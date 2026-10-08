package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.ui.watch.WatchScreen
import app.apex.ui.watch.theaterPlayerHeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Modo cinema: o player ocupa quase toda a altura da tela (e nunca passa de 16:9 da largura). */
class TheaterTest {
    private fun approx(expected: Dp, actual: Dp) = assertTrue(kotlin.math.abs(expected.value - actual.value) < 1f, "esperava $expected, veio $actual")

    @Test
    fun altura_do_player_no_modo_cinema() {
        // janela larga: limitada pela altura (tela - espiada do título), bem mais que os 74% de antes
        approx(820.dp, theaterPlayerHeight(1600.dp, 900.dp))
        assertTrue(theaterPlayerHeight(1600.dp, 900.dp) > 900.dp * 0.74f + 100.dp)
        // janela estreita e alta: o 16:9 da largura manda
        approx(450.dp, theaterPlayerHeight(800.dp, 1000.dp))
        // janela baixa: nunca fica minúsculo
        assertEquals(true, theaterPlayerHeight(1600.dp, 300.dp) >= 180.dp)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun na_tela_o_player_do_modo_cinema_tem_a_largura_toda_e_a_altura_nova() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.session.open(Media(Platform.Twitch, "gaules", "Live", Channel(Platform.Twitch, "gaules", "Gaules"), isLive = true, url = "https://www.twitch.tv/gaules"))
        container.ui.theater = true
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(1600.dp, 900.dp)) { WatchScreen(fullscreen = false) } } }
        waitForIdle()
        val bounds = onNodeWithTag("theater-player").getBoundsInRoot()
        approx(1600.dp, bounds.right - bounds.left)
        approx(820.dp, bounds.bottom - bounds.top)
    }
}
