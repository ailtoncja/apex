package app.apex.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.apex.model.ChatMessage
import app.apex.source.Http
import app.apex.source.KickRealtime
import app.apex.source.bool
import app.apex.source.KickSource
import io.ktor.client.request.header
import app.apex.source.get
import app.apex.source.long
import app.apex.source.list
import app.apex.source.parseJson
import app.apex.source.path
import app.apex.source.str
import app.apex.source.stripLineBreaks
import app.apex.source.twitchChatLogin
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
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
import kotlinx.coroutines.withTimeoutOrNull
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

    /** De qual plataforma é este chat. */
    abstract val platform: app.apex.model.Platform

    protected val _extensionEmotes = MutableStateFlow(ChannelEmotes.Empty)
    /** Os emotes da 7TV, BetterTTV e FrankerFaceZ deste canal (vazio enquanto carrega ou quando estão desligados nos Ajustes). */
    val extensionEmotes: StateFlow<ChannelEmotes> = _extensionEmotes.asStateFlow()

    protected val _nativeEmotes = MutableStateFlow(ChannelEmotes.Empty)
    /** Os emotes da própria plataforma para escrever: os do canal, os globais e (na Twitch, com a conta) os que a conta pode usar. */
    val nativeEmotes: StateFlow<ChannelEmotes> = _nativeEmotes.asStateFlow()

    private var emotesLoaded = false

    /** Busca os emotes ([loadEmotes]) uma vez só, na primeira conexão; uma falha só deixa a lista vazia. */
    protected fun loadEmotesOnce() {
        if (emotesLoaded) return
        emotesLoaded = true
        scope.launch { runCatching { loadEmotes() } }
    }

    /** Onde cada plataforma busca os emotes dela (e os das extensões para o canal). */
    protected open suspend fun loadEmotes() {}

    /** O que o campo de escrever diz quando não dá para escrever. */
    open val sendHint: String get() = "Entre na conta para escrever no chat"

    /** Por que o último [send] não foi (quando a plataforma disse); `null` = sem motivo conhecido. */
    var lastSendError: String? = null
        protected set

    private var job: Job? = null

    /** As mensagens já mostradas, pelo id: o histórico e o chat ao vivo trazem as mesmas nos primeiros segundos, e cada uma aparece uma vez só. */
    private val seen: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val counter = java.util.concurrent.atomic.AtomicInteger()
    private var historyLoaded = false

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

    /** Mostra [message], se ainda não apareceu; `false` quando é repetida (mesmo id). */
    protected fun push(message: ChatMessage): Boolean {
        if (!seen.add(message.id)) return false
        _messages.update { (it + message).takeLast(300) }
        return true
    }

    /** Um id para a mensagem que veio sem o seu; nunca se repete, mesmo com o chat cheio. */
    protected fun localId(prefix: String) = "$prefix-${counter.incrementAndGet()}"

    /**
     * As mensagens que já estavam no chat antes de entrar (cada plataforma entrega as últimas dezenas): aparecem na primeira conexão, antes
     * do chat ao vivo; numa reconexão o chat continua de onde estava. Falhar ou demorar em buscá-las não impede o chat.
     */
    protected suspend fun loadHistory(fetch: suspend () -> List<ChatMessage>) {
        if (historyLoaded) return
        historyLoaded = true
        val history = runCatching { withTimeoutOrNull(HISTORY_TIMEOUT_MS) { fetch() } }.getOrNull().orEmpty()
        app.apex.util.Timing.mark("chat: ${history.size} mensagens de antes de entrar")
        history.forEach { push(it) }
    }

    private companion object {
        const val HISTORY_TIMEOUT_MS = 6_000L
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
    /** As últimas mensagens do chat, de antes de entrar (a Twitch só as entrega com a conta; ver [app.apex.source.TwitchSource.recentChatMessages]). */
    private val history: suspend () -> List<ChatMessage> = { emptyList() },
    /** Os emotes das extensões para o canal de id dado (o número do canal chega pelo próprio chat); `null` = desligados. */
    private val extensions: (suspend (String) -> ChannelEmotes)? = null,
    /** Os emotes da própria Twitch para escrever (os globais e os da conta). */
    private val natives: (suspend () -> ChannelEmotes)? = null,
) : ChatClient(scope) {
    override suspend fun loadEmotes() {
        natives?.let { _nativeEmotes.value = it() }
    }

    override val platform = app.apex.model.Platform.Twitch

    @Volatile private var roomId: String? = null

    /** O número do canal chegou (na entrada no chat, ou em cada mensagem): busca os emotes das extensões dele, uma vez. */
    private fun room(id: String?) {
        if (id.isNullOrBlank() || roomId != null) return
        roomId = id
        val load = extensions ?: return
        scope.launch { runCatching { load(id) }.getOrNull()?.let { _extensionEmotes.value = it } }
    }

    private var session: DefaultClientWebSocketSession? = null

    override val canSend: Boolean get() = token() != null && accountLogin() != null

    /** O canal como o IRC aceita; `null` se o login tem algo além de letras minúsculas, números e "_" (viraria outro comando IRC). */
    private val channel: String? = twitchChatLogin(login)

    override suspend fun connect() {
        val channel = channel ?: throw ChatUnavailable("Canal inválido para o chat da Twitch")
        loadEmotesOnce()
        loadHistory(history)
        http.webSocket("wss://irc-ws.chat.twitch.tv:443") {
            session = this
            val oauth = token()?.takeIf { '\r' !in it && '\n' !in it && ' ' !in it }
            // O nome da conta só vale se for um login de verdade; senão, entra só para ler.
            val nick = accountLogin()?.let { twitchChatLogin(it) }
            send(Frame.Text("CAP REQ :twitch.tv/tags twitch.tv/commands"))
            if (oauth != null && nick != null) {
                send(Frame.Text("PASS oauth:$oauth"))
                send(Frame.Text("NICK $nick"))
            } else {
                send(Frame.Text("PASS SCHMOOPIIE"))
                send(Frame.Text("NICK justinfan${(10_000..99_999).random()}"))
            }
            send(Frame.Text("JOIN #$channel"))
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
            "ROOMSTATE" -> room(irc.tags["room-id"])
            "PRIVMSG" -> {
                room(irc.tags["room-id"])
                val name = irc.tags["display-name"].orEmpty().ifBlank { irc.prefix.substringBefore('!') }
                var text = irc.trailing
                if (text.startsWith("\u0001ACTION ")) text = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
                push(
                    ChatMessage(
                        id = irc.tags["id"] ?: localId("irc"),
                        author = name,
                        text = text,
                        color = parseHexColor(irc.tags["color"]) ?: colorFromName(name),
                        badges = irc.tags["badges"].orEmpty().split(',').filter { it.isNotBlank() }.map { it.substringBefore('/') },
                        emotes = parseTwitchEmotesTag(irc.tags["emotes"], text),
                    ),
                )
            }
            "NOTICE" -> if (irc.trailing.contains("authentication failed", true)) _status.value = "Login do chat recusado pela Twitch"
        }
    }

    override suspend fun send(text: String): Boolean {
        val s = session ?: return false
        val channel = channel ?: return false
        if (!canSend) return false
        // Uma quebra de linha no texto viraria um segundo comando IRC (com o login da pessoa).
        val clean = stripLineBreaks(text).trim()
        if (clean.isEmpty()) return false
        s.send(Frame.Text("PRIVMSG #$channel :$clean"))
        val me = accountLogin().orEmpty()
        push(ChatMessage(localId("me"), me, clean, colorFromName(me)))
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
    /** O canal (UC…) da live e os emotes das extensões para ele (a 7TV atende o YouTube); `null` = desligados. */
    private val channelId: String? = null,
    private val extensions: (suspend (String) -> ChannelEmotes)? = null,
) : ChatClient(scope) {
    override suspend fun loadEmotes() {
        val id = channelId ?: return
        extensions?.let { _extensionEmotes.value = it(id) }
    }

    override val platform = app.apex.model.Platform.YouTube

    /** O código de envio do chat desta live; só aparece com a conta logada e o chat aberto para ela. */
    private var sendParams by mutableStateOf<String?>(null)
    private var restriction by mutableStateOf<String?>(null)

    override val canSend: Boolean get() = youtube.tube.loggedIn && sendParams != null

    override val sendHint: String
        get() = if (youtube.tube.loggedIn) restriction ?: "O chat desta live não está aberto para você escrever" else super.sendHint

    override suspend fun connect() {
        loadEmotesOnce()
        var continuation = when (val start = youtube.liveChat(videoId)) {
            is app.apex.source.LiveChatStart.Ready -> start.token
            is app.apex.source.LiveChatStart.Unavailable -> throw ChatUnavailable(start.message)
        }
        _status.value = "Chat ao vivo"
        while (true) {
            val resp = youtube.tube.call("live_chat/get_live_chat") { put("continuation", continuation) } ?: error("Sem resposta do chat")
            val live = resp.path("continuationContents", "liveChatContinuation")
            if (live != null) {
                youtube.chatSendParams(live)?.let { sendParams = it }
                youtube.chatRestriction(live)?.let { restriction = it }
            }
            live["actions"].list().forEach { action ->
                val item = action.path("addChatItemAction", "item", "liveChatTextMessageRenderer") ?: return@forEach
                push(parseYoutubeChatItem(item) ?: return@forEach)
            }
            val next = live["continuations"][0]
            val data = next["timedContinuationData"] ?: next["invalidationContinuationData"] ?: next["reloadContinuationData"] ?: error("Chat encerrado")
            continuation = data["continuation"].str() ?: error("Chat encerrado")
            delay((data["timeoutMs"].long() ?: 3000).coerceIn(1500, 8000))
        }
    }

    override suspend fun send(text: String): Boolean {
        lastSendError = null
        val params = sendParams ?: run { lastSendError = sendHint; return false }
        val sent = runCatching { youtube.sendChatMessage(params, text) }.getOrNull()
        if (sent == null) { lastSendError = "Sem conexão com o YouTube."; return false }
        if (sent.result != app.apex.source.PostResult.Posted) { lastSendError = sent.reason; return false }
        // A mensagem volta na resposta: aparece já, e o chat (que a traz de novo daqui a alguns segundos) a ignora.
        sent.message?.let { push(it) }
        return true
    }
}

// ------------------------------------------------------------------------------------------------

class KickChat(
    scope: CoroutineScope,
    private val slug: String,
    private val kick: KickSource,
    private val http: HttpClient = Http.client,
    /** Os emotes das extensões para o usuário da Kick de número dado (a 7TV atende a Kick); `null` = desligados. */
    private val extensions: (suspend (Long) -> ChannelEmotes)? = null,
) : ChatClient(scope) {
    override val platform = app.apex.model.Platform.Kick

    /** A sala do chat do canal (conhecida depois que conectou). */
    @Volatile private var chatroom: Long? = null

    override val canSend: Boolean get() = kick.sessionToken != null

    override suspend fun loadEmotes() {
        val found = kick.emotes(slug)
        _nativeEmotes.value = ChannelEmotes(found.channel, found.global)
        val userId = found.userId ?: return
        extensions?.let { _extensionEmotes.value = it(userId) }
    }

    override suspend fun connect() {
        loadEmotesOnce()
        val chatroom = (chatroom ?: kick.chatroomId(slug)) ?: error("Chat da Kick indisponível")
        this.chatroom = chatroom
        loadHistory { kick.recentChat(slug) }
        // A Kick diz em qual servidor o chat está (ela já trocou do Pusher para o Centrifugo); sem resposta, vale o Centrifugo conhecido.
        val clientId = java.util.UUID.randomUUID().toString()
        val realtime = kick.channelId(slug)?.let { id -> runCatching { kick.chatRealtime(id, clientId) }.getOrNull() } ?: KickRealtime.DEFAULT
        app.apex.util.Timing.mark("chat kick: $realtime")
        when (realtime) {
            is KickRealtime.Centrifugo -> connectCentrifugo(realtime.url, clientId, chatroom)
            is KickRealtime.Pusher -> connectPusher(realtime, chatroom)
        }
    }

    /** O Centrifugo da Kick (protocolo JSON): conecta com o passe, entra na sala do chat e responde aos pings; o passe é renovado antes de vencer. */
    private suspend fun connectCentrifugo(url: String, clientId: String, chatroom: Long) {
        val token = kick.realtimeToken(clientId) ?: error("A Kick não deu o passe do chat")
        http.webSocket(url, request = { header("Origin", "https://kick.com"); header("User-Agent", Http.UA) }) {
            send(Frame.Text(kotlinx.serialization.json.buildJsonObject {
                put("id", 1); put("connect", kotlinx.serialization.json.buildJsonObject { put("token", token); put("name", "js") })
            }.toString()))
            send(Frame.Text("""{"id":2,"subscribe":{"channel":"chatrooms.$chatroom.v2"}}"""))
            var nextId = 3
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                // Um quadro pode trazer várias respostas, uma por linha; `{}` é o ping do servidor, respondido com `{}`.
                for (line in frame.readText().split('\n')) {
                    if (line.isBlank()) continue
                    if (line.trim() == "{}") { send(Frame.Text("{}")); continue }
                    val json = parseJson(line) ?: continue
                    json["error"]?.let { error("Centrifugo: ${it["message"].str()}") }
                    json["connect"]?.let { c ->
                        // O passe vence (ttl em segundos): um novo vai um minuto antes.
                        val ttl: Long = c["ttl"].long() ?: 0L
                        if (ttl > 0 && c["expires"].bool() == true) launch {
                            while (true) {
                                delay((ttl - 60).coerceAtLeast(30) * 1_000)
                                val fresh = kick.realtimeToken(clientId) ?: return@launch
                                send(Frame.Text(kotlinx.serialization.json.buildJsonObject {
                                    put("id", nextId++); put("refresh", kotlinx.serialization.json.buildJsonObject { put("token", fresh) })
                                }.toString()))
                            }
                        }
                    }
                    if (json["id"].long() == 2L && json["subscribe"] != null) _status.value = "Chat ao vivo"
                    json["push"]?.let { p ->
                        p["disconnect"]?.let { error("A Kick fechou o chat: ${it["reason"].str()}") }
                        val data = p["pub"]["data"]
                        onKickEvent(data["event"].str(), data["data"].str())
                    }
                }
            }
        }
    }

    /** O Pusher, por onde o chat da Kick passava (fica para o caso de ela voltar a mandar usá-lo). */
    private suspend fun connectPusher(pusher: KickRealtime.Pusher, chatroom: Long) {
        http.webSocket("wss://ws-${pusher.cluster}.pusher.com/app/${pusher.appKey}?protocol=7&client=js&version=8.4.0&flash=false") {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val json = parseJson(frame.readText()) ?: continue
                when (json["event"].str()) {
                    "pusher:connection_established" ->
                        send(Frame.Text("""{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroom.v2"}}"""))
                    "pusher:ping" -> send(Frame.Text("""{"event":"pusher:pong","data":{}}"""))
                    "pusher_internal:subscription_succeeded" -> _status.value = "Chat ao vivo"
                    "pusher:error" -> error("Pusher: ${json["data"]["message"].str()}")
                    else -> onKickEvent(json["event"].str(), json["data"].str())
                }
            }
        }
    }

    /** Um evento da sala do chat (igual nos dois servidores): por enquanto só as mensagens. */
    private fun onKickEvent(event: String?, data: String?) {
        if (event != "App\\Events\\ChatMessageEvent") return
        val m = parseKickChatMessage(data ?: return) ?: return
        if (push(if (m.id.isEmpty()) m.copy(id = localId("kick")) else m) && !liveSeen) {
            liveSeen = true
            app.apex.util.Timing.mark("chat kick: primeira mensagem ao vivo")
        }
    }

    @Volatile private var liveSeen = false

    override suspend fun send(text: String): Boolean {
        lastSendError = null
        val room = chatroom ?: kick.chatroomId(slug)?.also { chatroom = it }
        if (room == null) { lastSendError = "O chat da Kick ainda não conectou."; return false }
        val reason = runCatching { kick.sendChat(room, encodeKickEmotes(text, nativeEmotes.value.byName)) }.getOrElse { "Sem conexão com a Kick." }
        lastSendError = reason
        return reason == null
    }
}

