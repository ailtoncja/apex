package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.tabs.VideoTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** As abas de vídeo, como no navegador: clique normal troca o vídeo da aba ativa, "nova aba" acrescenta, fechar abre a vizinha, Ctrl+Tab circula. */
class VideoTabsTest {
    private fun video(id: String, live: Boolean = false) = Media(Platform.YouTube, id, "Vídeo $id", isLive = live, url = "https://www.youtube.com/watch?v=$id")

    @Test
    fun clique_normal_troca_a_aba_ativa_e_nova_aba_acrescenta() {
        val (app, _) = newTestApp()
        val a = video("a"); val b = video("b"); val c = video("c")
        app.openMedia(a)
        assertEquals(listOf("YouTube:a"), app.tabs.medias.map { it.key }, "o primeiro vídeo vira a primeira aba")
        app.openMedia(b)
        assertEquals(listOf("YouTube:b"), app.tabs.medias.map { it.key }, "o clique normal troca o vídeo da aba ativa, como um link no navegador")
        app.openInNewTab(c)
        assertEquals(listOf("YouTube:b", "YouTube:c"), app.tabs.medias.map { it.key }, "nova aba entra no fim sem mexer no que toca")
        assertEquals("YouTube:b", app.session.current.value?.key, "o que tocava continua tocando")
        assertEquals("Aberto em nova aba", app.ui.toast)
        app.openInNewTab(c)
        assertEquals("Já está aberto numa aba", app.ui.toast)
        app.openMedia(c)
        assertEquals(listOf("YouTube:b", "YouTube:c"), app.tabs.medias.map { it.key }, "abrir um vídeo que já é aba só a ativa")
        assertEquals("YouTube:c", app.tabs.activeKey())
    }

    @Test
    fun ctrl_tab_circula_e_fechar_a_aba_ativa_abre_a_vizinha() {
        val (app, _) = newTestApp()
        val a = video("a"); val b = video("b"); val c = video("c")
        app.openMedia(a); app.openInNewTab(b); app.openInNewTab(c)
        app.cycleTab(1)
        assertEquals("YouTube:b", app.session.current.value?.key)
        app.cycleTab(-1)
        assertEquals("YouTube:a", app.session.current.value?.key)
        app.cycleTab(-1)
        assertEquals("YouTube:c", app.session.current.value?.key, "dá a volta")
        assertEquals(listOf("YouTube:a", "YouTube:b", "YouTube:c"), app.tabs.medias.map { it.key }, "circular não mexe na lista")

        app.closeCurrentTab() // Ctrl+W na última: sem vizinha à direita, abre a da esquerda
        assertEquals("YouTube:b", app.session.current.value?.key, "fechar a aba ativa abre a vizinha, como no navegador")
        assertEquals(listOf("YouTube:a", "YouTube:b"), app.tabs.medias.map { it.key })
        app.closeTab("YouTube:a")
        assertEquals("YouTube:b", app.session.current.value?.key, "fechar outra aba não mexe no que toca")
        assertEquals(listOf("YouTube:b"), app.tabs.medias.map { it.key })
        app.closeCurrent() // o ✕ do mini player: para e fecha a aba, sem abrir outra
        assertTrue(app.tabs.medias.isEmpty())
        assertNull(app.session.current.value)

        app.openMedia(a); app.openInNewTab(b)
        app.closeCurrentTab()
        assertEquals("YouTube:b", app.session.current.value?.key, "fechar a primeira abre a da direita")
        app.closeCurrentTab()
        assertNull(app.session.current.value, "sem vizinha, para")
        assertTrue(app.tabs.medias.isEmpty())
    }

    @Test
    fun live_de_um_canal_entra_em_nova_aba_sem_interromper_o_que_toca() {
        val (app, _) = newTestApp()
        app.openMedia(video("a"))
        app.openLiveInNewTab(Channel(Platform.Twitch, "gaules", "Gaules"))
        app.openLiveInNewTab(Channel(Platform.Kick, "yoda", "Yoda"))
        assertEquals(listOf("YouTube:a", "Twitch:gaules", "Kick:yoda"), app.tabs.medias.map { it.key })
        assertTrue(app.tabs.medias.drop(1).all { it.isLive }, "entram como live")
        assertEquals("YouTube:a", app.session.current.value?.key, "o vídeo continua tocando")
    }

    @Test
    fun tocar_em_sequencia_usa_as_abas_seguintes_como_fila() {
        val (app, _) = newTestApp()
        val a = video("a"); val b = video("b"); val c = video("c")
        app.openMedia(a); app.openInNewTab(b); app.openInNewTab(c)
        assertEquals(listOf("YouTube:b", "YouTube:c"), app.tabs.after(a).map { it.key })
        assertFalse(app.ui.tabsSequence)
        app.toggleTabsSequence()
        assertTrue(app.ui.tabsSequence)
        assertEquals(listOf("YouTube:b", "YouTube:c"), app.session.upNext.value.map { it.key }, "ligar o modo põe as abas seguintes na fila")
        app.openMedia(c)
        assertTrue(app.session.upNext.value.isEmpty(), "a última aba não tem seguinte")
    }

    @Test
    fun a_aba_de_um_link_ganha_o_titulo_de_verdade_quando_o_video_e_preparado() {
        // Um link de descrição ou do chat entra como "Carregando…"; antes a aba ficava assim para sempre.
        val prepared = object : app.apex.source.Extractor by FakeExtractor2() {
            override suspend fun resolve(media: Media) = app.apex.model.Resolved(
                media.copy(title = "Título de verdade", thumbnailUrl = "https://i.ytimg.com/vi/${media.id}/hq.jpg"),
                null, null, null, null, emptyList(), emptyList(),
                listOf(app.apex.model.Quality("720p", 720, "https://fake/${media.id}.mp4")), null,
            )
        }
        val (app, _) = newTestApp(extractor = prepared)
        app.openMedia(Media(Platform.YouTube, "abc", "Carregando…", url = "https://www.youtube.com/watch?v=abc"))
        val end = System.currentTimeMillis() + 5_000
        while (app.tabs.medias.single().title != "Título de verdade") { check(System.currentTimeMillis() < end) { "a aba não mudou: ${app.tabs.medias}" }; Thread.sleep(10) }
        assertEquals("https://i.ytimg.com/vi/abc/hq.jpg", app.tabs.medias.single().thumbnailUrl)
        assertEquals("YouTube:abc", app.tabs.medias.single().key)
    }

    @Test
    fun as_abas_ficam_guardadas_e_tem_limite() {
        val (app, _) = newTestApp()
        repeat(VideoTabs.MAX + 3) { app.openInNewTab(video("v$it")) }
        assertEquals(VideoTabs.MAX, app.tabs.medias.size, "cabem até ${VideoTabs.MAX}")
        assertEquals("Cabem até ${VideoTabs.MAX} abas", app.ui.toast)

        val dir = Files.createTempDirectory("apex-tabs").toFile()
        val d = UserData(FileStore(dir), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        d.setTabs(app.tabs.medias)
        d.flush()
        val again = UserData(FileStore(dir), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals(VideoTabs.MAX, again.tabs.value.size, "a lista volta ao reabrir o app")
    }
}
