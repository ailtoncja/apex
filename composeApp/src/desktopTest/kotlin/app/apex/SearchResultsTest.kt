package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.source.ChannelHit
import app.apex.source.RemotePlaylist
import app.apex.source.SearchFilters
import app.apex.source.SearchType
import app.apex.state.ChannelEntry
import app.apex.state.MediaEntry
import app.apex.state.PlaylistEntry
import app.apex.state.SearchKind
import app.apex.state.SearchSources
import app.apex.state.SubscriptionMatches
import app.apex.state.buildSearchEntries
import app.apex.state.extraCount
import app.apex.state.kind
import app.apex.state.withKind
import app.apex.ui.search.FiltersDialog
import app.apex.ui.search.SearchScreen
import app.apex.ui.shell.TopBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pesquisa como a do YouTube: uma lista só, com abas de tipo e o painel de filtros. */
class SearchResultsTest {
    private fun ch(p: Platform, id: String) = Channel(p, id, id)

    private fun vid(id: String, live: Boolean = false, p: Platform = Platform.YouTube, channel: Channel = ch(p, "ch-$id")) =
        Media(p, id, "Título $id", channel, isLive = live, url = "https://x/$id")

    private fun pl(id: String) = RemotePlaylist(id, "Playlist $id", null, null)

    private fun List<*>.ids() = map {
        when (it) {
            is ChannelEntry -> "c:" + it.channel.id
            is MediaEntry -> it.media.id
            is PlaylistEntry -> "p:" + it.playlist.id
            else -> "?"
        }
    }

    // um pouco de tudo, nas três plataformas
    private val src = SearchSources(
        ytVideos = listOf(vid("v1"), vid("v2"), vid("yl1", live = true), vid("v3"), vid("v4"), vid("v5"), vid("v6")),
        ytChannels = listOf(ch(Platform.YouTube, "ytc1"), ch(Platform.YouTube, "ytc2")),
        ytPlaylists = listOf(pl("a"), pl("b"), pl("c")),
        twitchLives = (1..8).map { vid("tl$it", live = true, p = Platform.Twitch) },
        twitchChannels = listOf(ChannelHit(ch(Platform.Twitch, "twc1"), null)),
        kickChannels = listOf(ChannelHit(ch(Platform.Kick, "kc1"), vid("kl1", live = true, p = Platform.Kick))),
    )

    // ------------------------------------------------------------------ abas de tipo e filtros

    @Test
    fun abas_de_tipo_ligam_os_filtros_do_youtube_e_voltam() {
        val base = SearchFilters(type = SearchType.Any)
        assertEquals(SearchKind.All, base.kind())
        for (k in SearchKind.entries) assertEquals(k, base.withKind(k).kind(), k.name)
        assertTrue(base.withKind(SearchKind.Lives).liveOnly)
        assertEquals(SearchType.Video, base.withKind(SearchKind.Videos).type)
        assertEquals(SearchType.Channel, base.withKind(SearchKind.Channels).type)
        assertEquals(SearchType.Playlist, base.withKind(SearchKind.Playlists).type)
        // trocar de aba não mexe nos outros filtros e tira a aba anterior
        val hoje = base.copy(date = app.apex.source.SearchDate.Today)
        assertEquals(app.apex.source.SearchDate.Today, hoje.withKind(SearchKind.Videos).date)
        assertFalse(hoje.withKind(SearchKind.Lives).withKind(SearchKind.All).liveOnly)
        // a aba de tipo não conta como "filtro ligado": ela já aparece no topo
        assertEquals(0, base.withKind(SearchKind.Lives).extraCount)
        assertEquals(0, base.withKind(SearchKind.Videos).extraCount)
        assertEquals(1, hoje.withKind(SearchKind.Videos).extraCount)
    }

    // ------------------------------------------------------------------ a lista

