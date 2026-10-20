package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.player.backgroundSource
import app.apex.source.Extractor
import app.apex.ui.shell.BackgroundAudioBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Música de fundo: um segundo player, só de som, que toca junto com o vídeo da página, com fila, volume próprio e repetir a lista; o vídeo
 * da página pode passar para o fundo do ponto em que está.
 */
class BackgroundAudioTest {
    private fun video(id: String) = Media(Platform.YouTube, id, "Música $id", url = "https://www.youtube.com/watch?v=$id")

    private fun resolved(media: Media, qualities: List<Quality>, live: Boolean = false, bridge: Boolean = false) =
        Resolved(media, null, null, null, null, emptyList(), emptyList(), qualities, "UA", isLive = live, rangeBridge = bridge)

    @Test
    fun toca_so_o_som_quando_a_plataforma_oferece() {
        // YouTube: o arquivo de áudio separado (pela ponte, se veio da abertura rápida).
        val yt = resolved(video("a"), listOf(
            Quality("1080p", 1080, "https://v/1080.mp4", "https://a/audio.m4a"),
            Quality("360p", 360, "https://v/360.mp4", "https://a/audio.m4a"),
        ), bridge = true)
        val s1 = backgroundSource(yt, 0)
        assertEquals("https://a/audio.m4a", s1.videoUrl)
        assertNull(s1.audioUrl)
        assertTrue(s1.audioOnly && s1.bridgeRanges)

        // Twitch e Kick: a qualidade "Somente áudio".
        val tw = resolved(Media(Platform.Twitch, "vod-1", "VOD", url = "https://www.twitch.tv/videos/1"), listOf(
            Quality("1080p60", 1080, "https://tw/1080.m3u8"), Quality("Somente áudio", 0, "https://tw/audio.m3u8", audioOnly = true),
        ))
        assertEquals("https://tw/audio.m3u8", backgroundSource(tw, 0).videoUrl)

        // Live do YouTube (só listas com vídeo): a menor, com o VLC sem decodificar a imagem.
        val live = resolved(video("radio").copy(isLive = true), listOf(
            Quality("Automático", 0, "https://yt/master.m3u8"), Quality("720p", 720, "https://yt/720.m3u8"), Quality("144p", 144, "https://yt/144.m3u8"),
        ), live = true)
        val s3 = backgroundSource(live, 0)
        assertEquals("https://yt/144.m3u8", s3.videoUrl)
        assertTrue(s3.audioOnly && s3.live && !s3.lowLatency)

        // Clipe: começa no começo dele e termina no fim dele; passado para o fundo no meio, continua de onde estava.
        val clip = resolved(video("c").copy(clipStartMs = 60_000, clipEndMs = 90_000), listOf(Quality("720p", 720, "https://v/720.mp4", "https://a/a.m4a")))
        assertEquals(60_000, backgroundSource(clip, 0).startMs)
        assertEquals(90_000, backgroundSource(clip, 0).endMs)
        assertEquals(75_000, backgroundSource(clip, 75_000).startMs)
    }

