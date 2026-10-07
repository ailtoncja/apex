package app.apex.cloud

import app.apex.data.CloudSession
import app.apex.data.UserData
import app.apex.shared.AuthResponse
import app.apex.shared.DeleteAccountRequest
import app.apex.shared.ErrorResponse
import app.apex.shared.ForgotRequest
import app.apex.shared.LoginRequest
import app.apex.shared.RefreshRequest
import app.apex.shared.RegisterRequest
import app.apex.shared.SyncRequest
import app.apex.shared.SyncResponse
import app.apex.source.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

class CloudException(val code: String, message: String, val status: Int = 0) : Exception(message)

const val DEFAULT_SERVER_URL = "http://localhost:8080"

/** Fala com o servidor de contas do Apex e cuida de renovar a sessão sozinho. */
class CloudClient(
    private val http: HttpClient,
    private val data: UserData,
    private val serverUrl: () -> String,
) {
    val session: StateFlow<CloudSession?> = data.cloudSession
    private val refreshLock = Mutex()

    private fun base() = serverUrl().trim().ifBlank { DEFAULT_SERVER_URL }.trimEnd('/')

    private suspend fun raw(url: String, body: String?, token: String?, delete: Boolean = false): HttpResponse = try {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            // Hospedagens grátis "dormem" e levam cerca de um minuto para acordar na primeira chamada.
            timeout { requestTimeoutMillis = 70_000; connectTimeoutMillis = 30_000 }
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (delete) http.delete(url, block) else http.post(url, block)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CloudException("offline", "Não consegui falar com o servidor do Apex. Verifique a internet e o endereço do servidor.")
    }

    private suspend fun failure(response: HttpResponse): Nothing {
        val parsed = runCatching { AppJson.decodeFromString<ErrorResponse>(response.bodyAsText()) }.getOrNull()
        throw CloudException(
            parsed?.error?.code ?: "http_${response.status.value}",
            parsed?.error?.message ?: "O servidor respondeu com erro ${response.status.value}.",
            response.status.value,
        )
    }

    private fun AuthResponse.toSession(url: String) = CloudSession(url, accessToken, refreshToken, user)

    /** Acorda o servidor (se estiver dormindo) sem esperar nada dele. */
    suspend fun wake() {
        runCatching { http.get("${base()}/health") { timeout { requestTimeoutMillis = 70_000; connectTimeoutMillis = 30_000 } } }
    }

    // ---------- sem sessão

    suspend fun register(email: String, password: String, name: String?): CloudSession {
        val url = base()
        val response = raw("$url/v1/auth/register", AppJson.encodeToString(RegisterRequest(email, password, name)), null)
        if (!response.status.isSuccess()) failure(response)
        return AppJson.decodeFromString<AuthResponse>(response.bodyAsText()).toSession(url).also(data::setCloudSession)
    }

    suspend fun login(email: String, password: String): CloudSession {
        val url = base()
        val response = raw("$url/v1/auth/login", AppJson.encodeToString(LoginRequest(email, password, "Apex Windows")), null)
        if (!response.status.isSuccess()) failure(response)
        return AppJson.decodeFromString<AuthResponse>(response.bodyAsText()).toSession(url).also(data::setCloudSession)
    }

    suspend fun forgot(email: String) {
        val response = raw("${base()}/v1/auth/forgot", AppJson.encodeToString(ForgotRequest(email)), null)
        if (!response.status.isSuccess()) failure(response)
    }

    // ---------- com sessão

    /** Faz a chamada com o token de acesso; se vencer, renova a sessão e tenta de novo. */
    private suspend fun authed(path: String, body: String?, delete: Boolean = false): HttpResponse {
        var s = session.value ?: throw CloudException("signed_out", "Entre na sua conta do Apex.")
        var response = raw("${s.serverUrl}$path", body, s.accessToken, delete)
        if (response.status.value == 401) {
            refresh(s.refreshToken)
            s = session.value ?: throw CloudException("signed_out", "Sua sessão terminou. Entre de novo.")
            response = raw("${s.serverUrl}$path", body, s.accessToken, delete)
        }
        return response
    }

    private suspend fun refresh(usedRefreshToken: String) = refreshLock.withLock {
        val current = session.value ?: return@withLock
        // Outra chamada já renovou enquanto esperávamos.
        if (current.refreshToken != usedRefreshToken) return@withLock
        val response = raw("${current.serverUrl}/v1/auth/refresh", AppJson.encodeToString(RefreshRequest(current.refreshToken)), null)
        if (response.status.isSuccess()) {
            data.setCloudSession(AppJson.decodeFromString<AuthResponse>(response.bodyAsText()).toSession(current.serverUrl))
        } else if (response.status.value == 401) {
            data.setCloudSession(null)
        }
    }

    suspend fun sync(request: SyncRequest): SyncResponse {
        val response = authed("/v1/sync", AppJson.encodeToString(request))
        if (!response.status.isSuccess()) failure(response)
        return AppJson.decodeFromString(response.bodyAsText())
    }

    suspend fun logout() {
        val s = session.value ?: return
        runCatching { raw("${s.serverUrl}/v1/auth/logout", AppJson.encodeToString(RefreshRequest(s.refreshToken)), null) }
        data.setCloudSession(null)
    }

    suspend fun deleteAccount(password: String) {
        val response = authed("/v1/me", AppJson.encodeToString(DeleteAccountRequest(password)), delete = true)
        if (!response.status.isSuccess()) failure(response)
        data.setCloudSession(null)
    }
}
