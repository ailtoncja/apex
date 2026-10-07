package app.apex.cloud

import app.apex.data.CloudSession
import app.apex.data.SyncState
import app.apex.data.UserData
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** O que fazer quando o aparelho já tem dados de outra conta do Apex. */
data class ForeignData(val newUserEmail: String)

/** A conta do Apex vista pelo app: entrar, sair, sincronizar. */
class CloudAccount(
    private val scope: CoroutineScope,
    private val data: UserData,
    http: HttpClient,
) {
    val client = CloudClient(http, data) { data.settings.value.serverUrl }
    val engine = SyncEngine(scope, data, client)

    val session: StateFlow<CloudSession?> = data.cloudSession
    val status: StateFlow<SyncStatus> = engine.status
    val syncState: StateFlow<SyncState> = data.syncState

    private val _foreign = MutableStateFlow<ForeignData?>(null)
    val foreignData: StateFlow<ForeignData?> = _foreign.asStateFlow()

    fun start() {
        if (data.cloudSession.value != null) {
            scope.launch { client.wake() }
            engine.start()
        }
    }

    suspend fun signIn(email: String, password: String) {
        val session = client.login(email.trim(), password)
        afterSignIn(session)
    }

    suspend fun signUp(email: String, password: String, name: String?) {
        val session = client.register(email.trim(), password, name?.trim()?.ifBlank { null })
        afterSignIn(session)
    }

    private fun afterSignIn(session: CloudSession) {
        val previous = data.syncState.value.userId
        if (previous != null && previous != session.user.id) {
            // Este aparelho ainda tem dados de outra pessoa: só sincroniza depois que o usuário decidir.
            _foreign.value = ForeignData(session.user.email)
            return
        }
        data.updateSyncState { it.copy(userId = session.user.id) }
        engine.start()
    }

    /** Resposta ao aviso de dados de outra conta: mesclar com os daqui ou começar do zero. */
    fun resolveForeign(merge: Boolean) {
        val session = data.cloudSession.value ?: return
        if (!merge) data.clearSyncedData()
        data.updateSyncState { SyncState(userId = session.user.id) }
        _foreign.value = null
        engine.start()
    }

    fun cancelForeign() {
        _foreign.value = null
        scope.launch { client.logout() }
    }

    fun syncNow() {
        scope.launch { engine.syncNow() }
    }

    /** Sai da conta. Com [wipeLocal], também apaga neste aparelho o que era sincronizado. */
    suspend fun signOut(wipeLocal: Boolean) {
        engine.stop()
        client.logout()
        if (wipeLocal) {
            data.clearSyncedData()
            data.updateSyncState { SyncState() }
        }
    }

    suspend fun deleteAccount(password: String) {
        client.deleteAccount(password)
        engine.stop()
        data.updateSyncState { SyncState() }
    }

    suspend fun forgot(email: String) = client.forgot(email.trim())
}
