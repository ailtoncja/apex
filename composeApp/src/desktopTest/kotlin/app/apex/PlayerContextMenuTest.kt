package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.ui.watch.WatchScreen
import app.apex.ui.watch.linkAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O menu do botão direito no player: abre onde o cursor está, não pausa o vídeo, e as opções fazem o que dizem. */
class PlayerContextMenuTest {
    private val video = Media(Platform.YouTube, "vid1", "Um vídeo", Channel(Platform.YouTube, "UC1", "Canal"), url = "https://www.youtube.com/watch?v=vid1")
    private val live = Media(Platform.Twitch, "gaules", "Live", Channel(Platform.Twitch, "gaules", "Gaules"), isLive = true, url = "https://www.twitch.tv/gaules")

    @Test
    fun link_no_ponto_atual_do_jeito_de_cada_site() {
        assertEquals("https://www.youtube.com/watch?v=vid1&t=754s", linkAt(video, 754_900))
        // Clipe do YouTube: o vídeo de onde ele saiu, no ponto do clipe.
        val clip = Media(Platform.YouTube, "clip-abc", "Clipe", url = "https://www.youtube.com/watch?v=base1", clipStartMs = 60_000, clipEndMs = 90_000)
        assertEquals("https://www.youtube.com/watch?v=base1&t=70s", linkAt(clip, 10_000))
        val vod = Media(Platform.Twitch, "vod-123", "VOD", url = "https://www.twitch.tv/videos/123")
        assertEquals("https://www.twitch.tv/videos/123?t=1h2m3s", linkAt(vod, 3_723_000))
        assertNull(linkAt(live, 5_000), "live não tem ponto")
        assertNull(linkAt(Media(Platform.Kick, "vod-x", "VOD", url = "https://kick.com/x/videos/x"), 5_000), "a Kick não aceita o ponto no link")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun botao_direito_abre_o_menu_sem_pausar_e_as_opcoes_funcionam() = runComposeUiTest {
        val (app, system) = newTestApp()
        val player = app.player as FakePlayer2
        app.session.open(video)
        player.state.value = player.state.value.copy(hasMedia = true, positionMs = 754_000, durationMs = 1_200_000)
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { WatchScreen(fullscreen = false) } } }
        waitForIdle()

        onNodeWithTag("player").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("player-menu").assertExists("o botão direito abre o menu")
        assertEquals(0, player.toggles, "o botão direito não pausa o vídeo")

        onNodeWithTag("player-menu-loop").performClick()
        waitForIdle()
        assertTrue(app.session.loop.value)
        onNodeWithTag("player-menu").assertDoesNotExist()

        onNodeWithTag("player").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("player-menu-copy-here").performClick()
        waitForIdle()
        assertEquals("https://www.youtube.com/watch?v=vid1&t=754s", system.copied)
        assertEquals("Link copiado: começa em 12:34", app.ui.toast)

        onNodeWithTag("player").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("player-menu-browser").performClick()
        waitForIdle()
        assertEquals(1, player.pauses, "abrir no navegador pausa o player, para o som não sair em dobro")
        assertEquals(0, player.toggles)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun na_live_o_menu_tem_as_opcoes_de_live() = runComposeUiTest {
        val (app, system) = newTestApp()
        app.session.open(live)
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { WatchScreen(fullscreen = false) } } }
        waitForIdle()
        onNodeWithTag("player").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("player-menu-loop").assertDoesNotExist()
        onNodeWithText("Assistir no Multi").assertExists()
        onNodeWithTag("player-menu-copy-here").assertDoesNotExist()
        onNodeWithText("Copiar link da live").performClick()
        waitForIdle()
        assertEquals("https://www.twitch.tv/gaules", system.copied)
        assertFalse(app.session.loop.value)
    }
}
