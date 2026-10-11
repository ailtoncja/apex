package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.data.Account
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.Route
import app.apex.source.CommentPoster
import app.apex.source.InnerTube
import app.apex.source.PostResult
import app.apex.source.YouTubeSource
import app.apex.source.parseJson
import app.apex.state.CommentBox
import app.apex.ui.watch.CommentComposer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comentar nos vídeos do YouTube: achar os códigos que a página dá, publicar e mostrar o comentário novo. */
class CommentPostTest {
    private val source = YouTubeSource(InnerTube())

    // ------------------------------------------------------------------ o que o YouTube devolve

    @Test
    fun acha_a_continuacao_da_secao_de_comentarios_e_ignora_as_outras_secoes() {
        val watch = parseJson(
            """{"contents":{"results":[
              {"itemSectionRenderer":{"sectionIdentifier":"sid-wn-chips","contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"RELACIONADOS"}}}}]}},
              {"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMENTARIOS"}}}}]}}
            ]}}""",
        )!!
        assertEquals("COMENTARIOS", source.commentsContinuation(watch))
        assertNull(source.commentsContinuation(parseJson("""{"contents":{}}""")!!), "vídeo sem seção de comentários")
    }

    @Test
    fun acha_o_codigo_de_criar_comentario_no_cabecalho() {
        val header = parseJson(
            """{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER","continuationItems":[
              {"commentsHeaderRenderer":{"createRenderer":{"commentSimpleboxRenderer":{"submitButton":{"buttonRenderer":{"serviceEndpoint":
                {"createCommentEndpoint":{"createCommentParams":"EgtkUXc0dzlXZ1hjUSoCCAB"}}}}}}}}]}}]}""",
        )!!
        assertEquals("EgtkUXc0dzlXZ1hjUSoCCAB", source.createCommentParams(header))
        assertNull(source.createCommentParams(parseJson("""{"onResponseReceivedEndpoints":[]}""")!!), "comentários desligados ou sem conta")
    }

    @Test
    fun so_considera_publicado_quando_o_youtube_confirma_e_na_duvida_nao_manda_de_novo() {
        fun result(json: String) = source.classifyPostResponse(parseJson(json)!!)
        assertEquals(PostResult.Posted, result("""{"actions":[{"createCommentAction":{"contents":{"commentThreadRenderer":{}}}}]}"""))
        assertEquals(PostResult.Posted, result("""{"actionResult":{"status":"STATUS_SUCCEEDED"}}"""))
        assertEquals(PostResult.Rejected, result("""{"error":{"code":400,"message":"Request contains an invalid argument."}}"""))
        assertEquals(PostResult.Rejected, result("""{"actionResult":{"status":"STATUS_FAILED"}}"""))
        assertEquals(PostResult.Unclear, result("""{"actions":[]}"""), "resposta sem sinal de sucesso nem de erro")
        assertEquals(PostResult.Unclear, result("""{"responseContext":{}}"""))
    }

    @Test
    fun sem_conta_nao_pede_nem_publica_nada() = runBlocking {
        // um InnerTube sem cookies: nem chega a falar com a rede
        assertNull(source.commentParams("dQw4w9WgXcQ"))
        assertEquals(PostResult.Rejected, source.postComment("params", "oi"))
    }

    // ------------------------------------------------------------------ o campo de comentar

    private class FakePoster(var params: String? = "PARAMS", var result: PostResult = PostResult.Posted) : CommentPoster {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun commentParams(videoId: String) = params
        override suspend fun postComment(params: String, text: String): PostResult { sent += params to text; return result }
    }

