package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.ChannelSort
import app.apex.model.Platform
import app.apex.source.ChannelTab
import app.apex.source.InnerTube
import app.apex.source.YouTubeSource
import app.apex.ui.channel.ChannelScreen
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Os vídeos de um canal: todas as páginas (inclusive no formato novo de "próxima página" do YouTube) e as ordens mais recentes / mais vistos / mais antigos. */
class ChannelVideosTest {
    private fun video(id: String, title: String) = """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"Canal","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}]}}}"""

    /** A "próxima página" no formato antigo e no novo (o que o YouTube manda hoje nas listas de envios dos canais). */
    private fun oldNext(token: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""
    private fun newNext(token: String) =
        """{"continuationItemViewModel":{"trigger":"CONTINUATION_TRIGGER_ON_ITEM_SHOWN","continuationCommand":{"innertubeCommand":{"continuationCommand":{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}}"""

    /** A aba do canal, com o menu de ordens (aba "Vídeos"): cada opção leva o seu token. */
    private fun tabWithMenu(vararg tokens: String) = """{"contents":[${video("t1", "Do tab")}],"menu":{"listItems":[""" +
        tokens.joinToString(",") { """{"listItemViewModel":{"title":{"content":"opção"},"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"continuationCommand":{"token":"$it"}}}}}}}""" } + "]}}"

    private class Fake(val reply: (body: String) -> String) {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            bodies += body
            respond(reply(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    // ------------------------------------------------------------------ todas as páginas

    @Test
    fun a_proxima_pagina_do_formato_novo_tambem_e_encontrada() = runBlocking {
        val fake = Fake { body ->
            when {
                "\"continuation\":\"PAG3\"" in body -> """{"contents":[${video("v5", "Quinto")}]}"""
                "\"continuation\":\"PAG2\"" in body -> """{"contents":[${video("v3", "Terceiro")},${video("v4", "Quarto")},${oldNext("PAG3")}]}"""
                else -> """{"contents":[${video("v1", "Primeiro")},${video("v2", "Segundo")},${newNext("PAG2")}]}"""
            }
        }
        val source = YouTubeSource(InnerTube(fake.client))
        val all = mutableListOf<String>()
        var token: String? = null
        var pages = 0
        do {
            val page = source.channelVideos("UC123", ChannelTab.Videos, token)
            all += page.items.map { it.id }
            token = page.continuation
            pages++
        } while (token != null && pages < 10)
        assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), all, "antes só vinha a primeira página: 100 vídeos de um canal com milhares")
        assertEquals(3, pages)
    }

    // ------------------------------------------------------------------ as ordens

    @Test
    fun mais_vistos_pede_o_token_da_ordem_na_aba_do_canal_e_segue_dele() = runBlocking {
        val fake = Fake { body ->
            when {
                "\"continuation\":\"TOK_POP\"" in body -> """{"contents":[${video("p1", "Mais visto")},${newNext("POP2")}]}"""
                "\"continuation\":\"TOK_OLD\"" in body -> """{"contents":[${video("o1", "O mais antigo")}]}"""
                "\"params\"" in body -> tabWithMenu("TOK_RECENT", "TOK_POP", "TOK_OLD")
                else -> """{"contents":[${video("r1", "Recente")}]}"""
            }
        }
        val source = YouTubeSource(InnerTube(fake.client))
        val first = source.channelVideos("UC123", ChannelTab.Videos, sort = ChannelSort.Popular)
        assertEquals(listOf("p1"), first.items.map { it.id })
        assertEquals("POP2", first.continuation, "as páginas seguintes continuam da ordem escolhida")
        assertTrue("\"browseId\":\"UC123\"" in fake.bodies[0] && "\"params\":\"EgZ2aWRlb3PyBgQKAjoA\"" in fake.bodies[0], "primeiro abre a aba de vídeos do canal: ${fake.bodies[0]}")
        assertTrue("\"continuation\":\"TOK_POP\"" in fake.bodies[1], "depois pede a lista pelo token de \"Mais vistos\"")

        val oldest = source.channelVideos("UC123", ChannelTab.Videos, sort = ChannelSort.Oldest)
        assertEquals(listOf("o1"), oldest.items.map { it.id }, "\"Mais antigos\" é o terceiro token do menu")
    }

    @Test
    fun mais_recentes_continua_vindo_da_lista_de_envios_do_canal() = runBlocking {
        val fake = Fake { """{"contents":[${video("r1", "Recente")}]}""" }
        YouTubeSource(InnerTube(fake.client)).channelVideos("UC123", ChannelTab.Videos)
        assertTrue("\"browseId\":\"VLUULF123\"" in fake.bodies.single(), fake.bodies.single())
    }

    @Test
    fun na_aba_de_transmissoes_as_ordens_sao_botoes_soltos() = runBlocking {
        val chips = listOf("TOK_RECENT", "TOK_POP", "TOK_OLD").joinToString(",") {
            """{"chipViewModel":{"text":"x","tapCommand":{"innertubeCommand":{"continuationCommand":{"token":"$it"}}}}}"""
        }
        val fake = Fake { body ->
            if ("\"continuation\":\"TOK_POP\"" in body) """{"contents":[${video("live-pop", "Live popular")}]}"""
            else """{"contents":[${video("l1", "Live")}],"chips":[$chips]}"""
        }
        val page = YouTubeSource(InnerTube(fake.client)).channelVideos("UC123", ChannelTab.Lives, sort = ChannelSort.Popular)
        assertEquals(listOf("live-pop"), page.items.map { it.id })
        assertTrue("\"params\":\"EgdzdHJlYW1z8gYECgJ6AA==\"" in fake.bodies[0], "a aba de transmissões tem o próprio parâmetro: ${fake.bodies[0]}")
    }

    @Test
    fun sem_as_ordens_na_aba_avisa_em_vez_de_mostrar_a_lista_errada() = runBlocking {
        val fake = Fake { """{"contents":[${video("t1", "Sem menu")}]}""" }
        val failure = assertFailsWith<IllegalStateException> {
            YouTubeSource(InnerTube(fake.client)).channelVideos("UC123", ChannelTab.Videos, sort = ChannelSort.Popular)
        }
        assertTrue("mais vistos" in failure.message.orEmpty(), failure.message)
    }

    // ------------------------------------------------------------------ o cartão da lista ordenada

    private fun lockup(id: String, vararg rows: List<String>) =
        """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"$id","metadata":{"lockupMetadataViewModel":{"title":{"content":"Título $id"},"metadata":{"contentMetadataViewModel":{"metadataRows":[""" +
            rows.joinToString(",") { row -> """{"metadataParts":[""" + row.joinToString(",") { """{"text":{"content":"$it"}}""" } + "]}" } + "]}}}}}}"

    @Test
    fun o_cartao_sem_linha_de_canal_mostra_visualizacoes_e_data() = runBlocking {
        // Dentro da página do canal o YouTube não repete o nome do canal: a primeira linha já traz "visualizações • há 7 anos".
        val fake = Fake { """{"contents":[${lockup("a1", listOf("23 mi de visualizações", "há 7 anos"))},${lockup("b1", listOf("Canal Tal"), listOf("1,2 mil visualizações", "há 2 dias"))}]}""" }
        val items = YouTubeSource(InnerTube(fake.client)).channelVideos("UC123", ChannelTab.Videos).items.associateBy { it.id }
        assertEquals(23_000_000L, items.getValue("a1").viewCount)
        assertTrue(items.getValue("a1").publishedAt != null, "a data também aparece")
        assertEquals(1_200L, items.getValue("b1").viewCount, "com a linha do canal continua como antes")
    }

    // ------------------------------------------------------------------ o estado do canal

    @Test
    fun cada_ordem_tem_a_sua_lista_e_voltar_a_uma_ordem_reaproveita() {
        val (container, _) = newTestApp()
        val st = container.screens.channel(Channel(Platform.YouTube, "UC123", "Canal"))
        assertTrue(st.videosBy(ChannelSort.Recent) === st.videos)
        assertTrue(st.videosBy(ChannelSort.Popular) !== st.videos)
        assertTrue(st.videosBy(ChannelSort.Popular) === st.videosBy(ChannelSort.Popular))
        assertTrue(st.videosBy(ChannelSort.Popular) !== st.videosBy(ChannelSort.Oldest))
        assertTrue(st.pastLivesBy(ChannelSort.Recent) === st.pastLives)
        assertTrue(st.pastLivesBy(ChannelSort.Oldest) !== st.pastLives)
        assertTrue(st.vodsBy(ChannelSort.Popular) !== st.vods)
    }

    // ------------------------------------------------------------------ na tela

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun a_aba_de_videos_tem_as_ordens_e_escolher_uma_carrega_a_lista_dela() = runComposeUiTest {
        val fake = Fake { body ->
            when {
                "\"continuation\":\"TOK_POP\"" in body -> """{"contents":[${video("p1", "O mais visto de todos")}]}"""
                "\"params\"" in body -> tabWithMenu("TOK_RECENT", "TOK_POP", "TOK_OLD")
                else -> """{"contents":[${video("r1", "O mais recente")}]}"""
            }
        }
        val (container, _) = newTestApp(fake.client)
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1200.dp, 800.dp)) { ChannelScreen(Channel(Platform.YouTube, "UC123", "Canal"), initialTab = 0) }
            }
        }
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("O mais recente").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Mais recentes").assertIsDisplayed()
        onNodeWithText("Mais antigos").assertIsDisplayed()

        onNodeWithText("Mais vistos").performClick()
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("O mais visto de todos").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ChannelSort.Popular, container.screens.channel(Channel(Platform.YouTube, "UC123", "Canal")).videoSort)
        assertEquals(0, onAllNodesWithText("O mais recente").fetchSemanticsNodes().size, "a lista da outra ordem sai")
    }
}
