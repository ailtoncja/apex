package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.ui.components.ChannelRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Canal ao vivo: a foto e a etiqueta "AO VIVO" entram direto na live; o nome abre a página do canal. */
class LiveClickTest {
    private val gaules = Channel(Platform.Twitch, "gaules", "Gaules")

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun foto_do_canal_ao_vivo_entra_direto_na_live() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { ChannelRow(gaules, live = true) } }
        onAllNodesWithText("G").onFirst().performClick() // sem foto, o avatar mostra a inicial do canal
        waitForIdle()
        assertTrue(container.nav.current is Route.Watch, "foi para a tela do vídeo, não para a página do canal: ${container.nav.current}")
        val live = container.session.current.value
        assertEquals("gaules", live?.id)
        assertTrue(live?.isLive == true)
        assertEquals(Platform.Twitch, live?.platform)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun etiqueta_ao_vivo_tambem_entra_na_live() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { ChannelRow(Channel(Platform.Kick, "yoda", "YoDa"), live = true) } }
        onAllNodesWithText("AO VIVO").onFirst().performClick()
        waitForIdle()
        assertTrue(container.nav.current is Route.Watch)
        assertEquals("yoda", container.session.current.value?.id)
        assertEquals(Platform.Kick, container.session.current.value?.platform)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nome_do_canal_continua_abrindo_a_pagina_do_canal() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { ChannelRow(gaules, live = true) } }
        onAllNodesWithText("Gaules").onFirst().performClick()
        waitForIdle()
        val route = container.nav.current
        assertTrue(route is Route.ChannelPage && route.channel.id == "gaules", "abriu $route")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun canal_que_nao_esta_ao_vivo_nao_tem_atalho_na_foto() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { ChannelRow(gaules, live = false) } }
        onAllNodesWithText("G").onFirst().performClick() // a foto faz parte da linha: abre o canal
        waitForIdle()
        assertTrue(container.nav.current is Route.ChannelPage)
    }

    @Test
    fun live_ja_conhecida_e_usada_em_vez_de_adivinhar() {
        val (container, _) = newTestApp()
        val known = Media(Platform.YouTube, "abc123", "Live do canal", Channel(Platform.YouTube, "UC1", "Canal"), isLive = true, url = "https://www.youtube.com/watch?v=abc123")
        container.openLive(Channel(Platform.YouTube, "UC1", "Canal"), known)
        assertTrue(container.nav.current is Route.Watch)
        assertEquals("abc123", container.session.current.value?.id, "o YouTube precisa do id do vídeo da live; usa o que já se conhece")
    }
}
