package app.apex.chat

import app.apex.model.ChatMessage
import app.apex.source.Http
import app.apex.source.KickSource
import app.apex.source.get
import app.apex.source.long
import app.apex.source.list
import app.apex.source.parseJson
import app.apex.source.path
import app.apex.source.str
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlin.math.abs

class ChatUnavailable(message: String) : Exception(message)

/** Chat de uma live. Lê sem login; para escrever precisa de uma conta. */
abstract class ChatClient(protected val scope: CoroutineScope) {
    protected val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    protected val _status = MutableStateFlow("Conectando ao chat…")
    val status: StateFlow<String> = _status.asStateFlow()

    abstract val canSend: Boolean

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    _status.value = if (attempt == 0) "Conectando ao chat…" else "Reconectando…"
                    connect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ChatUnavailable) {
                    _status.value = e.message ?: "Chat indisponível"
                    return@launch
                } catch (_: Exception) {
                }
                attempt++
                _status.value = "Chat desconectado. Tentando de novo…"
                delay(minOf(15_000L, 2_000L * attempt))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    protected fun push(message: ChatMessage) {
        _messages.update { (it + message).takeLast(300) }
    }

    protected abstract suspend fun connect()

    abstract suspend fun send(text: String): Boolean
}

internal fun colorFromName(name: String): Long {
    val palette = longArrayOf(0xFFFF6B6B, 0xFF4ECDC4, 0xFFFFD93D, 0xFF6C9BFF, 0xFFC792EA, 0xFFFF9F68, 0xFF7BE495, 0xFFF78FB3)
    return palette[abs(name.hashCode()) % palette.size]
}

internal fun parseHexColor(hex: String?): Long? {
    val h = hex?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return h.toLongOrNull(16)?.let { 0xFF000000 or it }
}

// ------------------------------------------------------------------------------------------------

class TwitchChat(
    scope: CoroutineScope,
    private val login: String,
    private val http: HttpClient = Http.client,
    private val token: () -> String?,
    private val accountLogin: () -> String?,
) : ChatClient(scope) {
    private var session: DefaultClientWebSocketSession? = null

    override val canSend: Boolean get() = token() != null && accountLogin() != null

    override suspend fun connect() {
        http.webSocket("wss://irc-ws.chat.twitch.tv:443") {
            session = this
            val oauth = token()
            val nick = accountLogin()
            send(Frame.Text("CAP REQ :twitch.tv/tags twitch.tv/commands"))
            if (oauth != null && nick != null) {
                send(Frame.Text("PASS oauth:$oauth"))
                send(Frame.Text("NICK ${nick.lowercase()}"))
            } else {
                send(Frame.Text("PASS SCHMOOPIIE"))
                send(Frame.Text("NICK justinfan${(10_000..99_999).random()}"))
            }
            send(Frame.Text("JOIN #${login.lowercase()}"))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                frame.readText().split("\r\n").filter { it.isNotBlank() }.forEach { handle(it) }
            }
        }
        session = null
    }

    private suspend fun DefaultClientWebSocketSession.handle(line: String) {
        val irc = parseIrc(line) ?: return
        when (irc.command) {
            "PING" -> send(Frame.Text("PONG :tmi.twitch.tv"))
            "JOIN" -> _status.value = "Chat ao vivo"
            "PRIVMSG" -> {
                val name = irc.tags["display-name"].orEmpty().ifBlank { irc.prefix.substringBefore('!') }
                var text = irc.trailing
                if (text.startsWith("\u0001ACTION ")) text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
                push(
                    ChatMessage(
                        id = irc.tags["id"] ?: "${name.hashCode()}${text.hashCode()}${_messages.value.size}",
                        author = name,
                        text = text,
                        color = parseHexColor(irc.tags["color"]) ?: colorFromName(name),
                        badges = irc.tags["badges"].orEmpty().split(',').filter { it.isNotBlank() }.map { it.substringBefore('/') },
                    ),
                )
            }
            "NOTICE" -> if (irc.trailing.contains("authentication failed", true)) _status.value = "Login do chat recusado pela Twitch"
        }
    }

    override suspend fun send(text: String): Boolean {
        val s = session ?: return false
        if (!canSend) return false
        s.send(Frame.Text("PRIVMSG #${login.lowercase()} :$text"))
        val me = accountLogin().orEmpty()
        push(ChatMessage("me${text.hashCode()}${_messages.value.size}", me, text, colorFromName(me)))
        return true
    }
}

internal class IrcLine(val tags: Map<String, String>, val prefix: String, val command: String, val trailing: String)