    /** Um extrator que prepara qualquer vídeo do YouTube com um arquivo de áudio. */
    private val prepared = object : Extractor by FakeExtractor2() {
        override suspend fun resolve(media: Media) =
            resolved(media, listOf(Quality("720p", 720, "https://v/${media.id}.mp4", "https://a/${media.id}.m4a")))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) { check(System.currentTimeMillis() < end) { "demorou demais: $what" }; Thread.sleep(10) }
    }

    @Test
    fun fila_volume_proprio_e_o_video_da_pagina_continua_intocado() {
        val extra = mutableListOf<FakePlayer2>()
        val (app, _) = newTestApp(extractor = prepared, newPlayer = { FakePlayer2().also { extra += it } })
        val main = app.player as FakePlayer2
        app.openMedia(video("filme"))
        waitFor("o vídeo da página tocar") { main.played.isNotEmpty() }

        app.playInBackground(video("a"))
        waitFor("a música tocar") { extra.singleOrNull()?.played?.isNotEmpty() == true }
        val bg = extra.single()
        assertEquals("https://a/a.m4a", bg.played.last().videoUrl, "só o som")
        assertTrue(bg.played.last().audioOnly)
        assertEquals("YouTube:filme", app.session.current.value?.key, "o vídeo da página continua")
        assertEquals(1, main.played.size, "e o player dele não foi mexido")

        app.background.enqueue(video("b"))
        assertEquals(listOf("YouTube:a", "YouTube:b"), app.background.queue.value.map { it.key })
        bg.state.value = bg.state.value.copy(ended = true) // a primeira acabou: vai para a seguinte
        waitFor("a seguinte tocar") { bg.played.last().videoUrl == "https://a/b.m4a" }
        assertEquals(1, app.background.index.value)

        val videoVolume = app.data.settings.value.volume
        app.background.setVolume(30)
        assertEquals(30, app.data.settings.value.backgroundVolume, "o volume da música fica guardado")
        assertEquals(videoVolume, app.data.settings.value.volume, "e é separado do volume do vídeo")

        app.background.close()
        assertTrue(bg.released, "parar solta o player de fundo")
        assertFalse(app.background.active)
        assertEquals("YouTube:filme", app.session.current.value?.key)
    }

    @Test
    fun repetir_a_lista_volta_para_a_primeira() {
        val extra = mutableListOf<FakePlayer2>()
        val (app, _) = newTestApp(extractor = prepared, newPlayer = { FakePlayer2().also { extra += it } })
        app.background.playAll(listOf(video("a"), video("b")))
        waitFor("tocar") { extra.singleOrNull()?.played?.isNotEmpty() == true }
        val bg = extra.single()
        app.background.setLoop(true)
        bg.state.value = bg.state.value.copy(ended = true)
        waitFor("a segunda") { bg.played.last().videoUrl == "https://a/b.m4a" }
        bg.state.value = bg.state.value.copy(ended = false) // a segunda começou (o player de verdade zera o "acabou" ao abrir)
        Thread.sleep(50)
        bg.state.value = bg.state.value.copy(ended = true)
        waitFor("voltar à primeira") { bg.played.last().videoUrl == "https://a/a.m4a" }
    }

    @Test
    fun o_video_da_pagina_passa_para_o_fundo_do_ponto_em_que_esta() {
        val extra = mutableListOf<FakePlayer2>()
        val (app, _) = newTestApp(extractor = prepared, newPlayer = { FakePlayer2().also { extra += it } })
        val main = app.player as FakePlayer2
        app.openMedia(video("show"))
        waitFor("tocar") { main.played.isNotEmpty() }
        main.state.value = main.state.value.copy(hasMedia = true, positionMs = 754_000, durationMs = 3_600_000)

        app.moveToBackground()
        assertNull(app.session.current.value, "o player da página fica livre para outro vídeo")
        assertTrue(app.tabs.medias.none { it.key == "YouTube:show" })
        waitFor("o fundo tocar") { extra.singleOrNull()?.played?.isNotEmpty() == true }
        assertEquals(754_000, extra.single().played.last().startMs, "continua de onde estava")
        assertEquals("YouTube:show", app.background.current?.key)
    }

    @Test
    fun voltar_a_assistir_abre_no_ponto_do_som_e_a_seguinte_fica_parada() {
        val extra = mutableListOf<FakePlayer2>()
        val (app, _) = newTestApp(extractor = prepared, newPlayer = { FakePlayer2().also { extra += it } })
        val main = app.player as FakePlayer2
        app.background.playAll(listOf(video("a"), video("b")))
        waitFor("tocar") { extra.singleOrNull()?.played?.isNotEmpty() == true }
        val bg = extra.single()
        bg.state.value = bg.state.value.copy(hasMedia = true, playing = true, positionMs = 95_000, durationMs = 200_000)
        waitFor("o estado chegar") { app.background.state.value.positionMs == 95_000L }

        app.watchFromBackground("YouTube:a")
        waitFor("abrir no player principal") { main.played.isNotEmpty() }
        assertEquals(95_000, main.played.last().startMs, "do ponto em que o som estava")
        assertEquals("YouTube:a", app.session.current.value?.key)
        assertEquals(listOf("YouTube:b"), app.background.queue.value.map { it.key }, "saiu do fundo")
        assertTrue(bg.stops > 0, "o som dela parou (nada em dobro)")
        val playedBefore = bg.played.size
        Thread.sleep(150)
        assertEquals(playedBefore, bg.played.size, "a seguinte não começa sozinha junto com o vídeo")

        app.background.togglePause() // o play da barra
        waitFor("a seguinte tocar") { bg.played.last().videoUrl == "https://a/b.m4a" }
    }

    @Test
    fun da_fila_sai_so_o_item_e_com_um_so_o_fundo_fecha() {
        val extra = mutableListOf<FakePlayer2>()
        val (app, _) = newTestApp(extractor = prepared, newPlayer = { FakePlayer2().also { extra += it } })
        val main = app.player as FakePlayer2
        app.background.playAll(listOf(video("a"), video("b")))
        waitFor("tocar") { extra.singleOrNull()?.played?.isNotEmpty() == true }
        val bg = extra.single()
        val stopsBefore = bg.stops

        app.watchFromBackground("YouTube:b") // só estava na fila
        waitFor("abrir") { main.played.isNotEmpty() }
        assertEquals(0, main.played.last().startMs, "da fila: de onde a pessoa tinha parado (aqui, o começo)")
        assertEquals(listOf("YouTube:a"), app.background.queue.value.map { it.key })
        assertEquals(stopsBefore, bg.stops, "a que toca continua")

        app.watchFromBackground("YouTube:a")
        assertFalse(app.background.active, "sem mais nada, o fundo fecha")
        assertTrue(bg.released)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clicar_no_titulo_da_barra_volta_a_assistir() = runComposeUiTest {
        val (app, _) = newTestApp(extractor = prepared)
        setContent { CompositionLocalProvider(LocalApp provides app) { BackgroundAudioBar() } }
        app.playInBackground(video("a"))
        waitForIdle()
        onNodeWithTag("background-watch").performClick()
        waitForIdle()
        assertEquals("YouTube:a", app.session.current.value?.key)
        assertFalse(app.background.active)
        onNodeWithTag("background-bar").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_barra_aparece_com_musica_e_o_x_para() = runComposeUiTest {
        val (app, _) = newTestApp(extractor = prepared)
        setContent { CompositionLocalProvider(LocalApp provides app) { BackgroundAudioBar() } }
        waitForIdle()
        onNodeWithTag("background-bar").assertDoesNotExist()
        app.playInBackground(video("a"))
        waitForIdle()
        onNodeWithTag("background-bar").assertExists()
        onNodeWithText("Música a").assertExists()
        onNodeWithTag("background-close").performClick()
        waitForIdle()
        onNodeWithTag("background-bar").assertDoesNotExist()
        assertFalse(app.background.active)
    }
}