    @Test
    fun tudo_mostra_poucos_canais_depois_as_lives_e_so_entao_os_videos_com_playlists_no_meio() {
        val ids = buildSearchEntries(SearchKind.All, emptySet(), false, src).ids()
        assertTrue(ids.take(2).all { it.startsWith("c:") }, "primeiro no máximo dois canais: $ids")
        assertEquals(2, ids.count { it.startsWith("c:") }, "só dois canais entram em Tudo")
        val rest = ids.drop(2)
        assertTrue(rest.take(5).all { it.startsWith("tl") || it.startsWith("kl") || it.startsWith("yl") }, "depois cinco lives: $rest")
        val videos = rest.drop(5)
        assertEquals(listOf("v1", "v2", "v3", "v4", "p:a", "v5", "v6", "p:b"), videos, "a primeira playlist entra depois de quatro vídeos")
        assertFalse("p:c" in ids, "só duas playlists em Tudo")
    }

    @Test
    fun cada_aba_mostra_so_o_seu_tipo() {
        val videos = buildSearchEntries(SearchKind.Videos, emptySet(), false, src).ids()
        assertEquals(listOf("v1", "v2", "v3", "v4", "v5", "v6"), videos, "só vídeos do YouTube, sem lives")
        val lives = buildSearchEntries(SearchKind.Lives, emptySet(), false, src).ids()
        assertTrue(lives.all { it.startsWith("tl") || it.startsWith("kl") || it.startsWith("yl") })
        assertEquals(8 + 1 + 1, lives.size, "Twitch, Kick e YouTube juntas")
        assertTrue(lives.take(3).map { it.take(2) }.toSet().size == 3, "as três plataformas se alternam: $lives")
        val channels = buildSearchEntries(SearchKind.Channels, emptySet(), false, src).ids()
        assertEquals(setOf("c:ytc1", "c:ytc2", "c:twc1", "c:kc1"), channels.toSet())
        assertEquals(listOf("p:a", "p:b", "p:c"), buildSearchEntries(SearchKind.Playlists, emptySet(), false, src).ids())
    }

    @Test
    fun plataformas_ligadas_tiram_o_resto() {
        val twitch = setOf(Platform.Twitch)
        val all = buildSearchEntries(SearchKind.All, twitch, false, src).ids()
        assertTrue(all.none { it.startsWith("v") || it.startsWith("p:") || it.startsWith("yl") || it.startsWith("kl") || it == "c:ytc1" }, "só a Twitch: $all")
        assertTrue("c:twc1" in all && all.any { it.startsWith("tl") })
        assertEquals(emptyList(), buildSearchEntries(SearchKind.Videos, twitch, false, src).ids(), "vídeos só existem no YouTube")
        assertEquals(emptyList(), buildSearchEntries(SearchKind.Playlists, twitch, false, src).ids())
        val two = buildSearchEntries(SearchKind.Lives, setOf(Platform.Twitch, Platform.Kick), false, src).ids()
        assertTrue(two.none { it.startsWith("yl") } && "kl1" in two && "tl1" in two, "Twitch e Kick juntas: $two")
    }

    @Test
    fun quem_a_pessoa_segue_vem_antes_e_bloqueados_somem() {
        val followedTw = ch(Platform.Twitch, "twc1")
        val withFollow = SearchSources(
            ytChannels = src.ytChannels, twitchChannels = src.twitchChannels, kickChannels = src.kickChannels,
            twitchLives = src.twitchLives, followed = setOf(followedTw.key, vid("tl3", p = Platform.Twitch).channel!!.key),
        )
        val ch = buildSearchEntries(SearchKind.Channels, emptySet(), false, withFollow).ids()
        assertEquals("c:twc1", ch.first(), "o canal que a pessoa segue vem primeiro: $ch")
        val lives = buildSearchEntries(SearchKind.Lives, emptySet(), false, withFollow).ids()
        assertEquals("tl3", lives.first(), "a live de um canal seguido vem primeiro: $lives")
        val blocked = buildSearchEntries(SearchKind.Lives, emptySet(), false, withFollow, blocked = listOf(vid("tl3", p = Platform.Twitch).channel!!.key)).ids()
        assertFalse("tl3" in blocked)
        assertFalse("c:twc1" in buildSearchEntries(SearchKind.Channels, emptySet(), false, withFollow, blocked = listOf(followedTw.key)).ids())
    }

