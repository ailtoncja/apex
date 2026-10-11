package app.apex

import app.apex.chat.YouTubeChat
import app.apex.source.InnerTube
import app.apex.source.PostResult
import app.apex.source.YouTubeSource
import app.apex.source.parseJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Escrever no chat de uma live do YouTube: achar o código de envio, mandar o texto e mostrar a mensagem sem repetir. */
class YouTubeChatSendTest {
    // ------------------------------------------------------------------ o que o YouTube devolve

    private val open = """{"actionPanel":{"liveChatMessageInputRenderer":{"sendButton":{"buttonRenderer":{"serviceEndpoint":{"sendLiveChatMessageEndpoint":{"params":"PARAMS-DO-CHAT"}}}}}}}"""
    private val restricted = """{"actionPanel":{"liveChatRestrictedParticipationRenderer":{"message":{"runs":[{"text":"Modo exclusivo "},{"text":"para inscritos"}]}}}}"""
    private val source = YouTubeSource(InnerTube())

    @Test
    fun acha_o_codigo_de_envio_so_quando_o_chat_esta_aberto() {
        assertEquals("PARAMS-DO-CHAT", source.chatSendParams(parseJson(open)!!))
        assertNull(source.chatSendParams(parseJson(restricted)!!))
        assertNull(source.chatRestriction(parseJson(open)!!))
        assertEquals("Modo exclusivo para inscritos", source.chatRestriction(parseJson(restricted)!!), "o aviso do próprio YouTube")
    }

    private fun message(id: String, text: String) =
        """{"liveChatTextMessageRenderer":{"id":"$id","authorName":{"simpleText":"Eu"},"message":{"runs":[{"text":"$text"}]}}}"""

    @Test
    fun a_resposta_do_envio_vira_publicada_recusada_ou_duvidosa() {
        val ok = source.classifyChatSend(parseJson("""{"actions":[{"addChatItemAction":{"item":${message("m1", "oi")}}}]}""")!!)
        assertEquals(PostResult.Posted, ok.result)
        assertEquals("m1", ok.message?.id)
        assertEquals("oi", ok.message?.text)

        val refused = source.classifyChatSend(parseJson("""{"error":{"code":403,"message":"Sem permissão."}}""")!!)
        assertEquals(PostResult.Rejected, refused.result)
        assertEquals("Sem permissão.", refused.reason)

        val unclear = source.classifyChatSend(parseJson("""{"actions":[]}""")!!)
        assertEquals(PostResult.Unclear, unclear.result)
        assertNotNull(unclear.reason)
    }

    @Test
    fun sem_conta_nao_manda_nada() = runBlocking {
        val sent = source.sendChatMessage("PARAMS", "oi")
        assertEquals(PostResult.Rejected, sent.result)
    }

    // ------------------------------------------------------------------ o chat de ponta a ponta (YouTube de mentira)

    private class FakeYouTube(val chatPanel: String, val sendResponse: String) {
        val sends = mutableListOf<HttpRequestData>()
        val sendBodies = mutableListOf<String>()
        private var polls = 0

        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path.endsWith("/next") -> respond(
                    """{"contents":{"liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"C0"}}]}}}""", HttpStatusCode.OK, json,
                )
                path.endsWith("/live_chat/get_live_chat") -> {
                    polls++
                    // a segunda consulta já traz a mensagem que a pessoa mandou (o YouTube a devolve também pelo chat)
                    val actions = if (polls >= 2) """[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"m1","authorName":{"simpleText":"Eu"},"message":{"runs":[{"text":"bom dia"}]}}}}}]""" else "[]"
                    respond(
                        """{"continuationContents":{"liveChatContinuation":{"actions":$actions,${chatPanel.trim().removePrefix("{").removeSuffix("}")},"continuations":[{"timedContinuationData":{"continuation":"C$polls","timeoutMs":1500}}]}}}""",
                        HttpStatusCode.OK, json,
                    )
                }
                path.endsWith("/live_chat/send_message") -> {
                    sends += request
                    sendBodies += (request.body as? TextContent)?.text.orEmpty()
                    respond(sendResponse, HttpStatusCode.OK, json)
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })
    }

    private fun chat(fake: FakeYouTube, scope: CoroutineScope, loggedIn: Boolean = true): YouTubeChat {
        val tube = InnerTube(fake.client)
        if (loggedIn) tube.cookieHeader = "SAPISID=abc; __Secure-3PAPISID=abc"
        return YouTubeChat(scope, "VIDEO", YouTubeSource(tube))
    }

    private fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "demorou demais" }; Thread.sleep(20) }
    }

    @Test
    fun escreve_manda_o_texto_certo_e_a_mensagem_nao_aparece_duas_vezes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeYouTube(open, """{"actions":[{"addChatItemAction":{"item":${message("m1", "bom dia")}}}]}""")
        val chat = chat(fake, scope)
        chat.start()
        waitFor { chat.canSend }
        assertTrue(chat.send("bom dia"))
        assertNull(chat.lastSendError)

        val body = fake.sendBodies.single()
        assertTrue("\"params\":\"PARAMS-DO-CHAT\"" in body, "usa o código do chat: $body")
        assertTrue("\"text\":\"bom dia\"" in body && "richMessage" in body && "textSegments" in body, "manda o texto: $body")
        assertTrue("clientMessageId" in body)
        assertEquals(listOf("m1"), chat.messages.value.map { it.id }, "aparece já, pela resposta do envio")

        // a consulta seguinte do chat devolve a mesma mensagem: não pode duplicar
        waitFor(10_000) { fake.sends.isNotEmpty() }
        Thread.sleep(4_000)
        assertEquals(listOf("m1"), chat.messages.value.map { it.id }, "sem repetir")
        scope.cancel()
    }

    @Test
    fun chat_limitado_mostra_o_aviso_do_youtube_e_nao_deixa_escrever() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeYouTube(restricted, "{}")
        val chat = chat(fake, scope)
        chat.start()
        waitFor { chat.sendHint == "Modo exclusivo para inscritos" }
        assertFalse(chat.canSend)
        assertFalse(chat.send("oi"))
        assertEquals("Modo exclusivo para inscritos", chat.lastSendError)
        assertTrue(fake.sends.isEmpty(), "nem chega a falar com o YouTube")
        scope.cancel()
    }

    @Test
    fun sem_conta_o_campo_pede_para_entrar() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = chat(FakeYouTube(open, "{}"), scope, loggedIn = false)
        chat.start()
        Thread.sleep(500)
        assertFalse(chat.canSend)
        assertEquals("Entre na conta para escrever no chat", chat.sendHint)
        scope.cancel()
    }

    @Test
    fun se_o_youtube_nao_confirmar_o_motivo_volta_para_a_pessoa() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = chat(FakeYouTube(open, """{"actions":[]}"""), scope)
        chat.start()
        waitFor { chat.canSend }
        assertFalse(chat.send("oi"))
        assertTrue(chat.lastSendError!!.contains("não confirmou"))
        assertTrue(chat.messages.value.none { it.text == "oi" }, "não inventa a mensagem")
        scope.cancel()
    }
}
