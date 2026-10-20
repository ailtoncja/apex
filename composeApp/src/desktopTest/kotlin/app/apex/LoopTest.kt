package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.source.Extractor
import app.apex.player.LoadState
import app.apex.ui.watch.WatchScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Repetir o vídeo: com o botão (ou R) ligado, o vídeo recomeça quando acaba em vez de ir para o próximo; abrir outro vídeo desliga. */
class LoopTest {
    private fun video(id: String) = Media(Platform.YouTube, id, "Vídeo $id", Channel(Platform.YouTube, "UC1", "Canal"), url = "https://www.youtube.com/watch?v=$id")
    private val live = Media(Platform.Twitch, "gaules", "Live", Channel(Platform.Twitch, "gaules", "Gaules"), isLive = true, url = "https://www.twitch.tv/gaules")

    @OptIn(InternalComposeUiApi::class)
    private fun press(key: Key, shift: Boolean = false) = KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift)

    private fun waitFor(timeoutMs: Long = 5_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "demorou demais: $what" }; Thread.sleep(10) }
    }

    /** O fim do vídeo, como o player avisa: `ended` passa a verdadeiro (e volta a falso quando ele abre de novo). */
    private fun FakePlayer2.finish() {
        state.value = state.value.copy(ended = false)
        Thread.sleep(50)
        state.value = state.value.copy(ended = true, playing = false)
    }

    @Test
    fun ligado_recomeca_o_mesmo_video_e_desligado_vai_para_o_proximo() {
        val (app, _) = newTestApp()
        val player = app.player as FakePlayer2
        val a = video("a"); val b = video("b")
        app.session.open(a, upNext = listOf(b))
        // O extrator de mentira não resolve (vira erro): para o repetir não importa, só o "acabou" do player.
        waitFor(what = "abrir") { app.session.load.value !is LoadState.Loading }

        app.session.setLoop(true)
        player.finish()
        waitFor(what = "recomeçar") { player.resumes == 1 }
        assertEquals("YouTube:a", app.session.current.value?.key, "com o repetir ligado não vai para o próximo")
        player.finish()
        waitFor(what = "recomeçar de novo") { player.resumes == 2 }
        assertEquals("YouTube:a", app.session.current.value?.key)

        app.session.setLoop(false)
        player.finish()
        waitFor(what = "ir para o próximo") { app.session.current.value?.key == "YouTube:b" }
        assertEquals(2, player.resumes)
    }

    @Test
    fun abrir_outro_video_desliga_e_reabrir_o_mesmo_mantem() {
        val (app, _) = newTestApp()
        val a = video("a")
        app.session.open(a)
        app.session.setLoop(true)
        app.session.open(a) // trocar a qualidade, voltar à mesma aba…: o mesmo vídeo
        assertTrue(app.session.loop.value)
        app.session.open(video("b"))
        assertFalse(app.session.loop.value, "como no YouTube, o repetir é do vídeo")
    }

    @Test
    fun a_tecla_r_liga_e_desliga_e_na_live_nao_vale() {
        val (app, _) = newTestApp()
        app.session.open(video("a"))
        assertTrue(handleShortcut(app, press(Key.R)))
        assertTrue(app.session.loop.value)
        assertEquals("Repetir: ligado", app.ui.toast)
        assertTrue(handleShortcut(app, press(Key.R)))
        assertFalse(app.session.loop.value)
        assertEquals("Repetir: desligado", app.ui.toast)

        app.session.open(live)
        assertFalse(handleShortcut(app, press(Key.R)), "live não acaba: não há o que repetir")
        assertFalse(app.session.loop.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun o_botao_do_player_liga_e_desliga() = runComposeUiTest {
        // Um vídeo que abre de verdade: com erro o player não mostra os controles (só diriam 0:00 / 0:00).
        val (app, _) = newTestApp(extractor = object : Extractor by FakeExtractor2() {
            override suspend fun resolve(media: Media) = Resolved(
                media, null, null, null, null, emptyList(), emptyList(),
                listOf(Quality("720p", 720, "https://v/${media.id}.mp4")), null, isLive = false,
            )
        })
        app.session.open(video("a"))
        waitUntil(timeoutMillis = 5_000) { app.session.load.value is LoadState.Ready }
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { WatchScreen(fullscreen = false) } } }
        waitForIdle()
        onNodeWithContentDescription("Repetir o vídeo (R)").performClick()
        waitForIdle()
        assertTrue(app.session.loop.value)
        onNodeWithContentDescription("Repetir: ligado (R)").performClick()
        waitForIdle()
        assertFalse(app.session.loop.value)
    }
}
