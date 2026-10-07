package app.apex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.InnerTube
import app.apex.source.KickSource
import app.apex.source.SearchDate
import app.apex.source.SearchDuration
import app.apex.source.SearchFeature
import app.apex.source.SearchFilters
import app.apex.source.SearchSort
import app.apex.source.SearchType
import app.apex.source.YouTubeSource
import app.apex.source.parseJson
import app.apex.state.ViewerRange
import app.apex.state.ViewerSort
import app.apex.state.matchSubscriptions
import app.apex.state.matchesWords
import app.apex.state.normalizeText
import app.apex.state.searchWords
import app.apex.ui.search.FiltersDialog
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pesquisa nas inscrições, filtros da pesquisa e das categorias, cabeçalho de playlist e lives da Kick. */
class BrowseTest {
    private fun live(id: String, title: String, viewers: Long, category: String = "Just Chatting", channelName: String = id) = Media(
        Platform.Twitch, id, title, Channel(Platform.Twitch, id, channelName), viewCount = viewers, isLive = true, category = category,
        url = "https://www.twitch.tv/$id",
    )

    // ------------------------------------------------------------------ busca nas inscrições

    @Test
    fun busca_ignora_acentos_e_maiusculas_e_exige_todas_as_palavras() {
        assertEquals("musica agua coracao", normalizeText("MÚSICA Água Coração"))
        assertEquals(listOf("copa", "do", "mundo"), searchWords("  Copa  do MUNDO "))
        val words = searchWords("copa mundo")
        assertTrue(matchesWords(words, "Melhores momentos da COPA do Mundo", null))
        assertTrue(matchesWords(words, "Copa América", "Mundo Bita"), "uma palavra em cada texto também vale")
        assertFalse(matchesWords(searchWords("copa mundo"), "Só a copa"))
        assertTrue(matchesWords(emptyList(), "qualquer coisa"), "sem palavras tudo combina")
    }

    @Test
    fun pesquisa_nas_inscricoes_acha_canais_e_so_videos_de_canais_seguidos() {
        val gaulesYt = Channel(Platform.YouTube, "UC1", "Gaules", handle = "@Gaules")
        val gaulesTw = Channel(Platform.Twitch, "gaules", "Gaules")
        val filipe = Channel(Platform.Twitch, "filiperaaamos", "FilipeRaaamos")
        val estranho = Channel(Platform.YouTube, "UC9", "Canal que não sigo")
        val subs = listOf(gaulesYt, gaulesTw, filipe)
        val items = listOf(
            Media(Platform.YouTube, "v1", "Partida completa de CS", gaulesYt, url = "u1"),
            Media(Platform.Twitch, "filiperaaamos", "Rank no Valorant", filipe, isLive = true, category = "VALORANT", url = "u2"),
            Media(Platform.YouTube, "v3", "Valorant: melhores momentos", estranho, url = "u3"),
        )
        val porNome = matchSubscriptions("gaules", subs, items)
        assertEquals(listOf("Gaules", "Gaules"), porNome.channels.map { it.name })
        assertEquals(setOf(Platform.YouTube, Platform.Twitch), porNome.channels.map { it.platform }.toSet())

        val porPalavra = matchSubscriptions("valorant", subs, items)
        assertEquals(listOf("filiperaaamos"), porPalavra.media.map { it.id }, "o vídeo do canal que a pessoa não segue não entra")

        assertEquals(listOf("Gaules"), matchSubscriptions("gaules", subs, items, setOf(Platform.Twitch)).channels.map { it.name })
        assertTrue(matchSubscriptions("   ", subs, items).isEmpty)
        assertTrue(matchSubscriptions("xyzinexistente", subs, items).isEmpty)
    }

    // ------------------------------------------------------------------ categoria

