package app.apex.cloud

import app.apex.data.Known
import app.apex.data.UserData
import app.apex.shared.SyncChange
import app.apex.shared.SyncRequest
import app.apex.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

sealed interface SyncStatus {
    data object SignedOut : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Mantém este aparelho igual à conta do Apex.
 *
 * - Descobre o que mudou aqui comparando o conteúdo de cada item com o que já sabia dele.
 * - Envia só as mudanças e recebe só o que mudou nos outros aparelhos.
 * - Em conflito, vale a edição mais recente. Na primeira vez em um aparelho, o que já está na conta vem primeiro.
 */
class SyncEngine(
    private val scope: CoroutineScope,
    private val data: UserData,
    private val client: CloudClient,
) {
    private val _status = MutableStateFlow<SyncStatus>(if (data.cloudSession.value == null) SyncStatus.SignedOut else SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val lock = Mutex()
    private var job: Job? = null

    private fun id(collection: String, key: String) = "$collection|$key"

    private fun hashOf(element: JsonElement?): Long {
        val text = element?.toString() ?: return 0
        return (text.hashCode().toLong() shl 32) xor text.length.toLong()
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Anota na hora o que mudou (e quando): a edição mais recente é a que vale num conflito.
            launch { changes().collect { detectNow() } }
            // Já o envio espera um pouco, para juntar várias mudanças numa viagem só.
            launch {
                changes().collectLatest {
                    delay(2_500)
                    syncNow()
                }
            }
            launch {
                while (true) {
                    delay(5 * 60_000L)
                    syncNow()
                }
            }
            syncNow()
        }
    }

    private fun changes() = merge(
        data.subscriptions.map { }, data.history.map { }, data.watchLater.map { },
        data.playlists.map { }, data.reactions.map { }, data.liked.map { }, data.settings.map { },
    )

    /** Marca o que mudou neste aparelho, com a hora de agora. */
    suspend fun detectNow() {
        lock.withLock { detectLocalChanges() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = SyncStatus.SignedOut
    }

    suspend fun syncNow() {
        if (data.cloudSession.value == null) {
            _status.value = SyncStatus.SignedOut
            return
        }
        lock.withLock {
            _status.value = SyncStatus.Syncing
            try {
                cycle()
                _status.value = SyncStatus.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudException) {
                if (e.code == "signed_out" || data.cloudSession.value == null) {
                    _status.value = SyncStatus.SignedOut
                    stop()
                } else _status.value = SyncStatus.Error(e.message ?: "Falha ao sincronizar.")
            } catch (e: Exception) {
                _status.value = SyncStatus.Error(e.message ?: "Falha ao sincronizar.")
            }
        }
    }

    private suspend fun cycle() {
        val firstTime = data.syncState.value.cursor == null
        // Primeira vez neste aparelho: traz o que a conta já tem antes de enviar o que há aqui.
        if (firstTime) pull(emptyList())
        detectLocalChanges()
        pull(pendingChanges())
        data.updateSyncState { it.copy(lastSyncAt = currentTimeMillis()) }
    }

    /** Compara o conteúdo atual com o que já sabíamos e marca o que mudou ou foi apagado. */
    private fun detectLocalChanges() {
        val snapshot = data.snapshot()
        val now = currentTimeMillis()
        data.updateSyncState { state ->
            val known = state.known.toMutableMap()
            val dirty = state.dirty.toMutableMap()
            for ((collection, items) in snapshot) {
                for ((key, element) in items) {
                    val item = id(collection, key)
                    val h = hashOf(element)
                    val k = known[item]
                    if (k == null || k.hash != h || k.deleted) {
                        known[item] = Known(h, now, false)
                        dirty[item] = now
                    }
                }
            }
            for ((item, k) in known.toList()) {
                if (k.deleted) continue
                val collection = item.substringBefore('|')
                val key = item.substringAfter('|')
                if (snapshot[collection]?.containsKey(key) != true) {
                    known[item] = k.copy(modifiedAt = now, deleted = true)
                    dirty[item] = now
                }
            }
            state.copy(known = known, dirty = dirty)
        }
    }

    private class Pending(val id: String, val change: SyncChange, val modifiedAt: Long)

    private fun pendingChanges(): List<Pending> {
        val state = data.syncState.value
        val snapshot = data.snapshot()
        return state.dirty.entries.take(500).mapNotNull { (item, modifiedAt) ->
            val collection = item.substringBefore('|')
            val key = item.substringAfter('|')
            val element = snapshot[collection]?.get(key)
            val deleted = state.known[item]?.deleted == true || element == null
            Pending(item, SyncChange(collection, key, if (deleted) null else element, deleted, modifiedAt), modifiedAt)
        }
    }

    /** Envia [changes] e recebe tudo o que mudou na conta; repete enquanto houver mais páginas. */
    private suspend fun pull(changes: List<Pending>) {
        var outgoing = changes
        var rounds = 0
        while (rounds++ < 40) {
            val response = client.sync(SyncRequest(data.syncState.value.cursor, outgoing.map { it.change }))
            val pushed = outgoing
            outgoing = emptyList()

            // O que foi enviado deixa de estar pendente (a não ser que mudou de novo nesse meio tempo).
            data.updateSyncState { state ->
                val dirty = state.dirty.toMutableMap()
                for (p in pushed) if (dirty[p.id] == p.modifiedAt) dirty.remove(p.id)
                state.copy(dirty = dirty)
            }

            applyRemote(response.items.map { RemoteItem(it.collection, it.key, it.data, it.deleted, it.modifiedAt) })
            data.updateSyncState { it.copy(cursor = response.cursor) }
            if (!response.hasMore) break
        }
    }

    private class RemoteItem(val collection: String, val key: String, val data: JsonElement?, val deleted: Boolean, val modifiedAt: Long)

    private fun applyRemote(items: List<RemoteItem>) {
        if (items.isEmpty()) return
        val state = data.syncState.value
        val accepted = items.filter { item ->
            val k = state.known[id(item.collection, item.key)]
            k == null || k.modifiedAt <= item.modifiedAt
        }
        accepted.forEach { data.applyRemote(it.collection, it.key, it.data, it.deleted) }
        val after = data.snapshot()
        data.updateSyncState { s ->
            val known = s.known.toMutableMap()
            val dirty = s.dirty.toMutableMap()
            for (item in accepted) {
                val itemId = id(item.collection, item.key)
                known[itemId] = Known(hashOf(after[item.collection]?.get(item.key)), item.modifiedAt, item.deleted)
                dirty.remove(itemId)
            }
            s.copy(known = known, dirty = dirty)
        }
    }
}
