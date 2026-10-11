package app.apex

import app.apex.chat.KickChat
import app.apex.source.KickSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Escrever no chat da Kick: o pedido certo, o aviso certo para cada recusa, e o chat só deixa escrever com a conta. */
class KickChatSendTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fake(val status: HttpStatusCode, val body: String) {
        val posts = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts += request
                bodies += (request.body as? TextContent)?.text.orEmpty()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                // GET do canal: devolve só o que o app lê (apelido, nome e a sala do chat)
                respond("""{"slug":"canal","user":{"username":"Canal"},"chatroom":{"id":4242}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }

    private fun source(fake: Fake, token: String? = "123|segredo") = KickSource(fake.client).also { it.sessionToken = token }

    @Test
    fun envia_para_a_sala_certa_com_a_conta_e_o_texto() = runBlocking {
        val fake = Fake(HttpStatusCode.OK, """{"status":{"error":false,"code":200}}""")
        assertNull(source(fake).sendChat(4242, "olá, chat!"))
        val post = fake.posts.single()
        assertEquals("https://kick.com/api/v2/messages/send/4242", post.url.toString())
        assertEquals("Bearer 123|segredo", post.headers[HttpHeaders.Authorization])
        assertEquals("""{"content":"olá, chat!","type":"message"}""", fake.bodies.single())
    }

    @Test
    fun cada_recusa_vira_um_aviso_que_a_pessoa_entende() = runBlocking {
        suspend fun reason(code: Int, body: String = "{}") = source(Fake(HttpStatusCode.fromValue(code), body)).sendChat(1, "oi")
        assertTrue(reason(401)!!.contains("sessão"), "sessão vencida")
        assertTrue(reason(403)!!.contains("banido"), "banido ou só para seguidores")
        assertEquals("A Kick recusou: Você está em timeout.", reason(403, """{"message":"Você está em timeout."}"""))
        assertTrue(reason(429)!!.contains("devagar"), "modo lento")
        assertEquals("The given data was invalid.", reason(422, """{"message":"The given data was invalid."}"""))
        assertEquals("A Kick recusou a mensagem.", reason(500, "<html>erro</html>"))
    }

    @Test
    fun sem_conta_nem_chega_a_falar_com_a_kick() = runBlocking {
        val fake = Fake(HttpStatusCode.OK, "{}")
        val reason = source(fake, token = null).sendChat(1, "oi")
        assertNotNull(reason)
        assertTrue(fake.posts.isEmpty())
    }

    @Test
    fun o_chat_so_deixa_escrever_logado_e_manda_para_a_sala_do_canal() = runBlocking {
        val fake = Fake(HttpStatusCode.OK, "{}")
        val kick = source(fake, token = null)
        val chat = KickChat(CoroutineScope(SupervisorJob() + Dispatchers.Default), "canal", kick, fake.client)
        assertFalse(chat.canSend, "sem login não escreve")
        kick.sessionToken = "123|segredo"
        assertTrue(chat.canSend, "logou: pode escrever (o campo acompanha o login)")

        assertTrue(chat.send("bom dia"))
        assertNull(chat.lastSendError)
        assertEquals("https://kick.com/api/v2/messages/send/4242", fake.posts.single().url.toString())

        // a Kick recusa: o chat conta o motivo
        val refused = Fake(HttpStatusCode.TooManyRequests, "{}")
        val chat2 = KickChat(CoroutineScope(SupervisorJob() + Dispatchers.Default), "canal", source(refused), refused.client)
        assertFalse(chat2.send("de novo"))
        assertTrue(chat2.lastSendError!!.contains("devagar"))
    }
}
