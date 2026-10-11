package app.apex

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.LibraryTab
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.player.PLAYBACK_SPEEDS
import app.apex.player.stepSpeed
import app.apex.ui.shell.SHORTCUT_GROUPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Os atalhos de teclado do app: pesquisar, voltar e avançar, trocar de tela, atualizar, velocidade e a lista de atalhos. */
class ShortcutsTest {
    @OptIn(InternalComposeUiApi::class)
    private fun press(key: Key, char: Char? = null, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): KeyEvent =
        KeyEvent(key, KeyEventType.KeyDown, codePoint = char?.code ?: 0, isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift)

    private val live = Media(Platform.YouTube, "live1", "Uma live", Channel(Platform.YouTube, "UC1", "Canal"), isLive = true, url = "https://www.youtube.com/watch?v=live1")
    private val video = Media(Platform.YouTube, "vid1", "Um vídeo", Channel(Platform.YouTube, "UC1", "Canal"), url = "https://www.youtube.com/watch?v=vid1")

    // ------------------------------------------------------------------ pesquisar

    @Test
    fun barra_e_ctrl_k_levam_o_cursor_para_a_pesquisa() {
        val (app, _) = newTestApp()
        assertEquals(0, app.ui.focusSearchTick)
        assertTrue(handleShortcut(app, press(Key.Slash, '/')))
        assertEquals(1, app.ui.focusSearchTick)
        assertTrue(handleShortcut(app, press(Key.K, ctrl = true)))
        assertTrue(handleShortcut(app, press(Key.L, ctrl = true)))
        assertEquals(3, app.ui.focusSearchTick)
    }

    @Test
    fun barra_digitada_num_campo_de_texto_fica_no_campo_mas_ctrl_k_ainda_vale() {
        val (app, _) = newTestApp()
        app.ui.typing = true
        assertFalse(handleShortcut(app, press(Key.Slash, '/')), "a barra é texto quando a pessoa está digitando")
        assertEquals(0, app.ui.focusSearchTick)
        assertTrue(handleShortcut(app, press(Key.K, ctrl = true)))
        assertEquals(1, app.ui.focusSearchTick)
    }

    @Test
    fun altgr_nao_dispara_atalho() {
        // No Windows o AltGr (usado no teclado brasileiro) chega como Ctrl + Alt.
        val (app, _) = newTestApp()
        assertFalse(handleShortcut(app, press(Key.K, ctrl = true, alt = true)))
        assertFalse(handleShortcut(app, press(Key.DirectionLeft, ctrl = true, alt = true)))
        assertEquals(0, app.ui.focusSearchTick)
        // Mas a "/" que o AltGr digita (AltGr + Q no teclado brasileiro) é uma "/" como outra qualquer.
        assertTrue(handleShortcut(app, press(Key.Q, '/', ctrl = true, alt = true)))
        assertEquals(1, app.ui.focusSearchTick)
    }

    // ------------------------------------------------------------------ navegar

