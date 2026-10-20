package app.apex

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.apex.data.Account
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.InnerTube
import app.apex.source.SessionExpiredException
import app.apex.source.YouTubeSource
import app.apex.source.get
import app.apex.source.list
import app.apex.source.parseJson
import app.apex.source.str
import app.apex.ui.library.AddToPlaylistDialog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O diálogo "Salvar em…" com a conta do YouTube conectada: lista as playlists da conta (inclusive a que acabou de ser criada), marca e
 * desmarca de verdade na conta, volta atrás quando o YouTube recusa e avisa quando a lista não veio (em vez de mostrar "nenhuma playlist").
 */
@OptIn(ExperimentalTestApi::class)
class YouTubePlaylistDialogTest {
    /** A conta do YouTube de mentira. Como o YouTube de hoje, recusa o pedido da lista sem `videoIds` ("argumento inválido"). */
    private class FakeAccount {
        class Pl(val id: String, val title: String, val videos: MutableSet<String> = ConcurrentHashMap.newKeySet())

        val lists = CopyOnWriteArrayList(listOf(Pl("WL", "Assistir mais tarde"), Pl("PLfranca", "França").apply { videos += "vid1" }))
        val edits = CopyOnWriteArrayList<String>()
        @Volatile var expired = false
        @Volatile var refuse = emptySet<String>()

