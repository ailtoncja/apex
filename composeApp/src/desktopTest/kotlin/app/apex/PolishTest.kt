package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Quality
import app.apex.nav.LibraryTab
import app.apex.nav.Route
import app.apex.player.fitVideo
import app.apex.player.pickDefaultQuality
import app.apex.player.trueFrameSize
import app.apex.theme.ApexColors
import app.apex.theme.Themes
import app.apex.theme.readableOn
import app.apex.ui.library.LibraryScreen
import app.apex.ui.settings.ThemeSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Esc na pesquisa, qualidade que não cai no "só áudio", quadro sem faixas pretas, tema, e a biblioteca sem barra de abas. */
class PolishTest {
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun esc(): KeyEvent = KeyEvent(Key.Escape, KeyEventType.KeyDown)

    // ------------------------------------------------------------------ Esc

    @Test
    fun esc_tira_o_cursor_da_busca_e_depois_sai_da_pagina_de_resultados() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Library(LibraryTab.Liked))
        app.nav.push(Route.Search("rally"))
        app.ui.typing = true

        assertTrue(handleShortcut(app, esc()), "1º Esc: tira o cursor do campo")
        assertFalse(app.ui.typing)
        assertTrue(app.nav.current is Route.Search, "ainda na pesquisa")

        assertTrue(handleShortcut(app, esc()), "2º Esc: sai da pesquisa")
        assertEquals(Route.Library(LibraryTab.Liked), app.nav.current, "volta para onde estava")
    }

    @Test
    fun esc_na_pesquisa_que_e_a_unica_pagina_vai_para_o_inicio() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Search("rally"))
        assertTrue(handleShortcut(app, esc()))
        assertEquals(Route.Home, app.nav.current)
    }

    @Test
    fun esc_em_outras_paginas_nao_faz_nada_e_na_tela_cheia_do_player_so_sai_dela() {
        val (app, _) = newTestApp()
        app.nav.goRoot(Route.Settings)
        assertFalse(handleShortcut(app, esc()), "Esc solto numa página comum não é do app")
        assertEquals(Route.Settings, app.nav.current)

        app.ui.playerFullscreen = true
        app.ui.typing = true
        assertTrue(handleShortcut(app, esc()))
        assertFalse(app.ui.playerFullscreen, "a tela cheia do player sai primeiro")
        assertTrue(app.ui.typing, "e o cursor continua onde estava")
    }

    // ------------------------------------------------------------------ qualidade padrão (nunca só áudio)

    private fun q(label: String, height: Int, audioOnly: Boolean = false, fps: Int? = null) = Quality(label, height, "https://x/$label", fps = fps, audioOnly = audioOnly)

    @Test
    fun a_qualidade_padrao_nunca_cai_no_somente_audio() {
        val hls = listOf(q("Automático", 0), q("1080p60", 1080, fps = 60), q("720p60", 720, fps = 60), q("480p", 480), q("Somente áudio", 0, audioOnly = true))
        assertEquals("1080p60", pickDefaultQuality(hls, 0).label, "automática = até 1080p")
        assertEquals("720p60", pickDefaultQuality(hls, 720).label)
        assertEquals("480p", pickDefaultQuality(hls, 480).label)
        

        // todas passam do limite: a menor imagem, não o áudio que vem por último
        val big = listOf(q("1440p", 1440), q("2160p", 2160), q("Somente áudio", 0, audioOnly = true))
        assertEquals("1440p", pickDefaultQuality(big, 720).label)

        // as variantes não dizem a altura (sem RESOLUTION na playlist): vale a primeira com imagem
        val unknown = listOf(q("Automático", 0), q("Padrão", 0), q("Somente áudio", 0, audioOnly = true))
        assertEquals("Automático", pickDefaultQuality(unknown, 0).label)

        // só há áudio: abre o áudio
        assertEquals("Somente áudio", pickDefaultQuality(listOf(q("Somente áudio", 0, audioOnly = true)), 0).label)
    }

    // ------------------------------------------------------------------ quadro sem faixas pretas

    @Test
    fun o_tamanho_do_quadro_e_o_do_video_e_nao_o_que_o_vlc_oferece() {
        // o VLC oferece 1088 e depois 1090 para um vídeo 1920x1080
        assertEquals(1920 to 1080, trueFrameSize(1920, 1090, 1920, 1080, 0), "pela faixa de vídeo")
        assertEquals(1920 to 1080, trueFrameSize(1920, 1088, 0, 0, 1080), "pela qualidade escolhida, quando a faixa ainda não existe")
        assertEquals(1280 to 720, trueFrameSize(1280, 738, 0, 0, 720))
        assertEquals(640 to 360, trueFrameSize(640, 386, 640, 360, 360))
        // sem nenhuma pista: fica o que o VLC ofereceu
        assertEquals(1920 to 1090, trueFrameSize(1920, 1090, 0, 0, 0))
        // pistas de outro vídeo (muito diferentes do oferecido) são ignoradas
        assertEquals(1920 to 1088, trueFrameSize(1920, 1088, 1280, 720, 720))
    }

    @Test
    fun o_video_ocupa_a_tela_toda_sem_faixa_preta_quando_a_proporcao_bate() {
        val full = fitVideo(1920f, 1080f, 1920, 1080)
        assertEquals(listOf(0, 0, 1920, 1080), listOf(full.x, full.y, full.width, full.height))
        // o quadro esticado de 1090 linhas deixava 9 px pretos de cada lado (o defeito de antes)
        val old = fitVideo(1920f, 1080f, 1920, 1090)
        assertEquals(9, old.x)
        // 4:3 numa tela 16:9: faixas dos lados, como em qualquer player
        val tv = fitVideo(1920f, 1080f, 640, 480)
        assertEquals(listOf(240, 0, 1440, 1080), listOf(tv.x, tv.y, tv.width, tv.height))
        // pixel não quadrado (SAR 16:15): 720x576 vira 768x576 = 4:3
        val sar = fitVideo(1920f, 1080f, 720, 576, pixelAspect = 16f / 15f)
        assertEquals(1440, sar.width)
        assertEquals(1080, sar.height)
    }

    // ------------------------------------------------------------------ tema

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clicar_num_cartao_de_tema_guarda_a_escolha_nos_ajustes() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1000.dp, 300.dp)) { ThemeSection() }
            }
        }
        assertEquals("dark", container.data.settings.value.theme)
        for (theme in listOf(Themes.Light, Themes.SubaruBlue, Themes.SubaruBlack, Themes.Dark)) {
            onNodeWithTag("theme-${theme.id}").performClick()
            waitForIdle()
            assertEquals(theme.id, container.data.settings.value.theme)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun o_app_aplica_o_tema_dos_ajustes_nas_cores_na_hora() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.nav.goRoot(Route.Settings)
        setContent { CompositionLocalProvider(LocalApp provides container) { ApexApp() } }
        try {
            waitForIdle()
            assertEquals(Themes.Dark, ApexColors.palette, "o padrão é o escuro")
            for (theme in listOf(Themes.Light, Themes.SubaruBlue, Themes.SubaruBlack)) {
                container.data.updateSettings { it.copy(theme = theme.id) }
                waitForIdle()
                assertEquals(theme, ApexColors.palette)
                assertEquals(theme.accent, ApexColors.Accent)
                assertEquals(theme.background, ApexColors.Background)
            }
        } finally {
            ApexColors.palette = Themes.Dark
        }
    }

    @Test
    fun os_temas_existem_sao_diferentes_e_o_id_desconhecido_cai_no_escuro() {
        assertEquals(listOf("dark", "light", "subaru-azul", "subaru-preto"), Themes.all.map { it.id })
        assertEquals(Themes.all.size, Themes.all.map { it.background }.toSet().size, "cada tema tem seu fundo")
        assertEquals(Themes.Dark, Themes.byId("tema-do-futuro"))
        assertEquals(Themes.Dark, Themes.byId(null))
        assertTrue(Themes.Light.isLight && !Themes.Dark.isLight && !Themes.SubaruBlue.isLight && !Themes.SubaruBlack.isLight)
        // o texto tem de contrastar com o fundo e o botão de destaque com o texto dele
        for (p in Themes.all) {
            assertTrue(luminance(p.onSurface) - luminance(p.background) > 0.45 || luminance(p.background) - luminance(p.onSurface) > 0.45, "texto x fundo em ${p.label}")
            assertTrue(kotlin.math.abs(luminance(p.onAccent) - luminance(p.accent)) > 0.3, "texto x destaque em ${p.label}")
        }
        // os dois Subaru têm destaque dourado (vermelho baixo-médio, verde alto, azul baixo)
        for (p in listOf(Themes.SubaruBlue, Themes.SubaruBlack)) {
            assertTrue(p.accent.red > 0.85f && p.accent.green in 0.65f..0.85f && p.accent.blue < 0.25f, "dourado em ${p.label}")
        }
        assertNotEquals(Themes.SubaruBlue.background, Themes.SubaruBlack.background)
    }

    private fun luminance(c: androidx.compose.ui.graphics.Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue


    // ------------------------------------------------------------------ cores de fora (nome no chat) e páginas no canto

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun cores_de_nome_do_chat_ficam_legiveis_em_qualquer_tema() {
        val white = androidx.compose.ui.graphics.Color.White
        val darkBlue = androidx.compose.ui.graphics.Color(0xFF0000FF)
        val pink = androidx.compose.ui.graphics.Color(0xFFFF69B4)
        for (p in Themes.all) {
            for (c in listOf(white, darkBlue, pink, androidx.compose.ui.graphics.Color.Black)) {
                val out = readableOn(c, p.surface)
                assertTrue(contrast(out, p.surface) >= 3.4f, "${p.label}: $c virou $out, contraste ${contrast(out, p.surface)}")
            }
        }
        // uma cor que já lê bem não muda
        assertEquals(pink, readableOn(pink, Themes.Dark.surface))
        // no tema claro o branco escurece (fica cinza-escuro, não some)
        assertTrue(readableOn(white, Themes.Light.surface).luminance() < 0.3f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun biblioteca_e_ajustes_comecam_no_canto_esquerdo_da_pagina_mesmo_em_janela_larga() = runComposeUiTest {
        val (container, _) = newTestApp()
        var page by androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
        setContent {
            CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(1900.dp, 900.dp)) { page() } }
        }
        page = { LibraryScreen(LibraryTab.History) }
        waitForIdle()
        val libraryLeft = onAllNodesWithText("Histórico").onFirst().getBoundsInRoot().left
        assertTrue(libraryLeft < 60.dp, "o título da biblioteca fica no canto (estava em $libraryLeft)")
        page = { app.apex.ui.settings.SettingsScreen() }
        waitForIdle()
        val settingsLeft = onAllNodesWithText("Ajustes").onFirst().getBoundsInRoot().left
        assertTrue(settingsLeft < 60.dp, "o título dos ajustes fica no canto (estava em $settingsLeft)")
    }

    // ------------------------------------------------------------------ biblioteca sem barra de abas

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_pagina_da_biblioteca_nao_tem_mais_a_barra_de_abas() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { LibraryScreen(LibraryTab.History) } }
        waitForIdle()
        // só o título da lista: os nomes das outras abas não aparecem como botões dentro da página
        assertEquals(0, onAllNodesWithText("Assistir depois").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Curtidos").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Clipes").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText("Histórico").fetchSemanticsNodes().isNotEmpty(), "o título da página continua")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_barra_lateral_recolhida_leva_a_cada_pagina_da_biblioteca() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.data.updateSettings { it.copy(sidebarCollapsed = true) }
        container.nav.goRoot(Route.Settings)
        setContent { CompositionLocalProvider(LocalApp provides container) { app.apex.ui.shell.Sidebar(expanded = false) } }
        waitForIdle()
        for (tab in LibraryTab.entries) {
            onAllNodesWithContentDescription(tab.label).onFirst().assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(Route.Library(tab), container.nav.current, "ícone de ${tab.label}")
        }
    }
}
