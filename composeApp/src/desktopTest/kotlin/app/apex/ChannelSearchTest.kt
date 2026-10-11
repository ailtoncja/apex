package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.model.Channel
import app.apex.model.Platform
import app.apex.source.InnerTube
import app.apex.source.YouTubeSource
import app.apex.state.Page
import app.apex.state.Paged
import app.apex.ui.channel.ChannelScreen
import app.apex.ui.library.RemotePlaylistScreen
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
import kotlin.test.assertTrue

/** Pesquisar dentro do canal (vídeos no YouTube inteiro, transmissões e playlists pelo nome) e dentro de uma playlist. */
class ChannelSearchTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun video(id: String, title: String) = """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"Canal","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}]}}}"""
    private fun playlist(id: String, title: String) = """{"playlistRenderer":{"playlistId":"$id","title":{"simpleText":"$title"},"videoCountText":{"runs":[{"text":"12"}]}}}"""

    /** O YouTube de mentira: responde ao `browse` conforme o que foi pedido. */
    private class Fake(val reply: (body: String) -> String) {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            bodies += body
            respond(reply(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    // ------------------------------------------------------------------ a busca do YouTube dentro do canal

    @Test
    fun a_busca_no_canal_pede_o_texto_ao_youtube_e_segue_pelas_paginas() = runBlocking {
        val fake = Fake { body ->
            if ("\"continuation\":\"PAG2\"" in body) """{"contents":[${video("v3", "Terceiro")}]}"""
            else """{"contents":[${video("v1", "Primeiro")},${video("v2", "Segundo")},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"PAG2"}}}}]}"""
        }
        val source = YouTubeSource(InnerTube(fake.client))
        val first = source.channelSearch("UC123", "texto procurado")
        assertEquals(listOf("v1", "v2"), first.items.map { it.id })
        assertEquals("PAG2", first.continuation)
        val body = fake.bodies.single()
        assertTrue("\"browseId\":\"UC123\"" in body && "\"query\":\"texto procurado\"" in body, "pede a busca do canal: $body")
        assertTrue("\"params\":\"EgZzZWFyY2jyBgQKAloA\"" in body, "com o parâmetro da aba de busca")

        val second = source.channelSearch("UC123", "texto procurado", first.continuation)
        assertEquals(listOf("v3"), second.items.map { it.id })
        assertEquals(null, second.continuation)
    }

    // ------------------------------------------------------------------ o estado do canal

    @Test
    fun a_busca_so_vai_ao_youtube_quando_a_pessoa_para_de_digitar_e_limpar_cancela() = runBlocking {
        val (container, _) = newTestApp()
        val st = container.screens.channel(Channel(Platform.YouTube, "UC123", "Canal"))
        st.updateQuery("ro")
        st.updateQuery("rock")
        assertEquals("rock", st.query)
        assertEquals("", st.searchedQuery, "ainda digitando: nada pesquisado")
        waitFor { st.searchedQuery == "rock" }
        assertEquals("rock", st.searchedQuery, "só o texto final foi pesquisado")

        st.updateQuery("   ")
        assertEquals("", st.searchedQuery, "apagar o texto volta à lista normal na hora")
        st.updateQuery("a")
        st.updateQuery("")
        Thread.sleep(700)
        assertEquals("", st.searchedQuery, "limpar antes do tempo cancela a busca")
    }

    @Test
    fun repetir_a_mesma_busca_reaproveita_a_lista() {
        val (container, _) = newTestApp()
        val st = container.screens.channel(Channel(Platform.YouTube, "UC999", "Outro"))
        assertTrue(st.searchPaged("rock") === st.searchPaged("rock"))
        assertTrue(st.searchPaged("rock") !== st.searchPaged("pop"))
    }

    @Test
    fun filtrar_uma_lista_carrega_todas_as_paginas_dela() = runBlocking {
        val (container, _) = newTestApp()
        val st = container.screens.channel(Channel(Platform.YouTube, "UC555", "Canal"))
        var pagesAsked = 0
        val paged = Paged<String>(container.scope) { token ->
            pagesAsked++
            val n = token?.toInt() ?: 1
            Page(listOf("item$n"), if (n < 5) (n + 1).toString() else null)
        }
        paged.loadIfNeeded()
        st.loadAll(paged)
        assertEquals(listOf("item1", "item2", "item3", "item4", "item5"), paged.items.value)
        assertEquals(5, pagesAsked)
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "demorou demais" }; Thread.sleep(20) }
    }

    // ------------------------------------------------------------------ na tela

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun na_aba_playlists_digitar_filtra_pelo_nome() = runComposeUiTest {
        val fake = Fake { """{"contents":[${playlist("PL1", "Rock clássico")},${playlist("PL2", "Pop anos 80")},${playlist("PL3", "Rock nacional")}]}""" }
        val (container, _) = newTestApp(fake.client)
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1200.dp, 800.dp)) { ChannelScreen(Channel(Platform.YouTube, "UC123", "Canal"), initialTab = 2) }
            }
        }
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("Pop anos 80").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodesWithText("Rock clássico").fetchSemanticsNodes().size)

        onNode(hasSetTextAction()).performTextInput("rock")
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("Pop anos 80").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, onAllNodesWithText("Rock clássico").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("Rock nacional").fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun na_aba_videos_digitar_mostra_o_que_o_youtube_achou_no_canal_todo() = runComposeUiTest {
        val fake = Fake { body ->
            if ("\"query\":\"achado\"" in body) """{"contents":[${video("s1", "Vídeo achado na busca")}]}"""
            else """{"contents":[${video("n1", "Vídeo comum da lista")}]}"""
        }
        val (container, _) = newTestApp(fake.client)
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1200.dp, 800.dp)) { ChannelScreen(Channel(Platform.YouTube, "UC123", "Canal"), initialTab = 0) }
            }
        }
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("Vídeo comum da lista").fetchSemanticsNodes().isNotEmpty() }

        onNode(hasSetTextAction()).performTextInput("achado")
        waitUntil(timeoutMillis = 15_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("Vídeo achado na busca").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodesWithText("Vídeo comum da lista").fetchSemanticsNodes().size, "a lista normal sai enquanto há busca")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dentro_de_uma_playlist_digitar_filtra_os_videos() = runComposeUiTest {
        val items = (1..8).joinToString(",") { """{"playlistVideoRenderer":{"videoId":"p$it","title":{"runs":[{"text":"${if (it % 2 == 0) "Aula de violão $it" else "Show ao vivo $it"}"}]},"shortBylineText":{"runs":[{"text":"Canal","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}]}}}""" }
        val fake = Fake { """{"contents":[$items]}""" }
        val (container, _) = newTestApp(fake.client)
        setContent {
            CompositionLocalProvider(LocalApp provides container) {
                Box(Modifier.requiredSize(1200.dp, 800.dp)) { RemotePlaylistScreen("PL1", "Minha playlist") }
            }
        }
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("Show ao vivo 1").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performTextInput("violão")
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText("Show ao vivo 1").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, onAllNodesWithText("Aula de violão 2").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("Aula de violão 8").fetchSemanticsNodes().size)
    }
}
