package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.LiveFilter
import app.apex.source.SearchFilters
import app.apex.state.CategoryTab
import app.apex.state.HomeSource
import app.apex.state.ViewerRange
import app.apex.state.YOUTUBE_TOPIC_CATEGORIES
import app.apex.state.allows
import app.apex.state.channelsIn
import app.apex.state.describe
import app.apex.state.mediaIn
import app.apex.state.matchSubscriptions
import app.apex.state.toEnumSet
import app.apex.state.toggled
import app.apex.state.youtubeCategoryQueries
import app.apex.state.youtubeGameCategories
import app.apex.ui.home.HomeScreen
import app.apex.ui.subs.SubscriptionsScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Filtros que se combinam (YouTube + Twitch, Inscrições + Twitch…), os da tela inicial e as categorias do YouTube. */
class MultiFilterTest {
    private fun channel(p: Platform, id: String) = Channel(p, id, id)

    private fun media(p: Platform, id: String, live: Boolean = false, viewers: Long = 0) =
        Media(p, id, "Título $id", channel(p, "c-$id"), viewCount = viewers, isLive = live, url = "https://x/$id")

    // ------------------------------------------------------------------ plataformas

    @Test
    fun plataformas_se_combinam_e_com_as_tres_voltam_a_ser_todas() {
        assertTrue(emptySet<Platform>().allows(Platform.Kick), "vazio = todas")
        val yt = emptySet<Platform>().toggled(Platform.YouTube)
        val ytTw = yt.toggled(Platform.Twitch)
        assertEquals(setOf(Platform.YouTube, Platform.Twitch), ytTw)
        assertTrue(ytTw.allows(Platform.Twitch) && ytTw.allows(Platform.YouTube) && !ytTw.allows(Platform.Kick))
        assertEquals(setOf(Platform.Twitch), ytTw.toggled(Platform.YouTube), "desligar uma deixa a outra")
        assertEquals(emptySet(), ytTw.toggled(Platform.Kick), "com as três ligadas é o mesmo que nenhuma")
    }

    @Test
    fun descricao_das_plataformas_em_portugues() {
        assertEquals("todas as plataformas", emptySet<Platform>().describe())
        assertEquals("Kick", setOf(Platform.Kick).describe())
        assertEquals("YouTube e Twitch", setOf(Platform.YouTube, Platform.Twitch).describe())
    }

    @Test
    fun canais_e_videos_filtrados_por_varias_plataformas() {
        val channels = Platform.entries.map { channel(it, "a") }
        assertEquals(3, channels.channelsIn(emptySet()).size)
        assertEquals(setOf(Platform.YouTube, Platform.Kick), channels.channelsIn(setOf(Platform.YouTube, Platform.Kick)).map { it.platform }.toSet())
        val items = Platform.entries.map { media(it, "m") }
        assertEquals(listOf(Platform.Twitch), items.mediaIn(setOf(Platform.Twitch)).map { it.platform })
    }

    @Test
    fun pesquisa_nas_inscricoes_com_duas_plataformas() {
        val subs = Platform.entries.map { Channel(it, "gaules-${it.name}", "Gaules") }
        val found = matchSubscriptions("gaules", subs, emptyList(), setOf(Platform.Twitch, Platform.Kick))
        assertEquals(setOf(Platform.Twitch, Platform.Kick), found.channels.map { it.platform }.toSet())
    }

    @Test
    fun filtros_salvos_voltam_como_conjunto_e_ignoram_nomes_que_nao_existem() {
        val sources = listOf("Subs", "Trending", "NaoExiste").toEnumSet(HomeSource.entries.toTypedArray())
        assertEquals(setOf(HomeSource.Subs, HomeSource.Trending), sources)
        assertEquals(emptySet(), emptyList<String>().toEnumSet(Platform.entries.toTypedArray()))
    }

    // ------------------------------------------------------------------ Ao vivo

    @Test
    fun tela_ao_vivo_com_duas_plataformas_usa_duas_listas() {
        val (app, _) = newTestApp()
        val live = app.screens.live
        assertEquals(listOf(live.all), live.lists(), "sem filtro: a mistura das três")
        live.select(LiveFilter.Twitch)
        assertEquals(listOf(live.twitch), live.lists())
        live.platforms = live.platforms.toggled(Platform.YouTube)
        assertEquals(setOf(live.twitch, live.youtube), live.lists().toSet(), "Twitch e YouTube ao mesmo tempo")
        live.select(LiveFilter.All)
        assertEquals(emptySet(), live.platforms)
    }

    // ------------------------------------------------------------------ categorias do YouTube

    @Test
    fun jogos_do_youtube_vem_da_lista_da_twitch_sem_o_que_nao_e_jogo() {
        val twitch = listOf("Just Chatting", "Minecraft", "Music", "VALORANT", "Slots", "Counter-Strike").mapIndexed { i, n ->
            LiveCategory("$i", n, 1000L - i, "https://img/$i.jpg")
        }
        val games = youtubeGameCategories(twitch)
        assertEquals(listOf("Minecraft", "VALORANT", "Counter-Strike"), games.map { it.name })
        assertTrue(games.all { it.id.startsWith("yt:game:") }, "id próprio para não colidir com as categorias da Twitch")
        assertEquals("https://img/1.jpg", games.first().imageUrl, "mantém a capa")
        assertEquals(emptyList(), youtubeGameCategories(emptyList()))
    }

