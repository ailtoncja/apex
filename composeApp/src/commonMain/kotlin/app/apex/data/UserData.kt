package app.apex.data

import app.apex.model.AppSettings
import app.apex.model.Channel
import app.apex.model.HistoryEntry
import app.apex.model.LocalPlaylist
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.SupportKind
import app.apex.source.AppJson
import app.apex.shared.SyncCollections
import app.apex.shared.UserDto
import app.apex.util.currentTimeMillis
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

interface KeyValueStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

@Serializable
data class Account(
    val platform: Platform,
    val displayName: String,
    val avatarUrl: String? = null,
    /** YouTube: cabeçalho de cookies. Twitch: token. Kick: token de sessão. */
    val credential: String,
    /** De onde veio o login, quando dá para renová-lo lendo o navegador de novo (`firefox:<id>`). */
    val source: String? = null,
)

/** Login no servidor do Apex (guardado cifrado no disco). */
@Serializable
data class CloudSession(val serverUrl: String, val accessToken: String, val refreshToken: String, val user: UserDto)

/** O que a sincronização já sabe de cada item: serve para descobrir o que mudou neste aparelho. */
@Serializable
data class Known(val hash: Long, val modifiedAt: Long, val deleted: Boolean)

@Serializable
data class SyncState(
    val userId: String? = null,
    val cursor: String? = null,
    val known: Map<String, Known> = emptyMap(),
    /** Itens com mudança ainda não enviada: id → quando mudou. */
    val dirty: Map<String, Long> = emptyMap(),
    val lastSyncAt: Long? = null,
)

/** Só os ajustes que fazem sentido em qualquer aparelho. */
@Serializable
data class SyncedSettings(
    val autoplayNext: Boolean = true,
    val defaultQuality: Int = 0,
    val defaultRate: Float = 1f,
    val rememberPosition: Boolean = true,
    val showChat: Boolean = true,
    val hideMature: Boolean = true,
    val subtitleLang: String = "pt",
    val subtitlesOnByDefault: Boolean = false,
    val kickLanguage: String = "pt",
    val twitchLanguage: String = "PT",
    val blockedChannels: List<String> = emptyList(),
)

@Serializable
data class FeedCache(val items: List<Media> = emptyList(), val updatedAt: Long = 0)

/** Tudo que é do usuário e fica no disco: inscrições, histórico, listas, curtidas, ajustes e contas. */
class UserData(private val store: KeyValueStore, private val scope: CoroutineScope) {
    private inline fun <reified T> load(name: String, default: T): T =
        store.read(name)?.let { runCatching { AppJson.decodeFromString<T>(it) }.getOrNull() } ?: default

    /** Uma coleção que vai para o disco; [written] é o que já foi gravado (começa com o que foi lido na abertura). */
    private class Persisted(val name: String, val current: () -> Any?, val encode: () -> String, var written: Any?)

    private val persisted = mutableListOf<Persisted>()

    private inline fun <reified T> persist(name: String, flow: StateFlow<T>) {
        persisted += Persisted(name, { flow.value }, { AppJson.encodeToString(flow.value) }, flow.value)
    }

    /**
     * Grava no disco o que mudou desde a última gravação. Roda sozinho a cada meio segundo e ao fechar o app.
     * (Comparar o estado atual com o gravado não perde mudanças feitas logo na abertura, como acontecia ao "escutar" cada coleção.)
     */
    fun flush() = synchronized(persisted) {
        for (p in persisted) {
            val value = p.current()
            if (value == p.written) continue
            if (runCatching { store.write(p.name, p.encode()) }.isSuccess) p.written = value
        }
    }

    private val _settings = MutableStateFlow(load("settings", AppSettings()))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _subscriptions = MutableStateFlow(load<List<Channel>>("subscriptions", emptyList()))
    val subscriptions: StateFlow<List<Channel>> = _subscriptions.asStateFlow()

    private val _history = MutableStateFlow(load<List<HistoryEntry>>("history", emptyList()))
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _watchLater = MutableStateFlow(load<List<Media>>("watchlater", emptyList()))
    val watchLater: StateFlow<List<Media>> = _watchLater.asStateFlow()

    private val _playlists = MutableStateFlow(load<List<LocalPlaylist>>("playlists", emptyList()))
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    /** `1` = gostei, `-1` = não gostei, por chave da mídia. */
    private val _reactions = MutableStateFlow(load<Map<String, Int>>("reactions", emptyMap()))
    val reactions: StateFlow<Map<String, Int>> = _reactions.asStateFlow()