    @Test
    fun so_inscricoes_usa_apenas_o_que_combina_entre_os_canais_seguidos() {
        val seguido = ch(Platform.Twitch, "gaules")
        val mine = SubscriptionMatches(
            channels = listOf(seguido, ch(Platform.YouTube, "gaulesyt")),
            media = listOf(vid("m1", p = Platform.Twitch, channel = seguido), vid("m2", live = true, p = Platform.Twitch, channel = seguido), vid("m3")),
        )
        val s = SearchSources(ytVideos = src.ytVideos, twitchLives = src.twitchLives, mine = mine)
        assertEquals(listOf("c:gaules", "c:gaulesyt", "m2", "m1", "m3"), buildSearchEntries(SearchKind.All, emptySet(), true, s).ids(), "canais, depois as lives e os vídeos")
        assertEquals(listOf("m2"), buildSearchEntries(SearchKind.Lives, emptySet(), true, s).ids())
        assertEquals(listOf("m1", "m3"), buildSearchEntries(SearchKind.Videos, emptySet(), true, s).ids())
        assertEquals(listOf("c:gaules", "c:gaulesyt"), buildSearchEntries(SearchKind.Channels, emptySet(), true, s).ids())
        assertEquals(emptyList(), buildSearchEntries(SearchKind.Playlists, emptySet(), true, s).ids())
        assertTrue(buildSearchEntries(SearchKind.All, emptySet(), true, SearchSources()).isEmpty(), "sem nada seguido que combine, lista vazia")
    }

    @Test
    fun chaves_da_lista_nunca_se_repetem() {
        val dup = SearchSources(ytVideos = listOf(vid("v1"), vid("v1")), ytPlaylists = listOf(pl("a"), pl("a")))
        val keys = buildSearchEntries(SearchKind.All, emptySet(), false, dup).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ------------------------------------------------------------------ interface

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clicar_numa_aba_troca_o_tipo_e_mantem_as_plataformas() = runComposeUiTest {
        val (container, _) = newTestApp()
        val base = SearchFilters(type = SearchType.Any)
        container.screens.search("gaules", base).platforms = setOf(Platform.Twitch)
        container.nav.replaceTop(Route.Search("gaules", base))
        setContent { CompositionLocalProvider(LocalApp provides container) { SearchScreen(Route.Search("gaules", base)) } }
        onAllNodesWithText("Ao vivo").onFirst().performClick()
        waitForIdle()
        val route = container.nav.current as Route.Search
        assertEquals(SearchKind.Lives, route.filters.kind())
        assertEquals(setOf(Platform.Twitch), container.screens.search("gaules", route.filters).platforms, "a Twitch continua ligada")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun painel_de_filtros_liga_plataformas_e_so_inscricoes() = runComposeUiTest {
        var platforms by mutableStateOf(emptySet<Platform>())
        var onlySubs by mutableStateOf(false)
        setContent {
            FiltersDialog(
                SearchFilters(type = SearchType.Any), {}, {},
                platforms = platforms, onPlatforms = { platforms = it }, onlySubs = onlySubs, onOnlySubs = { onlySubs = it },
            )
        }
        onAllNodesWithText("Twitch").onFirst().performClick()
        onAllNodesWithText("Kick").onFirst().performClick()
        waitForIdle()
        assertEquals(setOf(Platform.Twitch, Platform.Kick), platforms, "duas plataformas ao mesmo tempo")
        onAllNodesWithText("Só dos canais que sigo").onFirst().performClick()
        waitForIdle()
        assertTrue(onlySubs)
        onAllNodesWithText("Todas as plataformas").onFirst().performClick()
        waitForIdle()
        assertEquals(emptySet(), platforms)
        assertTrue(onlySubs, "trocar de plataforma não desliga as inscrições")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun botao_de_pesquisar_da_barra_abre_os_resultados() = runComposeUiTest {
        val (container, _) = newTestApp()
        setContent { CompositionLocalProvider(LocalApp provides container) { TopBar {} } }
        onNode(hasSetTextAction()).performTextInput("minecraft")
        onNodeWithContentDescription("Pesquisar").performClick()
        waitForIdle()
        val route = container.nav.current
        assertTrue(route is Route.Search && route.query == "minecraft", "abriu $route")
    }
}
