package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.player.LoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tela do Multi: a grade, o som numa live só, destaque, remover, adicionar pelo campo, e os players param ao sair. */
class MultiScreenTest {
    private fun live(platform: Platform, id: String) =
        Media(platform, id, id, Channel(platform, id, id.uppercase(), url = "https://x/$id"), isLive = true, url = "https://x/$id")

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_grade_mostra_as_lives_e_os_botoes_fazem_o_que_dizem() = runComposeUiTest {
        val (app, _) = newTestApp()
        app.ui.multiChat = false // o chat de verdade falaria com a rede
        val a = live(Platform.Twitch, "a"); val b = live(Platform.Kick, "b")
        app.multi.add(a); app.multi.add(b)
        app.nav.goRoot(Route.Multi)
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { ApexApp() } } }
        waitForIdle()
        onNodeWithTag("tile-${a.key}").assertExists()
        onNodeWithTag("tile-${b.key}").assertExists()
        waitUntil(timeoutMillis = 5_000) { app.multi.tiles.value.all { it.load.value is LoadState.Ready } }
        assertTrue((app.multi.tiles.value[0].player as FakePlayer2).played.isNotEmpty(), "abrir a tela toca as lives")
        // A primeira live entra com som: o botão dela diz "Silenciar"; o da outra, "Som nesta live".
        onNodeWithTag("tile-audio-${a.key}", useUnmergedTree = true).onChildren().filterToOne(hasContentDescription("Silenciar")).assertExists()
        onNodeWithTag("tile-audio-${b.key}", useUnmergedTree = true).onChildren().filterToOne(hasContentDescription("Som nesta live")).assertExists()

        onNodeWithTag("tile-live-${a.key}").assertExists("cada live tem o botão AO VIVO / Voltar ao vivo")
        onNodeWithTag("tile-audio-${b.key}").performClick()
        waitForIdle()
        assertEquals(setOf(a.key, b.key), app.multi.audio.value, "o botão liga o som desta live sem tirar das outras")
        onNodeWithTag("tile-focus-${b.key}").performClick()
        waitForIdle()
        assertEquals(b.key, app.multi.focus.value)
        onNodeWithTag("tile-${a.key}").assertExists("em destaque, as outras continuam na coluna ao lado")
        onNodeWithTag("tile-remove-${a.key}").performClick()
        waitForIdle()
        onNodeWithTag("tile-${a.key}").assertDoesNotExist()
        assertEquals(listOf(b.key), app.multi.medias.map { it.key })
        assertEquals(setOf(b.key), app.multi.audio.value)

        // Adicionar pelo campo: nome de canal da Kick.
        onNodeWithTag("multi-platform-Kick").performClick()
        onNodeWithTag("multi-add-field").performTextInput("brabox")
        onNodeWithTag("multi-add").performClick()
        waitForIdle()
        assertEquals(listOf("Kick:b", "Kick:brabox"), app.multi.medias.map { it.key })
        onNodeWithTag("tile-Kick:brabox").assertExists()

        // O chat: ligar pelo botão da live abre a coluna com a aba dela.
        onNodeWithTag("tile-chat-Kick:brabox").performClick()
        waitForIdle()
        assertTrue(app.ui.multiChat)
        assertEquals("Kick:brabox", app.multi.chat.value)
        onNodeWithTag("multi-chat-tab-Kick:brabox").assertExists()
        onNodeWithTag("multi-chat-toggle").performClick()
        waitForIdle()
        assertTrue(!app.ui.multiChat)

        // Sair da tela para os players; a lista continua.
        app.nav.goRoot(Route.Home)
        waitForIdle()
        waitUntil(timeoutMillis = 3_000) { app.multi.tiles.value.all { (it.player as FakePlayer2).stops >= 1 } }
        assertEquals(2, app.data.multiStreams.value.size)
    }
}