    @Test
    fun categoria_filtra_por_publico_e_por_palavras() {
        val (app, _) = newTestApp()
        val cat = app.screens.category(Platform.Twitch, LiveCategory("1", "Just Chatting", null, null))
        val lives = listOf(
            live("a", "Conversa da noite", 50), live("b", "Reação ao jogo", 450, channelName = "Zé"), live("c", "Música ao vivo", 5_000, category = "Música"),
            live("d", "Campeonato", 25_000),
        )
        assertEquals(4, cat.visible(lives).size)
        cat.range = ViewerRange.Small
        assertEquals(listOf("b"), cat.visible(lives).map { it.id })
        cat.range = ViewerRange.Big
        assertEquals(listOf("d"), cat.visible(lives).map { it.id })
        cat.range = ViewerRange.Any
        cat.query = "MUSICA"
        assertEquals(listOf("c"), cat.visible(lives).map { it.id }, "acha “Música” digitando sem acento e em maiúsculas")
        cat.query = "ze"
        assertEquals(listOf("b"), cat.visible(lives).map { it.id }, "também procura pelo nome do canal")
        assertEquals(listOf("Mais espectadores", "Menos espectadores"), ViewerSort.entries.map { it.label })
        assertEquals(ViewerSort.Most, cat.sort)
    }

    // ------------------------------------------------------------------ filtros da pesquisa

    private fun bytesOf(f: SearchFilters): List<Int> =
        Base64.getDecoder().decode(YouTubeSource(InnerTube()).searchParams(f)!!).map { it.toInt() and 0xFF }