internal fun parseIrc(line: String): IrcLine? {
    var rest = line
    val tags = mutableMapOf<String, String>()
    if (rest.startsWith("@")) {
        val sp = rest.indexOf(' ')
        if (sp < 0) return null
        rest.substring(1, sp).split(';').forEach { tags[it.substringBefore('=')] = it.substringAfter('=', "") }
        rest = rest.substring(sp + 1)
    }
    var prefix = ""
    if (rest.startsWith(":")) {
        val sp = rest.indexOf(' ')
        if (sp < 0) return null
        prefix = rest.substring(1, sp)
        rest = rest.substring(sp + 1)
    }
    val trailingAt = rest.indexOf(" :")
    val trailing = if (trailingAt >= 0) rest.substring(trailingAt + 2) else ""
    val head = if (trailingAt >= 0) rest.substring(0, trailingAt) else rest
    return IrcLine(tags, prefix, head.split(' ').firstOrNull().orEmpty(), trailing)
}

// ------------------------------------------------------------------------------------------------

class YouTubeChat(
    scope: CoroutineScope,
    private val videoId: String,
    private val youtube: app.apex.source.YouTubeSource,
) : ChatClient(scope) {
    override val canSend: Boolean get() = false

    override suspend fun connect() {
        var continuation = when (val start = youtube.liveChat(videoId)) {
            is app.apex.source.LiveChatStart.Ready -> start.token
            is app.apex.source.LiveChatStart.Unavailable -> throw ChatUnavailable(start.message)
        }
        _status.value = "Chat ao vivo"
        val seen = HashSet<String>()
        while (true) {
            val resp = youtube.tube.call("live_chat/get_live_chat") { put("continuation", continuation) } ?: error("Sem resposta do chat")
            val live = resp.path("continuationContents", "liveChatContinuation")
            live["actions"].list().forEach { action ->
                val item = action.path("addChatItemAction", "item", "liveChatTextMessageRenderer") ?: return@forEach
                val id = item["id"].str() ?: return@forEach
                if (!seen.add(id)) return@forEach
                val name = item["authorName"]["simpleText"].str() ?: return@forEach
                val text = item["message"]["runs"].list().joinToString("") {
                    it["text"].str() ?: it.path("emoji", "shortcuts", 0).str() ?: ""
                }
                val isMod = item["authorBadges"].list().isNotEmpty()
                push(ChatMessage(id, name, text, colorFromName(name), if (isMod) listOf("moderator") else emptyList()))
            }
            val next = live["continuations"][0]
            val data = next["timedContinuationData"] ?: next["invalidationContinuationData"] ?: next["reloadContinuationData"] ?: error("Chat encerrado")
            continuation = data["continuation"].str() ?: error("Chat encerrado")
            delay((data["timeoutMs"].long() ?: 3000).coerceIn(1500, 8000))
        }
    }

    override suspend fun send(text: String): Boolean = false
}

// ------------------------------------------------------------------------------------------------

class KickChat(
    scope: CoroutineScope,
    private val slug: String,
    private val kick: KickSource,
    private val http: HttpClient = Http.client,
) : ChatClient(scope) {
    override val canSend: Boolean get() = false

    override suspend fun connect() {
        val chatroom = kick.chatroomId(slug) ?: error("Chat da Kick indisponível")
        http.webSocket("wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679?protocol=7&client=js&version=8.4.0&flash=false") {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val json = parseJson(frame.readText()) ?: continue
                when (json["event"].str()) {
                    "pusher:connection_established" ->
                        send(Frame.Text("""{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroom.v2"}}"""))
                    "pusher:ping" -> send(Frame.Text("""{"event":"pusher:pong","data":{}}"""))
                    "pusher_internal:subscription_succeeded" -> _status.value = "Chat ao vivo"
                    "App\\Events\\ChatMessageEvent" -> {
                        val data = parseJson(json["data"].str().orEmpty()) ?: continue
                        val name = data["sender"]["username"].str() ?: continue
                        val color = parseHexColor(data.path("sender", "identity", "color").str()) ?: colorFromName(name)
                        push(
                            ChatMessage(
                                id = data["id"].str() ?: "${name.hashCode()}${_messages.value.size}",
                                author = name,
                                text = cleanKickText(data["content"].str().orEmpty()),
                                color = color,
                                badges = data.path("sender", "identity", "badges").list().mapNotNull { it["type"].str() },
                            ),
                        )
                    }
                }
            }
        }
    }

    /** As figurinhas da Kick vêm como `[emote:1234:nome]`; deixa só o nome. */
    private fun cleanKickText(raw: String): String =
        Regex("""\[emote:\d+:([^\]]+)]""").replace(raw) { it.groupValues[1] }

    override suspend fun send(text: String): Boolean = false
}