        private fun options(video: String) = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("addToPlaylistRenderer", buildJsonObject {
                        put("playlists", buildJsonArray {
                            lists.forEach { pl ->
                                add(buildJsonObject {
                                    put("playlistAddToOptionRenderer", buildJsonObject {
                                        put("playlistId", pl.id)
                                        put("title", buildJsonObject { put("simpleText", pl.title) })
                                        put("containsSelectedVideos", if (video in pl.videos) "ALL" else "NONE")
                                    })
                                })
                            }
                        })
                    })
                })
            })
        }.toString()

        val requests = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            requests += path.substringAfterLast('/')
            // Como a rede de verdade, a resposta leva um instante (a instantânea chegava antes do primeiro quadro do diálogo e o
            // ambiente de teste perdia a atualização; no app a resposta volta na thread da interface).
            kotlinx.coroutines.delay(150)
            val body = parseJson((request.body as? TextContent)?.text.orEmpty())
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            fun error(code: Int, status: String) =
                respond("""{"error":{"code":$code,"message":"recusado","status":"$status"}}""", HttpStatusCode.fromValue(code), json)
            when {
                expired && path.startsWith("/youtubei/") -> error(401, "UNAUTHENTICATED")
                path.endsWith("/playlist/get_add_to_playlist") -> {
                    val video = body["videoIds"].list().firstNotNullOfOrNull { it.str() }
                    if (video == null) error(400, "INVALID_ARGUMENT") else respond(options(video), HttpStatusCode.OK, json)
                }
                path.endsWith("/playlist/create") -> {
                    val pl = Pl("PLnova${lists.size}", body["title"].str()!!)
                    body["videoIds"].list().mapNotNull { it.str() }.forEach { pl.videos += it }
                    lists.add(1, pl) // a mais recente aparece logo depois do "Assistir mais tarde", como no YouTube
                    respond("""{"playlistId":"${pl.id}"}""", HttpStatusCode.OK, json)
                }
                path.endsWith("/browse/edit_playlist") -> {
                    val id = body["playlistId"].str()!!
                    val action = body["actions"][0]
                    if (id in refuse) error(403, "PERMISSION_DENIED")
                    else {
                        val pl = lists.first { it.id == id }
                        when (action["action"].str()) {
                            "ACTION_ADD_VIDEO" -> pl.videos += action["addedVideoId"].str()!!
                            "ACTION_REMOVE_VIDEO_BY_VIDEO_ID" -> pl.videos -= action["removedVideoId"].str()!!
                        }
                        edits += "${action["action"].str()} $id"
                        respond("""{"status":"STATUS_SUCCEEDED"}""", HttpStatusCode.OK, json)
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })
    }

    private fun video(id: String) = Media(Platform.YouTube, id, "Vídeo $id", url = "https://www.youtube.com/watch?v=$id")

    private fun loggedApp(fake: FakeAccount): AppContainer {
        current = fake
        val (app, _) = newTestApp(fake.client)
        app.data.setAccount(Account(Platform.YouTube, "Conta", credential = "SAPISID=abc; __Secure-3PAPISID=abc"), Platform.YouTube)
        val end = System.currentTimeMillis() + 5_000
        while (!app.tube.loggedIn) { check(System.currentTimeMillis() < end) { "a conta não entrou" }; Thread.sleep(10) }
        return app
    }

    private var current: FakeAccount? = null

    private fun androidx.compose.ui.test.ComposeUiTest.waitText(text: String) {
        try {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            val screen = onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().joinToString(" || ") { root ->
                fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                    listOfNotNull(n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString()) + n.children.flatMap { walk(it) }
                walk(root).joinToString(" | ")
            }
            throw AssertionError("\"$text\" não apareceu; na tela: $screen; pedidos: ${current?.requests}", e)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.box(playlistId: String) =
        onNode(isToggleable() and hasAnyAncestor(hasTestTag("yt-playlist-$playlistId")))

    @Test
    fun a_playlist_criada_no_youtube_aparece_quando_o_dialogo_abre_de_novo() = runComposeUiTest {
        val fake = FakeAccount()
        val app = loggedApp(fake)
        var target by mutableStateOf<Media?>(video("vid2"))
        setContent { CompositionLocalProvider(LocalApp provides app) { target?.let { AddToPlaylistDialog(it) { target = null } } } }

        waitText("França")
        onNodeWithText("Assistir mais tarde").assertExists()
        box("PLfranca").assertIsOff()

        onNodeWithText("Nova playlist no YouTube").performClick()
        onNode(hasSetTextAction()).performTextInput("Viagem")
        onNodeWithText("Salvar").performClick()
        waitUntil(timeoutMillis = 5_000) { fake.lists.any { it.title == "Viagem" } && app.ui.toast == "Salvo em “Viagem” no YouTube" }
        assertNull(target, "o diálogo fecha depois de criar")
        assertEquals(setOf("vid2"), fake.lists.first { it.title == "Viagem" }.videos.toSet(), "a playlist nasce com o vídeo")

        // O caso que falhava: abrir "Salvar em…" de novo e a playlist nova não estar lá.
        target = video("vid3")
        waitText("Viagem")
        onNodeWithText("França").assertExists()
        target = null
        waitForIdle()
        target = video("vid2")
        waitText("Viagem")
        val novaId = fake.lists.first { it.title == "Viagem" }.id
        waitUntil(timeoutMillis = 5_000) { runCatching { box(novaId).assertIsOn() }.isSuccess }
    }

    @Test
    fun marcar_e_desmarcar_vao_para_a_conta_e_recusa_volta_atras() = runComposeUiTest {
        val fake = FakeAccount()
        val app = loggedApp(fake)
        setContent { CompositionLocalProvider(LocalApp provides app) { AddToPlaylistDialog(video("vid1")) {} } }
        waitText("França")
        box("PLfranca").assertIsOn()

        onNodeWithTag("yt-playlist-PLfranca").performClick()
        waitUntil(timeoutMillis = 5_000) { "ACTION_REMOVE_VIDEO_BY_VIDEO_ID PLfranca" in fake.edits }
        waitUntil(timeoutMillis = 5_000) { app.ui.toast == "Removido de “França” no YouTube" }
        box("PLfranca").assertIsOff()
        assertFalse("vid1" in fake.lists.first { it.id == "PLfranca" }.videos)

        fake.refuse = setOf("WL")
        onNodeWithTag("yt-playlist-WL").performClick()
        waitUntil(timeoutMillis = 5_000) { app.ui.toast == "O YouTube não aceitou a mudança em “Assistir mais tarde”" }
        waitForIdle()
        box("WL").assertIsOff()
        assertTrue(fake.lists.first { it.id == "WL" }.videos.isEmpty())
    }

    @Test
    fun lista_que_nao_veio_avisa_e_tenta_de_novo() = runComposeUiTest {
        val fake = FakeAccount()
        val app = loggedApp(fake)
        fake.expired = true
        setContent { CompositionLocalProvider(LocalApp provides app) { AddToPlaylistDialog(video("vid1")) {} } }
        waitText("Sua sessão do YouTube expirou. Saia da conta e entre de novo.")
        onNodeWithText("França").assertDoesNotExist()

        fake.expired = false
        onNodeWithText("Tentar de novo").performClick()
        waitText("França")
        onNodeWithText("Tentar de novo").assertDoesNotExist()
    }

    @Test
    fun respostas_de_erro_do_youtube_nao_contam_como_feito() = runBlocking {
        val fake = FakeAccount()
        val yt = YouTubeSource(InnerTube(fake.client).apply { cookieHeader = "SAPISID=abc; __Secure-3PAPISID=abc" })
        assertEquals(listOf("WL", "PLfranca"), yt.playlistOptions("vid1").map { it.id })
        fake.refuse = setOf("PLfranca")
        assertFalse(yt.editPlaylist("PLfranca", "vid9", true), "403 não é sucesso")
        fake.expired = true
        assertFailsWith<SessionExpiredException> { yt.playlistOptions("vid1") }
        assertFailsWith<SessionExpiredException> { yt.createPlaylist("X", "vid1") }
        assertFailsWith<SessionExpiredException> { yt.rate("vid1", 1) }
        Unit
    }
}
