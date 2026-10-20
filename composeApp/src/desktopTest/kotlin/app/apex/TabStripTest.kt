package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Media
import app.apex.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

/** A faixa de abas: aparece com mais de uma aba (ou uma aba aberta sem nada tocando), clicar numa aba abre o vídeo dela, o ✕ fecha. */
class TabStripTest {
    private fun video(id: String) = Media(Platform.YouTube, id, "Vídeo $id", url = "https://www.youtube.com/watch?v=$id")

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_faixa_aparece_quando_faz_sentido_e_clicar_troca_de_video() = runComposeUiTest {
        val (app, _) = newTestApp()
        app.ui.multiChat = false
        val a = video("a"); val b = video("b")
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { ApexApp() } } }
        waitForIdle()
        onNodeWithTag("tab-strip").assertDoesNotExist()

        app.openInNewTab(b) // nada tocando: a aba aberta aparece, mesmo sozinha
        waitForIdle()
        onNodeWithTag("tab-strip").assertExists("uma aba aberta sem nada tocando aparece na faixa")

        app.openMedia(a) // entra como segunda aba e toca
        waitForIdle()
        assertEquals(listOf("YouTube:b", "YouTube:a"), app.tabs.medias.map { it.key })
        onNodeWithTag("tab-YouTube:b").performClick()
        waitForIdle()
        assertEquals("YouTube:b", app.session.current.value?.key, "clicar na aba abre o vídeo dela")

        onNodeWithTag("tab-close-YouTube:a").performClick()
        waitForIdle()
        assertEquals(listOf("YouTube:b"), app.tabs.medias.map { it.key })
        assertEquals("YouTube:b", app.session.current.value?.key, "fechar outra aba não mexe no que toca")
        onNodeWithTag("tab-strip").assertDoesNotExist()
    }
}