    /** Espera (fora da tela) o campo de comentar chegar a um estado: ele é preparado em segundo plano. Assim a primeira composição já o vê pronto. */
    private fun awaitBox(container: AppContainer, timeoutMs: Long = 5_000, condition: (CommentBox) -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition(container.screens.watch.commentBox)) {
            check(System.currentTimeMillis() < end) { "o campo de comentar não chegou ao estado esperado: ${container.screens.watch.commentBox}" }
            Thread.sleep(10)
        }
    }

    private val video = Media(Platform.YouTube, "dQw4w9WgXcQ", "Vídeo", url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")

    private fun AppContainer.signedIn() = data.setAccount(Account(Platform.YouTube, "Ailton", null, "SAPISID=x"), Platform.YouTube)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun escrever_e_comentar_publica_limpa_o_campo_e_poe_o_comentario_no_topo() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.signedIn()
        val poster = FakePoster()
        container.screens.watch.poster = poster
        container.screens.watch.startFor(video)
        awaitBox(container) { it is CommentBox.Ready }
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(800.dp, 400.dp)) { CommentComposer(video) } } }

        onNodeWithTag("comment-input").performClick()
        onNodeWithTag("comment-input").performTextInput("  Que clássico!  ")
        waitForIdle()
        onNodeWithTag("comment-send").performClick()
        waitUntil(timeoutMillis = 5_000) { poster.sent.isNotEmpty() }

        assertEquals(listOf("PARAMS" to "Que clássico!"), poster.sent, "manda o texto sem os espaços das pontas")
        waitUntil(timeoutMillis = 5_000) { container.screens.watch.comments.value.isNotEmpty() }
        val mine = container.screens.watch.comments.value.first()
        assertEquals("Que clássico!", mine.text)
        assertEquals("Ailton", mine.author)
        assertEquals("Comentário publicado", container.ui.toast)
        waitForIdle()
        assertEquals(0, onAllNodesWithText("Comentar").fetchSemanticsNodes().size, "o campo limpou e os botões sumiram")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun se_o_youtube_recusar_o_texto_fica_no_campo_e_nada_aparece() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.signedIn()
        val poster = FakePoster(result = PostResult.Rejected)
        container.screens.watch.poster = poster
        container.screens.watch.startFor(video)
        awaitBox(container) { it is CommentBox.Ready }
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(800.dp, 400.dp)) { CommentComposer(video) } } }

        onNodeWithTag("comment-input").performClick()
        onNodeWithTag("comment-input").performTextInput("meu texto")
        waitForIdle()
        onNodeWithTag("comment-send").performClick()
        waitUntil(timeoutMillis = 5_000) { poster.sent.isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { container.ui.toast != null }

        assertEquals("Não foi possível publicar o comentário", container.ui.toast)
        assertTrue(container.screens.watch.comments.value.isEmpty())
        assertFalse(container.screens.watch.posting)
        waitForIdle()
        assertEquals(1, onAllNodesWithText("meu texto").fetchSemanticsNodes().size, "o texto continua no campo")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun resposta_duvidosa_avisa_para_conferir_e_mantem_o_texto() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.signedIn()
        val poster = FakePoster(result = PostResult.Unclear)
        container.screens.watch.poster = poster
        container.screens.watch.startFor(video)
        awaitBox(container) { it is CommentBox.Ready }
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(800.dp, 400.dp)) { CommentComposer(video) } } }
        onNodeWithTag("comment-input").performClick()
        onNodeWithTag("comment-input").performTextInput("talvez publicou")
        waitForIdle()
        onNodeWithTag("comment-send").performClick()
        waitUntil(timeoutMillis = 5_000) { container.ui.toast != null }
        assertEquals("O YouTube não confirmou. Confira nos comentários antes de tentar de novo", container.ui.toast)
        assertTrue(container.screens.watch.comments.value.isEmpty(), "não inventa um comentário que talvez não exista")
        waitForIdle()
        assertEquals(1, onAllNodesWithText("talvez publicou").fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun comentario_em_branco_nao_e_enviado() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.signedIn()
        val poster = FakePoster()
        container.screens.watch.poster = poster
        container.screens.watch.startFor(video)
        awaitBox(container) { it is CommentBox.Ready }
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(800.dp, 400.dp)) { CommentComposer(video) } } }
        onNodeWithTag("comment-input").performClick()
        onNodeWithTag("comment-input").performTextInput("    ")
        waitForIdle()
        onNodeWithTag("comment-send").performClick()
        waitForIdle()
        assertTrue(poster.sent.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sem_conta_mostra_o_convite_e_o_botao_leva_aos_ajustes() = runComposeUiTest {
        val (container, _) = newTestApp()
        container.screens.watch.startFor(video)
        assertEquals(CommentBox.LoggedOut, container.screens.watch.commentBox)
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(800.dp, 400.dp)) { CommentComposer(video) } } }
        onNodeWithText("Entre na sua conta do YouTube para comentar.").assertExists()
        onNodeWithText("Entrar").performClick()
        assertEquals(Route.Settings, container.nav.current)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun comentarios_desligados_mostram_o_aviso_e_entrar_na_conta_com_o_video_aberto_libera_o_campo() = runComposeUiTest {
        val (container, _) = newTestApp()
        val poster = FakePoster(params = null)
        container.screens.watch.poster = poster
        container.screens.watch.startFor(video)
        setContent { CompositionLocalProvider(LocalApp provides container) { Box(Modifier.requiredSize(800.dp, 400.dp)) { CommentComposer(video) } } }
        waitForIdle()
        assertEquals(CommentBox.LoggedOut, container.screens.watch.commentBox)

        // entrou na conta com o vídeo aberto, mas o vídeo não aceita comentários
        container.signedIn()
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); container.screens.watch.commentBox == CommentBox.Disabled }
        val aviso = "Não é possível comentar neste vídeo (os comentários estão desativados)."
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithText(aviso).fetchSemanticsNodes().isNotEmpty() }

        // o YouTube passa a devolver o código: ao reabrir o vídeo o campo aparece
        poster.params = "NOVO"
        container.screens.watch.startFor(video)
        waitUntil(timeoutMillis = 10_000) { Snapshot.sendApplyNotifications(); onAllNodesWithTag("comment-input").fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(container.screens.watch.commentBox)
    }

    @Test
    fun lives_e_outras_plataformas_nao_tem_campo_de_comentar() {
        val (container, _) = newTestApp()
        container.signedIn()
        container.screens.watch.startFor(video.copy(isLive = true))
        assertEquals(CommentBox.Disabled, container.screens.watch.commentBox)
        container.screens.watch.startFor(Media(Platform.Twitch, "vod-1", "VOD", isLive = false, url = "https://www.twitch.tv/videos/1"))
        assertEquals(CommentBox.Disabled, container.screens.watch.commentBox)
    }
}
