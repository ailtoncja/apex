package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import app.apex.player.MAX_VOLUME
import app.apex.player.NORMAL_VOLUME
import app.apex.player.PlaySource
import app.apex.player.PlayerState
import app.apex.player.sliderFromVolume
import app.apex.player.stepVolume
import app.apex.player.vlcOptions
import app.apex.player.volumeFromSlider
import app.apex.ui.watch.VolumeControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Volume até 200% e menos atraso nas lives. */
class VolumeLatencyTest {
    // ------------------------------------------------------------------ volume

    @Test
    fun volume_vai_ate_200_e_o_controle_gruda_no_100() {
        assertEquals(200, MAX_VOLUME)
        assertEquals(0, volumeFromSlider(0f))
        assertEquals(100, volumeFromSlider(0.5f))
        assertEquals(100, volumeFromSlider(0.51f), "perto de 100% gruda nele")
        assertEquals(100, volumeFromSlider(0.48f))
        assertEquals(120, volumeFromSlider(0.6f))
        assertEquals(200, volumeFromSlider(1f))
        assertEquals(200, volumeFromSlider(1.7f), "nunca passa de 200")
        assertEquals(0, volumeFromSlider(-1f))
        assertEquals(1f, sliderFromVolume(200))
        assertEquals(0.5f, sliderFromVolume(NORMAL_VOLUME))
        assertEquals(1f, sliderFromVolume(999))
        assertEquals(0f, sliderFromVolume(-5))
    }

    @Test
    fun setas_do_teclado_sobem_de_5_em_5_ate_200() {
        assertEquals(105, stepVolume(100, 5), "passa de 100")
        assertEquals(200, stepVolume(198, 5))
        assertEquals(200, stepVolume(200, 5))
        assertEquals(0, stepVolume(3, -5))
        assertEquals(95, stepVolume(100, -5))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun controle_de_volume_mostra_a_porcentagem_acima_de_100() = runComposeUiTest {
        val (container, _) = newTestApp()
        (container.player as FakePlayer2).state.value = PlayerState(hasMedia = true, volume = 150)
        setContent { CompositionLocalProvider(LocalApp provides container) { VolumeControl() } }
        assertFalse(onAllNodesWithText("150%").fetchSemanticsNodes().isNotEmpty(), "o controle só aparece com o mouse em cima")
        onNodeWithContentDescription("Volume").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("150%").fetchSemanticsNodes().isNotEmpty() }
    }

    // ------------------------------------------------------------------ atraso das lives

    @Test
    fun live_com_baixa_latencia_comeca_mais_perto_do_ao_vivo_e_guarda_menos() {
        val live = vlcOptions(PlaySource("https://x/live.m3u8", live = true, lowLatency = true))
        assertTrue(":adaptive-livedelay=3000" in live, "começa ~3 segmentos antes do fim (o padrão do VLC são 8)")
        assertTrue(":network-caching=1000" in live)
        val normal = vlcOptions(PlaySource("https://x/live.m3u8", live = true, lowLatency = false))
        assertTrue(":network-caching=3000" in normal)
        assertTrue(normal.none { it.startsWith(":adaptive-livedelay") }, "desligado: volta ao jeito antigo")
    }

    @Test
    fun video_gravado_nao_usa_a_opcao_de_live_e_leva_os_outros_parametros() {
        val vod = vlcOptions(PlaySource("https://x/v.mp4", audioUrl = "https://x/a.m4a", userAgent = "UA/1", startMs = 90_000, live = false, lowLatency = true))
        assertEquals(listOf(":input-slave=https://x/a.m4a", ":http-user-agent=UA/1", ":start-time=90.0", ":network-caching=1500"), vod)
        val clip = vlcOptions(PlaySource("https://x/v.mp4", startMs = 10_000, endMs = 40_000))
        assertTrue(":stop-time=40.0" in clip && ":start-time=10.0" in clip)
    }
}
