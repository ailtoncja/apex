package app.apex

import app.apex.chat.ChatClient
import app.apex.chat.KickChat
import app.apex.chat.TwitchChat
import app.apex.model.ChatMessage
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import app.apex.source.get
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ao entrar no chat de uma live, as mensagens de antes já aparecem (Twitch com a conta, Kick sem), uma vez só, sem repetir as que chegam ao vivo. */
class ChatHistoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    // ------------------------------------------------------------------ Twitch

    private val twitchHistory = """{"data":{"channel":{"id":"1","recentChatMessages":[
        {"id":"b","sentAt":"2026-10-10T01:38:30.5Z","deletedAt":null,"content":{"text":"segunda"},"sender":{"login":"ana","displayName":"Ana","chatColor":"#FF4500"},"senderBadges":[{"setID":"subscriber","version":"12"},{"setID":"premium","version":"1"}]},
        {"id":"a","sentAt":"2026-10-10T01:38:10.1Z","deletedAt":null,"content":{"text":"  primeira  "},"sender":{"login":"beto","displayName":"Beto","chatColor":null},"senderBadges":[]},
        {"id":"x","sentAt":"2026-10-10T01:38:20.0Z","deletedAt":"2026-10-10T01:38:25.0Z","content":{"text":"apagada"},"sender":{"login":"c","displayName":"C","chatColor":null},"senderBadges":[]},
        {"id":"y","sentAt":"2026-10-10T01:38:21.0Z","deletedAt":null,"content":{"text":"   "},"sender":{"login":"d","displayName":"D","chatColor":null},"senderBadges":[]},
        {"sentAt":"2026-10-10T01:38:22.0Z","deletedAt":null,"content":{"text":"sem id"},"sender":{"login":"e","displayName":"E","chatColor":null},"senderBadges":[]},
        {"id":"c","sentAt":"2026-10-10T01:38:40.0Z","deletedAt":null,"content":{"text":"terceira"},"sender":{"login":"dani","displayName":null,"chatColor":"#00FF7F"},"senderBadges":[]}
    ]}}}"""

    @Test
    fun as_mensagens_da_twitch_vem_em_ordem_sem_as_apagadas_com_cor_e_selos() {
        val list = TwitchSource().parseRecentChat(parseJson(twitchHistory)!!["data"])
        assertEquals(listOf("a", "b", "c"), list.map { it.id }, "da mais antiga para a mais nova; apagada, vazia e sem id ficam de fora")
        assertEquals(listOf("Beto", "Ana", "dani"), list.map { it.author }, "sem nome de exibição vale o login")
        assertEquals("primeira", list[0].text, "sem espaços nas pontas")
        assertEquals(0xFFFF4500, list[1].color, "a cor que a pessoa escolheu na Twitch")
        assertEquals(listOf("subscriber", "premium"), list[1].badges)
        assertTrue(list[0].color != null, "sem cor escolhida, uma cor fixa pelo nome")
    }

    @Test
    fun a_twitch_so_entrega_o_historico_com_a_conta_e_o_app_nem_pergunta_sem_ela() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(twitchHistory, HttpStatusCode.OK, json)
        })
        val twitch = TwitchSource(client)
        assertTrue(twitch.recentChatMessages("canal").isEmpty())
        assertTrue(requests.isEmpty(), "sem conta a Twitch responde 'unauthenticated': não vale a viagem")

        twitch.authToken = "token"
        assertEquals(listOf("a", "b", "c"), twitch.recentChatMessages("canal").map { it.id })
        val request = requests.single()
        assertEquals("OAuth token", request.headers[HttpHeaders.Authorization])
        val body = (request.body as TextContent).text
        assertTrue("recentChatMessages" in body && "\\\"canal\\\"" in body, body)
    }

    // ------------------------------------------------------------------ Kick

    private val kickChannel = """{"id":5,"slug":"canal","user":{"username":"Canal"},"chatroom":{"id":4242}}"""
    private val kickMessages = """{"status":{"code":200},"data":{"messages":[
        {"id":"m3","content":"a mais nova [emote:1:KEKW]","type":"message","created_at":"2026-10-10T01:37:46+00:00","sender":{"username":"zed","identity":{"color":"#75FD7F","badges":[{"type":"moderator"}]}}},
        {"id":"m2","content":"   ","type":"message","created_at":"2026-10-10T01:37:40+00:00","sender":{"username":"vazio","identity":{"color":null,"badges":[]}}},
        {"id":"m1","content":"a mais antiga","type":"message","created_at":"2026-10-10T01:37:37+00:00","sender":{"username":"ana","identity":{"color":null,"badges":[]}}}
    ],"cursor":"123","pinned_message":null}}"""

    private class KickFake(val channel: String, val messages: String) {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            urls += url
            when {
                url.endsWith("/messages") -> respond(messages, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }

    @Test
    fun as_mensagens_da_kick_vem_da_mais_antiga_para_a_mais_nova_pelo_numero_do_canal() = runBlocking {
        val fake = KickFake(kickChannel, kickMessages)
        val list = KickSource(fake.client).recentChat("canal")
        assertEquals(listOf("m1", "m3"), list.map { it.id }, "a Kick manda a mais nova primeiro; a vazia fica de fora")
        assertEquals("a mais nova KEKW", list[1].text, "figurinha vira só o nome")
        assertEquals(listOf("moderator"), list[1].badges)
        assertEquals(0xFF75FD7F, list[1].color)
        assertEquals(listOf("https://kick.com/api/v2/channels/canal", "https://kick.com/api/v2/channels/5/messages"), fake.urls)
    }

    // ------------------------------------------------------------------ o chat

    private class Probe(scope: CoroutineScope) : ChatClient(scope) {
        override val canSend = false
        override suspend fun connect() {}
        override suspend fun send(text: String) = false
        fun show(m: ChatMessage) = push(m)
        fun id(prefix: String) = localId(prefix)
    }

    @Test
    fun uma_mensagem_que_ja_apareceu_nao_aparece_de_novo_e_ids_locais_nao_se_repetem() {
        val chat = Probe(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertTrue(chat.show(ChatMessage("1", "ana", "oi")))
        assertFalse(chat.show(ChatMessage("1", "ana", "oi")), "a mesma mensagem, vinda do histórico e do chat ao vivo, aparece uma vez")
        assertEquals(1, chat.messages.value.size)
        repeat(400) { chat.show(ChatMessage(chat.id("x"), "a", "b")) }
        assertEquals(300, chat.messages.value.size, "o chat guarda as últimas 300")
        assertTrue(chat.id("me") != chat.id("me"))
    }

    @Test
    fun o_chat_da_twitch_mostra_o_historico_antes_de_conectar_e_so_o_busca_uma_vez() = runBlocking {
        val calls = AtomicInteger()
        val history = listOf(ChatMessage("h1", "ana", "antes 1"), ChatMessage("h2", "beto", "antes 2"))
        // Sem o plugin de WebSocket, a conexão ao chat ao vivo falha na hora e o chat fica tentando de novo.
        val chat = TwitchChat(
            CoroutineScope(SupervisorJob() + Dispatchers.Default), "canal", HttpClient(MockEngine { respond("") }),
            token = { null }, accountLogin = { null }, history = { calls.incrementAndGet(); history },
        )
        chat.start()
        withTimeout(3_000) { while (chat.messages.value.size < 2) delay(10) }
        assertEquals(listOf("h1", "h2"), chat.messages.value.map { it.id })
        delay(2_600) // já passou pela primeira reconexão (2 s)
        assertTrue(chat.status.value.contains("Tentando de novo") || chat.status.value.contains("Reconectando"), chat.status.value)
        assertEquals(1, calls.get(), "numa reconexão o histórico não é buscado de novo (as mensagens já estão na tela)")
        assertEquals(listOf("h1", "h2"), chat.messages.value.map { it.id })
        chat.stop()
    }

    @Test
    fun o_chat_da_kick_mostra_o_historico_do_canal_antes_de_conectar() = runBlocking {
        val fake = KickFake(kickChannel, kickMessages)
        val chat = KickChat(CoroutineScope(SupervisorJob() + Dispatchers.Default), "canal", KickSource(fake.client), fake.client)
        chat.start()
        withTimeout(3_000) { while (chat.messages.value.size < 2) delay(10) }
        assertEquals(listOf("m1", "m3"), chat.messages.value.map { it.id })
        delay(2_600)
        assertEquals(1, fake.urls.count { it.endsWith("/messages") }, "o histórico vem uma vez; as reconexões não o repetem")
        chat.stop()
    }
}
