package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.ui.multi.matchChannels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** As sugestões do campo do Multi: os canais que a pessoa segue, filtrados enquanto digita, os ao vivo primeiro. */
class MultiSuggestionsTest {
    private val razah = Channel(Platform.Twitch, "razah", "RazaH", handle = "@razah", url = "https://www.twitch.tv/razah")
    private val brabox = Channel(Platform.Kick, "brabox", "Brabox", url = "https://kick.com/brabox")
    private val caze = Channel(Platform.YouTube, "UCZiYbVptd3PVPf4f6eR6UaQ", "CazéTV", handle = "@CazeTV", url = "https://www.youtube.com/@CazeTV")
    private val rafael = Channel(Platform.Twitch, "rafaelzinho", "Rafael Zinho", url = "https://www.twitch.tv/rafaelzinho")
    private val subs = listOf(rafael, caze, brabox, razah)

    @Test
    fun combina_por_nome_handle_ou_login_com_os_ao_vivo_na_frente() {
        assertEquals(listOf("Twitch:rafaelzinho", "Twitch:razah", "Kick:brabox"), matchChannels("ra", subs, emptySet()).map { it.key }, "quem começa com o texto vem antes de quem só contém (b-ra-box)")
        assertEquals(listOf("Twitch:razah", "Twitch:rafaelzinho", "Kick:brabox"), matchChannels("ra", subs, setOf("Twitch:razah")).map { it.key }, "quem está ao vivo vem primeiro")
        assertEquals(listOf("Twitch:razah"), matchChannels("raz", subs, emptySet()).map { it.key })
        assertEquals(listOf("YouTube:UCZiYbVptd3PVPf4f6eR6UaQ"), matchChannels("@caze", subs, emptySet()).map { it.key }, "pelo @handle, com ou sem o @")
        assertEquals(listOf("Kick:brabox"), matchChannels("BRAB", subs, emptySet()).map { it.key }, "sem diferença de maiúsculas")
        assertTrue(matchChannels("", subs, emptySet()).isEmpty(), "sem texto, sem sugestões")
        assertTrue(matchChannels("UCZiYb", subs, emptySet()).isEmpty(), "o id do YouTube não é nome para ninguém")
        assertEquals(8, matchChannels("c", (1..12).map { Channel(Platform.Twitch, "canal$it", "Canal $it") }, emptySet()).size, "até 8")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun digitar_mostra_os_canais_seguidos_e_clicar_ou_dar_enter_poe_no_multi() = runComposeUiTest {
        val (app, _) = newTestApp()
        app.ui.multiChat = false
        app.data.addSubscriptions(subs)
        app.nav.goRoot(Route.Multi)
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { ApexApp() } } }
        waitForIdle()
        onNodeWithTag("multi-suggestions").assertDoesNotExist()

        onNodeWithTag("multi-add-field").performTextInput("raz")
        waitForIdle()
        onNodeWithTag("multi-suggestion-Twitch:razah").assertExists("RazaH combina com 'raz'")
        onNodeWithTag("multi-suggestion-Twitch:rafaelzinho").assertDoesNotExist()
        onNodeWithTag("multi-suggestion-Kick:brabox").assertDoesNotExist()
        // Um clique fora (num chip de plataforma) fecha a lista; voltar ao campo abre de novo.
        onNodeWithTag("multi-platform-Twitch").performClick()
        waitForIdle()
        onNodeWithTag("multi-suggestions").assertDoesNotExist()
        onNodeWithTag("multi-add-field").performClick()
        waitForIdle()
        onNodeWithTag("multi-suggestion-Twitch:razah").assertExists()
        onNodeWithTag("multi-suggestion-Twitch:razah").performClick()
        waitForIdle()
        assertEquals(listOf("Twitch:razah"), app.multi.medias.map { it.key })
        assertEquals("RazaH", app.multi.medias.single().channel?.name, "entra com o nome do canal, não só o login")
        onNodeWithTag("tile-Twitch:razah").assertExists()

        // Enter pega a primeira sugestão (quem já está no Multi não aparece mais).
        onNodeWithTag("multi-add-field").performTextClearance()
        onNodeWithTag("multi-add-field").performTextInput("bra")
        waitForIdle()
        onNodeWithTag("multi-suggestion-Kick:brabox").assertExists()
        onNodeWithTag("multi-add-field").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Twitch:razah", "Kick:brabox"), app.multi.medias.map { it.key })

        onNodeWithTag("multi-add-field").performTextInput("ra")
        waitForIdle()
        onNodeWithTag("multi-suggestion-Twitch:razah").assertDoesNotExist()
        onNodeWithTag("multi-suggestion-Twitch:rafaelzinho").assertExists()
    }
}
