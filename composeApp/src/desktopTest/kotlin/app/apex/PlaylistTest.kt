package app.apex

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.AppContainer
import app.apex.BrowserOption
import app.apex.LocalApp
import app.apex.SystemServices
import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.LocalPlaylist
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.InnerTube
import app.apex.source.AppJson
import app.apex.source.YouTubeSource
import app.apex.source.parseJson
import app.apex.ui.components.ContextMenuHost
import app.apex.ui.components.videoItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Playlists dos canais, cópia para as playlists locais e o menu do botão direito. */
class PlaylistTest {
    @Test
    fun le_as_playlists_de_um_canal_nos_dois_formatos_do_youtube() {
        val root = parseJson(
            """{"a":[
              {"lockupViewModel":{"contentId":"PLnovo1","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Partidas Completas"}}},
                "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                  "image":{"sources":[{"url":"https://i.ytimg.com/vi/aaa/hq.jpg"}]},
                  "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"100 vídeos"}}]}}]}}}}}},
              {"lockupViewModel":{"contentId":"VIDEO1","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Isto é um vídeo, não uma playlist"}}}}},
              {"gridPlaylistRenderer":{"playlistId":"PLantigo","title":{"runs":[{"text":"Retrospectivas"}]},
                "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/bbb/a.jpg"},{"url":"https://i.ytimg.com/vi/bbb/b.jpg"}]},
                "videoCountText":{"runs":[{"text":"12"},{"text":" vídeos"}]}}},
              {"lockupViewModel":{"contentId":"PLnovo1","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Repetida"}}}}}
            ]}""",
        )!!
        val playlists = YouTubeSource(InnerTube()).parsePlaylists(root)
        assertEquals(listOf("PLnovo1", "PLantigo"), playlists.map { it.id })
        assertEquals("Partidas Completas", playlists[0].title)
        assertEquals("https://i.ytimg.com/vi/aaa/hq.jpg", playlists[0].thumbnailUrl)
        assertEquals("100 vídeos", playlists[0].countText)
        assertEquals("Retrospectivas", playlists[1].title)
        assertEquals("https://i.ytimg.com/vi/bbb/b.jpg", playlists[1].thumbnailUrl)
    }

    private fun video(i: Int) = Media(
        Platform.YouTube, "vid%08d".format(i), "Título longo do vídeo número $i para ocupar espaço na lista",
        channel = Channel(Platform.YouTube, "UCcanal", "Canal", "https://yt3.ggpht.com/avatar-bem-comprido-$i", "@canal", 123_456, true, "https://www.youtube.com/channel/UCcanal"),
        thumbnailUrl = "https://i.ytimg.com/vi/vid$i/hqdefault.jpg?sqp=-oaymwEmCKgBEF5IWvKriqkDGQgBFQAAAAAYASUAAMhCPQCAokN4AbgC8xg=&rs=AOn4CLD-Y8_TXtXkO23SS3hHSmTkVFkYRw",
        durationSec = 600, viewCount = 1000, publishedAt = 1_700_000_000_000, url = "https://www.youtube.com/watch?v=vid$i",
    )