    @Test
    fun filtros_do_youtube_viram_o_parametro_certo() {
        val f = SearchFilters(
            sort = SearchSort.Views, date = SearchDate.Today, duration = SearchDuration.Short, liveOnly = true, type = SearchType.Any,
            features = setOf(SearchFeature.FourK, SearchFeature.HDR),
        )
        // ordem (8,3) + filtros (18, tamanho): data (8,2), duração (24,1), ao vivo (64,1), 4K (112,1) e HDR (200,1,1)
        assertContentEquals(listOf(0x08, 3, 0x12, 11, 0x08, 2, 0x18, 1, 0x40, 1, 0x70, 1, 0xC8, 0x01, 1), bytesOf(f))
        // Sem filtro de tipo ("Tudo") não manda o byte do tipo; "Vídeo" e "Canal" mandam.
        assertFalse(bytesOf(SearchFilters(type = SearchType.Any)).windowed(2).any { it == listOf(0x10, 1) })
        assertTrue(bytesOf(SearchFilters(type = SearchType.Video)).windowed(2).any { it == listOf(0x10, 1) })
        assertTrue(bytesOf(SearchFilters(type = SearchType.Channel)).windowed(2).any { it == listOf(0x10, 2) })
        assertTrue(bytesOf(SearchFilters(type = SearchType.Playlist)).windowed(2).any { it == listOf(0x10, 3) })
        // O tamanho declarado do bloco de filtros sempre bate com o que foi escrito.
        val all = bytesOf(f.copy(features = SearchFeature.entries.toSet()))
        assertEquals(all.size - 4, all[3], "bloco de filtros: tamanho declarado = bytes seguintes")

        assertEquals(0, SearchFilters(type = SearchType.Any).activeCount)
        assertEquals(6, f.activeCount, "ordem, data, duração, ao vivo e 2 recursos (o tipo em Tudo não conta)")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun painel_de_filtros_aplica_cada_escolha() = runComposeUiTest {
        var current by mutableStateOf(SearchFilters(type = SearchType.Any))
        setContent { FiltersDialog(current, { current = it }, {}) }
        onNodeWithText("Hoje").performClick()
        assertEquals(SearchDate.Today, current.date)
        onNodeWithText("Canal").performClick()
        assertEquals(SearchType.Channel, current.type)
        onNodeWithText("4K").performClick()
        onNodeWithText("HDR").performClick()
        assertEquals(setOf(SearchFeature.FourK, SearchFeature.HDR), current.features)
        onNodeWithText("4K").performClick()
        assertEquals(setOf(SearchFeature.HDR), current.features, "tocar de novo desliga")
        onNodeWithText("Ao vivo").performClick()
        assertTrue(current.liveOnly)
        onNodeWithText("Visualizações").performClick()
        assertEquals(SearchSort.Views, current.sort)
        onNodeWithText("Limpar tudo").performClick()
        assertEquals(SearchFilters(type = SearchType.Any), current)
    }

    // ------------------------------------------------------------------ playlist

    @Test
    fun cabecalho_da_playlist_traz_dono_numeros_e_descricao() {
        val root = parseJson(
            """{"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{
              "title":{"dynamicTextViewModel":{"text":{"content":"GAMEPLAYS LENDÁRIAS"}}},
              "metadata":{"contentMetadataViewModel":{"metadataRows":[
                {"metadataParts":[{"avatarStack":{"avatarStackViewModel":{"text":{"content":"por Gaules"}}}}]},
                {"metadataParts":[{"text":{"content":"Playlist"}},{"text":{"content":"30 vídeos"}},{"text":{"content":"2.341 visualizações"}}]}]}},
              "description":{"descriptionPreviewViewModel":{"description":{"content":"As melhores gameplays do Gaules e a Tribo!!"}}}}}}},
             "sidebar":{"playlistSidebarRenderer":{"items":[
               {"playlistSidebarPrimaryInfoRenderer":{"title":{"runs":[{"text":"GAMEPLAYS LENDÁRIAS"}]},
                 "stats":[{"runs":[{"text":"30"},{"text":" vídeos"}]},{"simpleText":"2.341 visualizações"},{"runs":[{"text":"Atualizado há "},{"text":"7"},{"text":" dias"}]}]}},
               {"playlistSidebarSecondaryInfoRenderer":{"videoOwner":{"videoOwnerRenderer":{"title":{"runs":[{"text":"Gaules",
                 "navigationEndpoint":{"browseEndpoint":{"browseId":"UC5ZTRH1zclthyc6b_D3m2Pw"}}}]}}}}}]}}}""",
        )!!
        val info = YouTubeSource(InnerTube()).parsePlaylistInfo(root)
        assertNotNull(info)
        assertEquals("GAMEPLAYS LENDÁRIAS", info.title)
        assertEquals("Gaules", info.owner?.name)
        assertEquals("UC5ZTRH1zclthyc6b_D3m2Pw", info.owner?.id)
        assertEquals(listOf("30 vídeos", "2.341 visualizações", "Atualizado há 7 dias"), info.stats)
        assertEquals("As melhores gameplays do Gaules e a Tribo!!", info.description)
        assertEquals(null, YouTubeSource(InnerTube()).parsePlaylistInfo(parseJson("""{"outra":"coisa"}""")!!))
    }

    // ------------------------------------------------------------------ Kick

    @Test
    fun live_do_endereco_novo_da_kick_vira_media() {
        val kick = KickSource()
        val media = kick.webLiveToMedia(
            parseJson(
                """{"id":"01a11688","title":"CBLOW REACT AS 13H","viewer_count":6812,"thumbnail":{"src":"https://images.kick.com/t.webp"},
                "channel":{"id":64245633,"slug":"yoda","profile_pic":"https://files.kick.com/p.webp","username":"YoDa"},
                "category":{"id":5,"name":"League of Legends","slug":"league-of-legends"},"language":"pt","is_mature":false}""",
            ),
        )
        assertNotNull(media)
        assertEquals("yoda", media.id)
        assertEquals("CBLOW REACT AS 13H", media.title)
        assertEquals(6812L, media.viewCount)
        assertEquals("League of Legends", media.category)
        assertEquals("https://kick.com/yoda", media.url)
        assertEquals("YoDa", media.channel?.name)
        assertTrue(media.isLive)
        assertEquals(null, kick.webLiveToMedia(parseJson("""{"title":"sem canal"}""")))
    }
}