    private val _liked = MutableStateFlow(load<List<Media>>("liked", emptyList()))
    val liked: StateFlow<List<Media>> = _liked.asStateFlow()

    private val _feed = MutableStateFlow(load("feed", FeedCache()))
    val feed: StateFlow<FeedCache> = _feed.asStateFlow()

    private val _cloud = MutableStateFlow(load<CloudSession?>("cloud", null))
    val cloudSession: StateFlow<CloudSession?> = _cloud.asStateFlow()

    private val _syncState = MutableStateFlow(load("sync_state", SyncState()))
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _accounts = MutableStateFlow(load<Map<String, Account>>("accounts", emptyMap()))
    val accounts: StateFlow<Map<String, Account>> = _accounts.asStateFlow()

    init {
        persist("settings", _settings)
        persist("subscriptions", _subscriptions)
        persist("history", _history)
        persist("watchlater", _watchLater)
        persist("playlists", _playlists)
        persist("reactions", _reactions)
        persist("liked", _liked)
        persist("feed", _feed)
        persist("accounts", _accounts)
        persist("cloud", _cloud)
        persist("sync_state", _syncState)
        scope.launch {
            while (true) {
                delay(500)
                flush()
            }
        }
    }

    // ---------- ajustes ----------

    fun updateSettings(change: (AppSettings) -> AppSettings) = _settings.update(change)

    fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        updateSettings { s -> s.copy(searchHistory = (listOf(q) + s.searchHistory.filterNot { it.equals(q, true) }).take(30)) }
    }

    fun removeSearchHistory(query: String) =
        updateSettings { s -> s.copy(searchHistory = s.searchHistory.filterNot { it == query }) }

    fun blockChannel(channel: Channel) = updateSettings { s -> s.copy(blockedChannels = (s.blockedChannels + channel.key).distinct()) }

    fun unblockChannel(key: String) = updateSettings { s -> s.copy(blockedChannels = s.blockedChannels - key) }

    // ---------- inscrições ----------

    fun isSubscribed(channel: Channel) = _subscriptions.value.any { it.key == channel.key }

    fun toggleSubscription(channel: Channel) = _subscriptions.update { list ->
        if (list.any { it.key == channel.key }) list.filterNot { it.key == channel.key } else list + channel
    }

    /**
     * Marca quais canais a pessoa apoia pagando (sub ou membro) numa plataforma. Quem não está na lista deixa de ser "apoiado";
     * apoiar um canal que ainda não estava nas inscrições também o inscreve.
     */
    fun setSupport(platform: Platform, supported: List<Pair<Channel, SupportKind>>) = _subscriptions.update { list ->
        val byKey = supported.associate { it.first.key to it }
        val updated = list.map { ch -> if (ch.platform != platform) ch else ch.copy(support = byKey[ch.key]?.second) }
        val known = updated.map { it.key }.toSet()
        updated + supported.filter { it.first.key !in known }.map { (c, kind) -> c.copy(support = kind) }
    }

    fun addSubscriptions(channels: List<Channel>) = _subscriptions.update { list ->
        val known = list.map { it.key }.toSet()
        list + channels.filter { it.key !in known }
    }

    // ---------- histórico ----------

    fun recordHistory(media: Media, positionMs: Long, durationMs: Long) {
        if (media.isLive) {
            _history.update { list -> (listOf(HistoryEntry(media, currentTimeMillis(), 0, 0)) + list.filterNot { it.media.key == media.key }).take(500) }
            return
        }
        _history.update { list ->
            (listOf(HistoryEntry(media, currentTimeMillis(), positionMs, durationMs)) + list.filterNot { it.media.key == media.key }).take(500)
        }
    }

    /** Abriu o vídeo: sobe para o topo do histórico sem perder o ponto em que a pessoa tinha parado. */
    fun touchHistory(media: Media) {
        if (media.isLive) return recordHistory(media, 0, 0)
        _history.update { list ->
            val old = list.firstOrNull { it.media.key == media.key }
            (listOf(HistoryEntry(media, currentTimeMillis(), old?.positionMs ?: 0, old?.durationMs ?: 0)) + list.filterNot { it.media.key == media.key }).take(500)
        }
    }

    fun resumePosition(media: Media): Long {
        val e = _history.value.firstOrNull { it.media.key == media.key } ?: return 0
        if (e.durationMs <= 0) return 0
        val left = e.durationMs - e.positionMs
        return if (e.positionMs > 10_000 && left > 15_000) e.positionMs else 0
    }

    fun removeHistory(key: String) = _history.update { list -> list.filterNot { it.media.key == key } }

    fun clearHistory() = _history.update { emptyList() }

    // ---------- assistir depois ----------

    fun isWatchLater(media: Media) = _watchLater.value.any { it.key == media.key }

    fun toggleWatchLater(media: Media) = _watchLater.update { list ->
        if (list.any { it.key == media.key }) list.filterNot { it.key == media.key } else listOf(media) + list
    }

    // ---------- playlists locais ----------

    fun createPlaylist(name: String, first: Media? = null): String {
        val id = "pl_${currentTimeMillis()}"
        _playlists.update { it + LocalPlaylist(id, name.trim().ifBlank { "Nova playlist" }, listOfNotNull(first)) }
        return id
    }

    /**
     * Cria playlist(s) locais com [items] (a cópia de uma playlist do YouTube). A sincronização aceita até 60 mil caracteres por
     * playlist, então listas grandes viram "Nome (1/3)", "Nome (2/3)"… Devolve os ids criados.
     */
    fun importPlaylist(name: String, items: List<Media>): List<String> {
        val clean = name.trim().ifBlank { "Playlist" }
        val parts = mutableListOf<MutableList<Media>>()
        var size = 0
        for (m in items.distinctBy { it.key }.map { it.compactForPlaylist() }) {
            val chars = AppJson.encodeToString(Media.serializer(), m).length + 1
            if (parts.isEmpty() || size + chars > MAX_PLAYLIST_CHARS) {
                parts.add(mutableListOf())
                size = 0
            }
            parts.last() += m
            size += chars
        }
        if (parts.isEmpty()) return emptyList()
        val stamp = currentTimeMillis()
        val created = parts.mapIndexed { i, part ->
            LocalPlaylist("pl_${stamp}_$i", if (parts.size > 1) "$clean (${i + 1}/${parts.size})" else clean, part)
        }
        _playlists.update { it + created }
        return created.map { it.id }
    }

    /** Sem o que só enfeita (avatar, seguidores…): cada item de playlist ocupa menos na sincronização. */
    private fun Media.compactForPlaylist() = copy(channel = channel?.copy(avatarUrl = null, handle = null, followers = null, support = null))

    fun renamePlaylist(id: String, name: String) =
        _playlists.update { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it } }

    fun deletePlaylist(id: String) = _playlists.update { list -> list.filterNot { it.id == id } }

    fun togglePlaylistItem(id: String, media: Media) = _playlists.update { list ->
        list.map { pl ->
            if (pl.id != id) pl
            else if (pl.items.any { it.key == media.key }) pl.copy(items = pl.items.filterNot { it.key == media.key })
            else pl.copy(items = pl.items + media)
        }
    }

    // ---------- curtidas ----------

    fun reaction(media: Media): Int = _reactions.value[media.key] ?: 0

    fun setReaction(media: Media, value: Int) {
        _reactions.update { map -> if (value == 0) map - media.key else map + (media.key to value) }
        _liked.update { list ->
            val without = list.filterNot { it.key == media.key }
            if (value == 1) listOf(media) + without else without
        }
    }

    // ---------- feed ----------

    fun saveFeed(items: List<Media>) {
        _feed.value = FeedCache(items.take(300), currentTimeMillis())
    }

    // ---------- contas ----------

    fun account(platform: Platform): Account? = _accounts.value[platform.name]

    fun setAccount(account: Account?, platform: Platform) = _accounts.update { map ->
        if (account == null) map - platform.name else map + (platform.name to account)
    }

    // ---------- conta do Apex e sincronização ----------

    fun setCloudSession(session: CloudSession?) {
        _cloud.value = session
    }

    fun updateSyncState(change: (SyncState) -> SyncState) = _syncState.update(change)

    private fun AppSettings.synced() = SyncedSettings(
        autoplayNext, defaultQuality, defaultRate, rememberPosition, showChat, hideMature,
        subtitleLang, subtitlesOnByDefault, kickLanguage, twitchLanguage, blockedChannels,
    )

    private inline fun <reified T> decode(element: JsonElement?): T? =
        element?.let { runCatching { AppJson.decodeFromJsonElement<T>(it) }.getOrNull() }

    /** Tudo o que é sincronizado, item por item: coleção -> chave -> conteúdo. */
    fun snapshot(): Map<String, Map<String, JsonElement>> {
        val likedByKey = _liked.value.associateBy { it.key }
        return mapOf(
            SyncCollections.SUBSCRIPTIONS to _subscriptions.value.associate { it.key to AppJson.encodeToJsonElement(it) },
            SyncCollections.HISTORY to _history.value.associate { it.media.key to AppJson.encodeToJsonElement(it) },
            SyncCollections.WATCH_LATER to _watchLater.value.associate { it.key to AppJson.encodeToJsonElement(it) },
            SyncCollections.PLAYLISTS to _playlists.value.associate { it.id to AppJson.encodeToJsonElement(it) },
            SyncCollections.REACTIONS to _reactions.value.mapValues { (key, value) ->
                buildJsonObject {
                    put("value", value)
                    likedByKey[key]?.let { put("media", AppJson.encodeToJsonElement(it)) }
                }
            },
            SyncCollections.SETTINGS to mapOf("app" to AppJson.encodeToJsonElement(_settings.value.synced())),
        )
    }

    /** Aplica uma mudança que veio de outro aparelho. */
    fun applyRemote(collection: String, key: String, element: JsonElement?, deleted: Boolean) {
        val gone = deleted || element == null
        when (collection) {
            SyncCollections.SUBSCRIPTIONS ->
                if (gone) _subscriptions.update { l -> l.filterNot { it.key == key } }
                else decode<Channel>(element)?.let { ch ->
                    _subscriptions.update { l -> if (l.any { it.key == key }) l.map { if (it.key == key) ch else it } else l + ch }
                }
            SyncCollections.HISTORY ->
                if (gone) _history.update { l -> l.filterNot { it.media.key == key } }
                else decode<HistoryEntry>(element)?.let { e ->
                    _history.update { l -> (listOf(e) + l.filterNot { it.media.key == key }).sortedByDescending { it.watchedAt }.take(500) }
                }
            SyncCollections.WATCH_LATER ->
                if (gone) _watchLater.update { l -> l.filterNot { it.key == key } }
                else decode<Media>(element)?.let { m ->
                    _watchLater.update { l -> if (l.any { it.key == key }) l.map { if (it.key == key) m else it } else listOf(m) + l }
                }
            SyncCollections.PLAYLISTS ->
                if (gone) _playlists.update { l -> l.filterNot { it.id == key } }
                else decode<LocalPlaylist>(element)?.let { p ->
                    _playlists.update { l -> if (l.any { it.id == key }) l.map { if (it.id == key) p else it } else l + p }
                }
            SyncCollections.REACTIONS -> {
                if (gone) {
                    _reactions.update { it - key }
                    _liked.update { l -> l.filterNot { it.key == key } }
                } else {
                    val obj = element as? JsonObject ?: return
                    val value = (obj["value"] as? JsonPrimitive)?.int ?: return
                    val media = decode<Media>(obj["media"])
                    _reactions.update { it + (key to value) }
                    _liked.update { l ->
                        val without = l.filterNot { it.key == key }
                        if (value == 1 && media != null) listOf(media) + without else without
                    }
                }
            }
            SyncCollections.SETTINGS ->
                if (!gone) decode<SyncedSettings>(element)?.let { s ->
                    _settings.update {
                        it.copy(
                            autoplayNext = s.autoplayNext, defaultQuality = s.defaultQuality, defaultRate = s.defaultRate,
                            rememberPosition = s.rememberPosition, showChat = s.showChat, hideMature = s.hideMature,
                            subtitleLang = s.subtitleLang, subtitlesOnByDefault = s.subtitlesOnByDefault,
                            kickLanguage = s.kickLanguage, twitchLanguage = s.twitchLanguage, blockedChannels = s.blockedChannels,
                        )
                    }
                }
        }
    }

    /** Apaga os dados deste aparelho que são sincronizados (ao sair de uma conta ou trocar de conta). */
    fun clearSyncedData() {
        _subscriptions.value = emptyList()
        _history.value = emptyList()
        _watchLater.value = emptyList()
        _playlists.value = emptyList()
        _reactions.value = emptyMap()
        _liked.value = emptyList()
        _feed.value = FeedCache()
        _settings.update { it.copy(blockedChannels = emptyList(), searchHistory = emptyList()) }
    }
}

/** A sincronização aceita até 60 mil caracteres por playlist; fica abaixo disso com folga para o nome e o id. */
private const val MAX_PLAYLIST_CHARS = 50_000