    @Test
    fun alt_seta_volta_e_avanca_e_uma_navegacao_nova_esquece_o_avancar() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Home)
        app.nav.push(Route.Live(LiveFilter.All))
        app.nav.push(Route.Subscriptions)

        assertTrue(handleShortcut(app, press(Key.DirectionLeft, alt = true)))
        assertEquals(Route.Live(LiveFilter.All), app.nav.current)
        assertTrue(handleShortcut(app, press(Key.DirectionLeft, alt = true)))
        assertEquals(Route.Home, app.nav.current)
        assertTrue(handleShortcut(app, press(Key.DirectionLeft, alt = true)), "no começo continua sendo um atalho, só que não faz nada")
        assertEquals(Route.Home, app.nav.current)

        assertTrue(handleShortcut(app, press(Key.DirectionRight, alt = true)))
        assertEquals(Route.Live(LiveFilter.All), app.nav.current)
        assertTrue(app.nav.canGoForward)

        app.nav.push(Route.Settings)
        assertFalse(app.nav.canGoForward, "ir para outro lugar esquece o que dava para avançar")
    }

    @Test
    fun a_pagina_do_video_nao_entra_no_avancar() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Home)
        app.nav.push(Route.Watch)
        app.nav.back()
        assertFalse(app.nav.canGoForward)
    }

    @Test
    fun ctrl_numero_troca_de_tela_e_alt_home_volta_ao_inicio() {
        val (app, _) = newTestApp()
        val expected = listOf(
            Key.One to Route.Home, Key.Two to Route.Live(LiveFilter.All), Key.Three to Route.Subscriptions,
            Key.Four to Route.Library(LibraryTab.History), Key.Five to Route.Settings,
        )
        for ((key, route) in expected) {
            assertTrue(handleShortcut(app, press(key, ctrl = true)))
            assertEquals(route, app.nav.current)
        }
        assertTrue(handleShortcut(app, press(Key.Comma, ctrl = true)))
        assertEquals(Route.Settings, app.nav.current)
        assertTrue(handleShortcut(app, press(Key.MoveHome, alt = true)))
        assertEquals(Route.Home, app.nav.current)
    }

    @Test
    fun f5_e_ctrl_r_atualizam_a_tela() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Home)
        assertTrue(handleShortcut(app, press(Key.F5)))
        assertEquals("Atualizando…", app.ui.toast)
        app.ui.toast = null
        assertTrue(handleShortcut(app, press(Key.R, ctrl = true)))
        assertEquals("Atualizando…", app.ui.toast)
    }

    @Test
    fun ctrl_b_esconde_e_mostra_o_menu_lateral() {
        val (app, _) = newTestApp()
        val before = app.data.settings.value.sidebarCollapsed
        assertTrue(handleShortcut(app, press(Key.B, ctrl = true)))
        assertEquals(!before, app.data.settings.value.sidebarCollapsed)
        assertTrue(handleShortcut(app, press(Key.B, ctrl = true)))
        assertEquals(before, app.data.settings.value.sidebarCollapsed)
    }

    // ------------------------------------------------------------------ ajuda e Esc

    @Test
    fun interrogacao_e_f1_abrem_a_lista_de_atalhos_e_esc_fecha() {
        val (app, _) = newTestApp()
        assertTrue(handleShortcut(app, press(Key.Slash, '?', shift = true)))
        assertTrue(app.ui.shortcutsOpen)
        assertTrue(handleShortcut(app, press(Key.Escape)))
        assertFalse(app.ui.shortcutsOpen)
        assertTrue(handleShortcut(app, press(Key.F1)))
        assertTrue(app.ui.shortcutsOpen)
        assertTrue(handleShortcut(app, press(Key.F1)))
        assertFalse(app.ui.shortcutsOpen)
    }

    @Test
    fun esc_sai_do_modo_cinema_na_pagina_do_video() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Home)
        app.nav.push(Route.Watch)
        app.ui.theater = true
        assertTrue(handleShortcut(app, press(Key.Escape)))
        assertFalse(app.ui.theater)
        assertFalse(handleShortcut(app, press(Key.Escape)), "sem nada para sair, o Esc não é do app")
    }

    // ------------------------------------------------------------------ com algo tocando

    @Test
    fun menor_e_maior_mudam_a_velocidade_so_em_video_gravado() {
        val (app, _) = newTestApp()
        val player = app.player as FakePlayer2
        app.session.open(video)
        assertEquals(1f, player.state.value.rate)
        assertTrue(handleShortcut(app, press(Key.Period, '>', shift = true)))
        assertEquals(1.25f, player.state.value.rate)
        assertEquals("Velocidade: 1,25x", app.ui.toast)
        assertTrue(handleShortcut(app, press(Key.Comma, '<', shift = true)))
        assertTrue(handleShortcut(app, press(Key.Comma, '<', shift = true)))
        assertEquals(0.75f, player.state.value.rate)

        app.session.open(live)
        val before = player.state.value.rate
        assertFalse(handleShortcut(app, press(Key.Period, '>', shift = true)), "live não tem velocidade")
        assertEquals(before, player.state.value.rate)
    }

    @Test
    fun home_e_end_pulam_para_o_comeco_e_o_fim_na_pagina_do_video() {
        val (app, _) = newTestApp()
        val player = app.player as FakePlayer2
        app.session.open(video)
        player.state.value = player.state.value.copy(durationMs = 600_000)
        app.nav.goRoot(Route.Home)
        assertFalse(handleShortcut(app, press(Key.MoveEnd)), "fora da página do vídeo, Home e End não são do player")

        app.nav.push(Route.Watch)
        assertTrue(handleShortcut(app, press(Key.MoveEnd)))
        assertTrue(handleShortcut(app, press(Key.MoveHome)))
        assertEquals(listOf(599_000L, 0L), player.seeks)
    }

    @Test
    fun enter_na_live_pede_o_cursor_no_chat() {
        val (app, _) = newTestApp()
        app.session.open(live)
        app.nav.goRoot(Route.Home)
        app.nav.push(Route.Watch)
        assertTrue(handleShortcut(app, press(Key.Enter)))
        assertEquals(1, app.ui.focusChatTick)

        app.session.open(video)
        assertFalse(handleShortcut(app, press(Key.Enter)), "vídeo gravado não tem chat para escrever")
    }

    // ------------------------------------------------------------------ peças soltas

    @Test
    fun a_proxima_velocidade_anda_pela_lista_e_para_nas_pontas() {
        assertEquals(1.25f, stepSpeed(1f, faster = true))
        assertEquals(0.75f, stepSpeed(1f, faster = false))
        assertEquals(2f, stepSpeed(2f, faster = true))
        assertEquals(0.25f, stepSpeed(0.25f, faster = false))
        assertEquals(1.5f, stepSpeed(1.3f, faster = true), "velocidade fora da lista: vai para a próxima de cima")
        assertEquals(PLAYBACK_SPEEDS.sorted(), PLAYBACK_SPEEDS)
    }

    @Test
    fun a_lista_de_atalhos_nao_tem_tecla_repetida_dentro_do_mesmo_grupo() {
        for (group in SHORTCUT_GROUPS) {
            val keys = group.items.flatMap { it.keys }
            assertEquals(keys.distinct(), keys, "teclas repetidas em \"${group.title}\"")
        }
    }
}
