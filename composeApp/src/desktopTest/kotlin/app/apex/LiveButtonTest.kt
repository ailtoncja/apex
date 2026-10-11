package app.apex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.ui.watch.LiveEdgeButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O botão "AO VIVO / Voltar ao vivo" do player: tira o atraso que a live acumulou (pausa, travadas). */
class LiveButtonTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun em_dia_diz_ao_vivo_e_atrasado_oferece_a_volta_com_os_segundos() = runComposeUiTest {
        var behind by mutableStateOf(0L)
        var playing by mutableStateOf(true)
        var clicks = 0
        setContent { LiveEdgeButton(behind, playing) { clicks++ } }

        onNodeWithText("AO VIVO").assertIsDisplayed()
        behind = 3_000
        waitForIdle()
        onNodeWithText("AO VIVO").assertIsDisplayed()

        behind = 15_400
        waitForIdle()
        onNodeWithText("Voltar ao vivo • 15 s atrás").assertIsDisplayed()

        behind = 0
        playing = false
        waitForIdle()
        onNodeWithText("Voltar ao vivo").assertIsDisplayed()

        // Pausado, o botão conta o tempo parado (o player só conta quando está tocando).
        mainClock.advanceTimeBy(7_000)
        waitForIdle()
        onNodeWithText("Voltar ao vivo • 7 s atrás").assertIsDisplayed()

        onNodeWithText("Voltar ao vivo • 7 s atrás").performClick()
        assertEquals(1, clicks)

        // Voltou a tocar: o player já inclui a pausa na conta dele, o botão para de somar por conta própria.
        playing = true
        behind = 7_000
        waitForIdle()
        onNodeWithText("Voltar ao vivo • 7 s atrás").assertIsDisplayed()
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        onNodeWithText("Voltar ao vivo • 7 s atrás").assertIsDisplayed()
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun end_numa_live_volta_ao_ao_vivo_e_num_video_pula_para_o_fim() {
        val (app, _) = newTestApp()
        val player = app.player as FakePlayer2
        val live = Media(Platform.YouTube, "live1", "Uma live", Channel(Platform.YouTube, "UC1", "Canal"), isLive = true, url = "https://www.youtube.com/watch?v=live1")
        val video = Media(Platform.YouTube, "vid1", "Um vídeo", Channel(Platform.YouTube, "UC1", "Canal"), url = "https://www.youtube.com/watch?v=vid1")
        app.nav.goRoot(Route.Home)
        app.nav.push(Route.Watch)

        app.session.open(live)
        assertTrue(handleShortcut(app, KeyEvent(Key.MoveEnd, KeyEventType.KeyDown)))
        assertEquals(1, player.jumps)
        assertEquals("Voltando ao ao vivo…", app.ui.toast)
        assertTrue(player.seeks.isEmpty(), "numa live End não pula no tempo")

        app.session.open(video)
        player.state.value = player.state.value.copy(durationMs = 600_000)
        assertTrue(handleShortcut(app, KeyEvent(Key.MoveEnd, KeyEventType.KeyDown)))
        assertEquals(1, player.jumps, "num vídeo gravado não mexe no ao vivo")
        assertEquals(listOf(599_000L), player.seeks)
    }
}