/**
 * A mensagem como a Kick a quer: cada palavra que é o nome de um emote da Kick ([kickEmotes]) vira `[emote:id:nome]`, senão o chat mostra só o
 * texto. O resto fica como está.
 */
internal fun encodeKickEmotes(text: String, kickEmotes: Map<String, Emote>): String {
    if (kickEmotes.isEmpty()) return text
    return Regex("""\S+""").replace(text) { m -> kickEmotes[m.value]?.sendAs ?: m.value }
}

/** Uma mensagem do chat ao vivo da Kick (o `data` do evento ChatMessageEvent, em texto JSON); id vazio quando ela não trouxe. */
internal fun parseKickChatMessage(raw: String): ChatMessage? {
    val data = parseJson(raw) ?: return null
    val name = data["sender"]["username"].str() ?: return null
    val color = parseHexColor(data.path("sender", "identity", "color").str()) ?: colorFromName(name)
    val (text, emotes) = parseKickEmotes(data["content"].str().orEmpty())
    return ChatMessage(
        id = data["id"].str().orEmpty(),
        author = name,
        text = text,
        color = color,
        badges = data.path("sender", "identity", "badges").list().mapNotNull { it["type"].str() },
        emotes = emotes,
    )
}

/** As figurinhas da Kick vêm como `[emote:1234:nome]`; deixa só o nome. */
internal fun cleanKickText(raw: String): String = parseKickEmotes(raw).first

/** Uma mensagem de texto do chat do YouTube (ao vivo ou gravado). */
internal fun parseYoutubeChatItem(item: JsonElement): ChatMessage? {
    val id = item["id"].str() ?: return null
    val name = item["authorName"]["simpleText"].str() ?: return null
    val text = item["message"]["runs"].list().joinToString("") { it["text"].str() ?: it.path("emoji", "shortcuts", 0).str() ?: "" }
    val isMod = item["authorBadges"].list().isNotEmpty()
    return ChatMessage(id, name, text, colorFromName(name), if (isMod) listOf("moderator") else emptyList())
}
