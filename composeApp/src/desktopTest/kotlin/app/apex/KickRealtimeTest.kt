package app.apex

import app.apex.chat.parseKickChatMessage
import app.apex.source.KickRealtime
import app.apex.source.KickSource
import app.apex.source.get
import app.apex.source.isKickRealtimeUrl
import app.apex.source.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O chat ao vivo da Kick: em qual servidor ele está (Centrifugo ou Pusher) e as mensagens que chegam por ele. */
class KickRealtimeTest {
    private fun parse(json: String) = KickSource().parseRealtime(parseJson(json)!!["data"])

    @Test
    fun le_o_servidor_que_a_kick_indica() {
        assertEquals(
            KickRealtime.Centrifugo("wss://realtime.us-west-2.platform.kick.com/connection/websocket"),
            parse("""{"data":{"connections":[{"credentials":{"url":"wss://realtime.us-west-2.platform.kick.com/connection/websocket"},"provider":"centrifugo"}],"mode":"websocket"},"message":"success"}"""),
        )
        assertEquals(
            KickRealtime.Pusher("32cbd69e4b950bf97679", "us2"),
            parse("""{"data":{"connections":[{"credentials":{"app_key":"32cbd69e4b950bf97679","cluster":"us2"},"provider":"pusher"}],"mode":"websocket"}}"""),
        )
        // Servidor desconhecido primeiro: vale o próximo que o app sabe usar.
        assertEquals(
            KickRealtime.Centrifugo("wss://realtime.kick.com/connection/websocket"),
            parse("""{"data":{"connections":[{"provider":"ably","credentials":{}},{"provider":"centrifugo","credentials":{"url":"wss://realtime.kick.com/connection/websocket"}}],"mode":"websocket"}}"""),
        )
        assertNull(parse("""{"data":{"connections":[],"mode":"polling"}}"""), "sem websocket o app não tem como ouvir")
        assertNull(parse("""{"data":{"connections":[{"provider":"centrifugo","credentials":{"url":"wss://evil.example/kick.com/"}}],"mode":"websocket"}}"""), "só servidor da Kick")
    }

    @Test
    fun so_aceita_websocket_seguro_da_propria_kick() {
        assertTrue(isKickRealtimeUrl("wss://realtime.us-west-2.platform.kick.com/connection/websocket"))
        assertTrue(isKickRealtimeUrl("wss://kick.com:443/x"))
        assertFalse(isKickRealtimeUrl("ws://realtime.kick.com/connection/websocket"), "sem TLS não")
        assertFalse(isKickRealtimeUrl("wss://kick.com.evil.example/x"))
        assertFalse(isKickRealtimeUrl("wss://notkick.com/x"))
    }

    @Test
    fun a_mensagem_do_chat_ao_vivo_vira_texto_com_cor_selos_e_emotes() {
        val raw = """{"id":"f4a9","chatroom_id":66973867,"content":"boa [emote:37226:KEKW]","type":"message","created_at":"2026-10-22T16:39:50+00:00",""" +
            """"sender":{"id":1,"username":"pdkinha","slug":"pdkinha","identity":{"color":"#75FD7F","badges":[{"type":"subscriber","text":"Sub"}]}}}"""
        val m = parseKickChatMessage(raw)!!
        assertEquals("f4a9", m.id)
        assertEquals("pdkinha", m.author)
        assertEquals("boa KEKW", m.text)
        assertEquals(0xFF75FD7F, m.color)
        assertEquals(listOf("subscriber"), m.badges)
        assertTrue(m.emotes.isNotEmpty(), "o emote da Kick vira imagem")
        assertNull(parseKickChatMessage("""{"content":"sem autor"}"""))
        assertEquals("", parseKickChatMessage("""{"content":"oi","sender":{"username":"a"}}""")!!.id, "sem id: o chat põe um local")
    }
}
