package app.apex

import app.apex.chat.ChatClient
import app.apex.chat.LinkedChat
import app.apex.chat.UnifiedChat
import app.apex.chat.channelKey
import app.apex.chat.findLinkedChats
import app.apex.chat.linkChannels
import app.apex.chat.liveChannelKey
import app.apex.chat.rejectedPair
import app.apex.chat.sameNameCandidates
import app.apex.chat.unlinkChannel
import app.apex.model.Channel
import app.apex.model.ChatMessage
import app.apex.model.Media
import app.apex.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O chat unificado: o mesmo streamer na Twitch, na Kick e no YouTube, com os chats juntos. */
class UnifiedChatTest {
    private val twitchLive = Media(
        Platform.Twitch, "xqc", "live", Channel(Platform.Twitch, "xqc", "xQc", handle = "@xqc", url = "https://www.twitch.tv/xqc"), isLive = true,
        url = "https://www.twitch.tv/xqc",
    )
    private val youtubeLive = Media(
        Platform.YouTube, "vid123", "live", Channel(Platform.YouTube, "UCabc", "Gaules TV", handle = "@gaules", url = "https://youtube.com/@gaules"),
        isLive = true, url = "https://youtu.be/vid123",
    )

    @Test
    fun chaves_e_nomes_para_procurar() {
        assertEquals("Twitch:xqc", liveChannelKey(twitchLive))
        assertEquals("YouTube:UCabc", liveChannelKey(youtubeLive), "no YouTube a chave é o canal, não o vídeo")
        assertEquals("Kick:xqc", channelKey(Platform.Kick, "XQC"))
        assertEquals("YouTube:UCAbC", channelKey(Platform.YouTube, "UCAbC"), "o id do YouTube diferencia maiúsculas")
        assertEquals(listOf("xqc"), sameNameCandidates(twitchLive))
        assertEquals(listOf("gaules", "gaulestv"), sameNameCandidates(youtubeLive))
    }

    @Test
    fun vincular_e_separar() {
        var links = linkChannels(emptyList(), "Twitch:xqc", "Kick:xqc")
        assertEquals(listOf(listOf("Twitch:xqc", "Kick:xqc")), links)
        links = linkChannels(links, "Kick:xqc", "YouTube:UCx")
        assertEquals(listOf(listOf("Twitch:xqc", "Kick:xqc", "YouTube:UCx")), links, "o grupo cresce")
        links = linkChannels(links, "Twitch:outro", "Kick:outro")
        assertEquals(2, links.size)
        links = unlinkChannel(links, "YouTube:UCx")
        assertEquals(listOf("Twitch:xqc", "Kick:xqc"), links.first())
        links = unlinkChannel(links, "Kick:xqc")
        assertEquals(listOf(listOf("Twitch:outro", "Kick:outro")), links, "o grupo com um só some")
        assertEquals(rejectedPair("b", "a"), rejectedPair("a", "b"))
    }

    /** Uma "internet" de mentira: quais canais estão ao vivo em cada plataforma. */
    private fun liveIn(vararg live: Pair<Platform, String>): suspend (Platform, String) -> LinkedChat? = { platform, id ->
        if ((platform to id.lowercase()) in live.map { it.first to it.second.lowercase() }) LinkedChat(platform, channelKey(platform, id), id, id, id, false) else null
    }

    @Test
    fun acha_pelo_mesmo_nome_e_respeita_os_vinculos_e_as_recusas() = runBlocking<Unit> {
        // ninguém vinculado: a Kick de mesmo nome vira sugestão (o YouTube não tem ninguém ao vivo)
        val found = findLinkedChats(twitchLive, emptyList(), emptyList(), liveIn(Platform.Kick to "xqc"))
        assertEquals(listOf(Platform.Kick), found.map { it.platform })
        assertTrue(found.none { it.confirmed }, "achado pelo nome é sugestão")
        // vinculado à mão a um nome diferente: vale o vínculo, confirmado
        val linked = findLinkedChats(twitchLive, listOf(listOf("Twitch:xqc", "Kick:xqcnakick")), emptyList(), liveIn(Platform.Kick to "xqcnakick", Platform.Kick to "xqc"))
        assertEquals(listOf("Kick:xqcnakick"), linked.map { it.channelKey })
        assertTrue(linked.single().confirmed)
        // recusado: não sugere de novo
        val rejected = findLinkedChats(twitchLive, emptyList(), listOf(rejectedPair("Twitch:xqc", "Kick:xqc")), liveIn(Platform.Kick to "xqc"))
        assertTrue(rejected.isEmpty())
        // vinculado mas fora do ar: não entra
        assertTrue(findLinkedChats(twitchLive, listOf(listOf("Twitch:xqc", "Kick:xqc")), emptyList(), liveIn()).isEmpty())
    }

    private class FakeChat(scope: CoroutineScope, override val platform: Platform) : ChatClient(scope) {
        override val canSend = false
        override suspend fun connect() { delay(Long.MAX_VALUE) }
        override suspend fun send(text: String) = false
        fun say(id: String, text: String) = push(ChatMessage(id, "alguém", text))
    }

    @Test
    fun junta_as_mensagens_na_ordem_em_que_chegam_marcando_a_plataforma() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val twitch = FakeChat(scope, Platform.Twitch)
        val kick = FakeChat(scope, Platform.Kick)
        val unified = UnifiedChat(scope, listOf(twitch, kick))
        try {
            unified.start()
            twitch.say("1", "oi da twitch"); delay(50)
            kick.say("1", "oi da kick"); delay(50)
            twitch.say("2", "de novo")
            withTimeout(3_000) { while (unified.messages.value.size < 3) delay(10) }
            assertEquals(listOf("oi da twitch", "oi da kick", "de novo"), unified.messages.value.map { it.text })
            assertEquals(listOf(Platform.Twitch, Platform.Kick, Platform.Twitch), unified.messages.value.map { it.platform })
        } finally {
            unified.stop()
            scope.cancel()
        }
    }
}
