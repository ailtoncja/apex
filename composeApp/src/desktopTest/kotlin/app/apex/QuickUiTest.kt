package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.player.LoadState
import app.apex.source.ChannelOfflineException
import app.apex.theme.ApexColors
import app.apex.theme.Themes
import app.apex.ui.components.VideoCard
import app.apex.ui.watch.WatchScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** As melhorias rápidas de interface: barra de título no tema, "…" do vídeo, canal offline, atalhos nos cartões, dourado no escolhido. */
class QuickUiTest {
    private val video = Media(Platform.YouTube, "vid1", "Um vídeo", Channel(Platform.YouTube, "UC1", "Canal"), url = "https://www.youtube.com/watch?v=vid1")
    private val zack = Channel(Platform.Twitch, "zackrawrr", "zackrawrr", "https://x/zack.png", url = "https://www.twitch.tv/zackrawrr")

    /** Um extrator que responde que a live não está no ar. */
    private class OfflineExtractor(private val channel: Channel) : app.apex.source.Extractor by FakeExtractor2() {
        override suspend fun resolve(media: Media): app.apex.model.Resolved = throw ChannelOfflineException(channel.name, channel)
    }

    @Test
    fun a_cor_da_barra_de_titulo_vai_no_formato_do_windows() {
        assertEquals(0x332211, TitleBarColors.colorRef(Color(0xFF112233)), "COLORREF é 0x00BBGGRR")
        assertEquals(0x080808, TitleBarColors.colorRef(Themes.SubaruBlack.background))
    }

    @Test
    fun o_escolhido_e_dourado_nos_temas_subaru_e_branco_no_escuro() {
        val before = ApexColors.palette
        try {
            ApexColors.palette = Themes.SubaruBlack
            assertEquals(Themes.SubaruBlack.accent, ApexColors.Selected)
            assertEquals(Themes.SubaruBlack.onAccent, ApexColors.OnSelected)
            ApexColors.palette = Themes.Dark
            assertEquals(Themes.Dark.onSurface, ApexColors.Selected)
            assertEquals(Themes.Dark.background, ApexColors.OnSelected)
        } finally {
            ApexColors.palette = before
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun o_botao_de_reticencias_do_video_tem_as_acoes_de_vez_em_quando() = runComposeUiTest {
        val (app, system) = newTestApp()
        app.session.open(video)
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { WatchScreen(fullscreen = false) } } }
        waitForIdle()
        onNodeWithTag("watch-more-copy").assertDoesNotExist()
        onNodeWithTag("watch-more").performClick()
        waitForIdle()
        onNodeWithTag("watch-more-playlist").assertExists()
        onNodeWithTag("watch-more-background").assertExists()
        onNodeWithTag("watch-more-site").assertExists()
        onNodeWithTag("watch-more-copy").performClick()
        waitForIdle()
        assertEquals(video.url, system.copied)
        onNodeWithTag("watch-more-copy").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun live_offline_mostra_o_canal_e_o_caminho_para_os_videos_dele() = runComposeUiTest {
        val (app, _) = newTestApp(extractor = OfflineExtractor(zack))
        app.data.updateSettings { it.copy(showChat = false) } // o chat de verdade falaria com a rede
        val live = Media(Platform.YouTube, "abcdefghijk", "zackrawrr", isLive = true, url = "https://www.youtube.com/watch?v=abcdefghijk")
        app.session.open(live)
        waitUntil(timeoutMillis = 5_000) { app.session.load.value is LoadState.Error }
        val error = assertIs<LoadState.Error>(app.session.load.value)
        assertTrue(error.offline, "canal offline não é falha")
        assertEquals(zack, error.channel)

        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { WatchScreen(fullscreen = false) } } }
        waitForIdle()
        onNodeWithTag("player-offline").assertExists()
        onNodeWithTag("player-offline-retry").assertExists()
        onNodeWithTag("watch-more").assertDoesNotExist()
        onNodeWithTag("player-offline-channel").performClick()
        waitForIdle()
        assertEquals(Route.ChannelPage(zack), app.nav.current, "o botão leva aos vídeos do canal")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun quadro_offline_do_multi_mostra_o_canal_e_tenta_de_novo() = runComposeUiTest {
        var calls = 0
        val (app, _) = newTestApp(tileResolve = { media ->
            calls++
            if (calls == 1) throw ChannelOfflineException(zack.name, zack) else fakeTileResolve(media)
        })
        app.ui.multiChat = false
        val live = Media(Platform.Twitch, "zackrawrr", "Live antiga", zack, isLive = true, viewCount = 895, url = zack.url)
        app.multi.add(live)
        app.nav.goRoot(Route.Multi)
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.requiredSize(1000.dp, 740.dp)) { ApexApp() } } }
        waitUntil(timeoutMillis = 5_000) { app.multi.tiles.value.single().load.value is LoadState.Error }
        waitForIdle()
        onNodeWithTag("tile-offline-${live.key}").assertExists()
        onNodeWithTag("tile-retry-${live.key}").performClick()
        waitUntil(timeoutMillis = 5_000) { app.multi.tiles.value.single().load.value is LoadState.Ready }
        waitForIdle()
        onNodeWithTag("tile-offline-${live.key}").assertDoesNotExist()
        assertEquals(2, calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun o_cartao_mostra_os_atalhos_com_o_mouse_em_cima() = runComposeUiTest {
        val (app, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides app) { Box(Modifier.width(320.dp)) { VideoCard(video, Modifier.width(320.dp)) } } }
        waitForIdle()
        onNodeWithTag("card-new-tab").assertDoesNotExist()
        onNodeWithTag("card-background").assertDoesNotExist()
        // Mouse em cima do cartão: os atalhos aparecem no canto da capa.
        onRoot().performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithTag("card-later").assertExists()
        onNodeWithTag("card-background").assertExists()
        onNodeWithTag("card-new-tab").performMouseInput { moveTo(center); click(center) }
        waitForIdle()
        assertEquals(listOf(video.key), app.tabs.medias.map { it.key }, "o atalho abre em nova aba")
        assertNull(app.session.current.value, "sem interromper (nem abrir) o que toca")
    }
}