    private fun data() = UserData(FileStore(Files.createTempDirectory("apex-pl").toFile()), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun copiar_playlist_pequena_cria_uma_playlist_com_o_mesmo_nome() {
        val d = data()
        val ids = d.importPlaylist("  Minha cópia ", (1..5).map { video(it) } + video(3))
        assertEquals(1, ids.size)
        val pl = d.playlists.value.single()
        assertEquals("Minha cópia", pl.name)
        assertEquals(5, pl.items.size)
        // O enfeite que só ocupa espaço na sincronização fica de fora; o que identifica o vídeo e o canal fica.
        assertEquals(null, pl.items.first().channel?.avatarUrl)
        assertEquals("UCcanal", pl.items.first().channel?.id)
    }

    @Test
    fun copiar_playlist_grande_divide_em_partes_que_cabem_na_sincronizacao() {
        val d = data()
        val ids = d.importPlaylist("Enorme", (1..600).map { video(it) })
        assertTrue(ids.size > 1, "600 vídeos não cabem num item só")
        assertEquals(600, d.playlists.value.sumOf { it.items.size })
        assertEquals("Enorme (1/${ids.size})", d.playlists.value.first().name)
        // O servidor recusa itens acima de 60 mil caracteres: nenhuma parte pode passar disso.
        d.playlists.value.forEach { pl ->
            val chars = AppJson.encodeToString(LocalPlaylist.serializer(), pl).length
            assertTrue(chars < 60_000, "${pl.name} ficou com $chars caracteres")
        }
        assertEquals(emptyList(), d.importPlaylist("Vazia", emptyList()))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun botao_direito_abre_o_menu_e_o_clique_normal_nao() = runComposeUiTest {
        setContent {
            ContextMenuHost(menu = { dismiss ->
                DropdownMenu(expanded = true, onDismissRequest = dismiss) {
                    DropdownMenuItem(text = { Text("Copiar link") }, onClick = dismiss)
                }
            }) { Text("alvo") }
        }
        onNodeWithText("Copiar link").assertDoesNotExist()
        onNodeWithText("alvo").performClick()
        onNodeWithText("Copiar link").assertDoesNotExist()
        onNodeWithText("alvo").performMouseInput { rightClick() }
        onNodeWithText("Copiar link").assertExists()
    }
}

class ContextMenuCardsTest {
    private fun newApp(): Pair<AppContainer, FakeSystem> = newTestApp()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun botao_direito_num_video_mostra_as_opcoes() = runComposeUiTest {
        val (container, system) = newApp()
        val media = Media(
            Platform.YouTube, "abcdefghijk", "Um vídeo qualquer", channel = Channel(Platform.YouTube, "UCx", "Um Canal"),
            url = "https://www.youtube.com/watch?v=abcdefghijk", durationSec = 120,
        )
        setContent { androidx.compose.runtime.CompositionLocalProvider(LocalApp provides container) { app.apex.ui.components.VideoCard(media) } }
        onNodeWithText("Reproduzir").assertDoesNotExist()
        onNodeWithText("Um vídeo qualquer").performMouseInput { rightClick() }
        listOf("Reproduzir", "Salvar em Assistir depois", "Adicionar à playlist…", "Ir para o canal", "Copiar link", "Abrir no navegador").forEach {
            onNodeWithText(it).assertExists()
        }
        onNodeWithText("Copiar link").performClick()
        assertEquals(media.url, system.copied)
    }

    /** O mesmo vídeo em duas seções da mesma grade (feed das inscrições e recomendados) derrubava o app: "Key was already used". */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mesmo_video_em_duas_secoes_da_grade_nao_derruba_o_app() = runComposeUiTest {
        val (container, _) = newApp()
        val repetido = Media(Platform.YouTube, "OcSR10zUZFQ", "Vídeo repetido", channel = Channel(Platform.YouTube, "UCx", "Canal"), url = "https://www.youtube.com/watch?v=OcSR10zUZFQ")
        val outro = Media(Platform.YouTube, "outroVideo1", "Outro vídeo", url = "https://www.youtube.com/watch?v=outroVideo1")
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalApp provides container) {
                app.apex.ui.components.ApexGrid(androidx.compose.foundation.lazy.grid.rememberLazyGridState()) {
                    // Duplicado dentro da própria lista e também na outra seção.
                    videoItems(listOf(repetido, outro, repetido), section = "feed:")
                    videoItems(listOf(outro, repetido))
                }
            }
        }
        onAllNodesWithText("Vídeo repetido").assertCountEquals(2)
        onAllNodesWithText("Outro vídeo").assertCountEquals(2)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun botao_direito_numa_playlist_oferece_copiar() = runComposeUiTest {
        val (container, system) = newApp()
        val playlist = app.apex.source.RemotePlaylist("PLx8lfO", "Partidas Completas", null, "100 vídeos")
        setContent { androidx.compose.runtime.CompositionLocalProvider(LocalApp provides container) { app.apex.ui.components.RemotePlaylistCard(playlist) } }
        onNodeWithText("Partidas Completas").performMouseInput { rightClick() }
        listOf("Abrir", "Copiar para minhas playlists", "Copiar link", "Abrir no navegador").forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("Copiar link").performClick()
        assertEquals("https://www.youtube.com/playlist?list=PLx8lfO", system.copied)
    }
}