    @Test
    fun cada_assunto_do_youtube_tem_busca_de_lives_e_de_videos() {
        assertTrue(YOUTUBE_TOPIC_CATEGORIES.size >= 12)
        assertEquals(YOUTUBE_TOPIC_CATEGORIES.size, YOUTUBE_TOPIC_CATEGORIES.map { it.id }.toSet().size, "ids únicos")
        for (c in YOUTUBE_TOPIC_CATEGORIES) {
            val (lives, videos) = youtubeCategoryQueries(c)
            assertTrue(lives.isNotBlank() && videos.isNotBlank(), c.name)
            assertTrue(lives != videos, "a busca de lives é diferente da de vídeos (${c.name})")
            assertEquals(null, c.imageUrl)
        }
        val game = youtubeGameCategories(listOf(LiveCategory("1", "Minecraft", 1, "u"))).single()
        assertEquals("Minecraft" to "Minecraft gameplay", youtubeCategoryQueries(game))
    }

    @Test
    fun categoria_do_youtube_tem_abas_e_a_faixa_de_publico_so_vale_para_lives() {
        val (app, _) = newTestApp()
        val cat = app.screens.category(Platform.YouTube, YOUTUBE_TOPIC_CATEGORIES.first())
        assertFalse(cat.hasLanguage, "o YouTube não filtra por idioma")
        assertEquals(CategoryTab.Live, cat.tab)
        assertTrue(cat.current === cat.streams)
        cat.range = ViewerRange.Big
        val lives = listOf(media(Platform.YouTube, "a", live = true, viewers = 50), media(Platform.YouTube, "b", live = true, viewers = 20_000))
        assertEquals(listOf("b"), cat.visible(lives).map { it.id })
        cat.selectTab(CategoryTab.Videos)
        assertTrue(cat.current === cat.videos)
        val videos = listOf(media(Platform.YouTube, "v1", viewers = 10), media(Platform.YouTube, "v2", viewers = 5_000_000))
        assertEquals(listOf("v1", "v2"), cat.visible(videos).map { it.id }, "na aba de vídeos o número é de visualizações: não filtra por público")
        assertTrue(app.screens.category(Platform.Twitch, LiveCategory("1", "Minecraft", null, null)).hasLanguage)
    }

    // ------------------------------------------------------------------ pesquisa

    @Test
    fun pesquisa_guarda_plataformas_e_inscricoes_ao_mesmo_tempo() {
        val (app, _) = newTestApp()
        val st = app.screens.search("valorant", SearchFilters())
        assertEquals(emptySet(), st.platforms)
        assertFalse(st.onlySubs)
        st.platforms = st.platforms.toggled(Platform.Twitch)
        st.onlySubs = true
        assertEquals(setOf(Platform.Twitch), st.platforms)
        assertTrue(st.onlySubs, "Inscrições + Twitch")
    }

    // ------------------------------------------------------------------ tela inicial

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tela_inicial_guarda_varios_filtros_ligados_ao_mesmo_tempo() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { HomeScreen() } }
        onAllNodesWithText("Inscrições").onFirst().performClick()
        onAllNodesWithText("Twitch").onFirst().performClick()
        waitForIdle()
        var settings = container.data.settings.value
        assertEquals(listOf("Subs"), settings.homeSources)
        assertEquals(listOf("Twitch"), settings.homePlatforms)

        // Mais um de cada: eles somam, não trocam.
        onAllNodesWithText("Ao vivo").onFirst().performClick()
        onAllNodesWithText("Kick").onFirst().performClick()
        waitForIdle()
        settings = container.data.settings.value
        assertEquals(setOf("Subs", "Live"), settings.homeSources.toSet())
        assertEquals(setOf("Twitch", "Kick"), settings.homePlatforms.toSet())

        // "Limpar" volta ao padrão.
        onAllNodesWithText("Limpar").onFirst().performClick()
        waitForIdle()
        settings = container.data.settings.value
        assertTrue(settings.homeSources.isEmpty() && settings.homePlatforms.isEmpty())
    }

    // ------------------------------------------------------------------ inscrições

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun inscricoes_mostram_canais_de_duas_plataformas_ao_mesmo_tempo() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.data.addSubscriptions(
            listOf(
                Channel(Platform.YouTube, "UC1", "Canal do YouTube"), Channel(Platform.Twitch, "tw1", "Canal da Twitch"),
                Channel(Platform.Kick, "k1", "Canal da Kick"),
            ),
        )
        setContent { CompositionLocalProvider(LocalApp provides container) { SubscriptionsScreen() } }
        // O nome aparece na fileira de avatares e na lista de canais: basta existir.
        fun has(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        onAllNodesWithText("Canais", substring = true).onFirst().performClick()
        waitForIdle()
        assertTrue(has("Canal do YouTube") && has("Canal da Twitch") && has("Canal da Kick"))

        // Liga Twitch e Kick: o do YouTube some e os dois ficam.
        onAllNodesWithText("Twitch (1)").onFirst().performClick()
        onAllNodesWithText("Kick (1)").onFirst().performClick()
        waitForIdle()
        assertFalse(has("Canal do YouTube"))
        assertTrue(has("Canal da Twitch") && has("Canal da Kick"))

        // Desliga a Twitch: só a Kick.
        onAllNodesWithText("Twitch (1)").onFirst().performClick()
        waitForIdle()
        assertFalse(has("Canal da Twitch"))
        assertTrue(has("Canal da Kick"))
    }
}
