package app.apex.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Formatos trocados entre o app e o servidor do Apex. */

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String? = null)

@Serializable
data class LoginRequest(val email: String, val password: String, val device: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ForgotRequest(val email: String)

@Serializable
data class ResetRequest(val token: String, val newPassword: String)

@Serializable
data class VerifyRequest(val token: String)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val emailVerified: Boolean = false,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    /** Em quantos segundos o [accessToken] expira. */
    val expiresInSec: Long,
    val user: UserDto,
)

@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class ErrorResponse(val error: ErrorBody)

object SyncCollections {
    const val SUBSCRIPTIONS = "subscriptions"
    const val HISTORY = "history"
    const val WATCH_LATER = "watch_later"
    const val REACTIONS = "reactions"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"

    val all = setOf(SUBSCRIPTIONS, HISTORY, WATCH_LATER, REACTIONS, PLAYLISTS, SETTINGS)
}

/** Uma mudança feita neste aparelho. */
@Serializable
data class SyncChange(
    val collection: String,
    val key: String,
    /** O item inteiro; `null` quando [deleted]. */
    val data: JsonElement? = null,
    val deleted: Boolean = false,
    /** Quando o item foi mexido, no relógio do aparelho (ms). Vale a edição mais recente. */
    val modifiedAt: Long,
)

@Serializable
data class SyncRequest(
    /** Cursor devolvido na sincronização anterior; `null` na primeira vez. */
    val since: String? = null,
    val changes: List<SyncChange> = emptyList(),
)

@Serializable
data class SyncItem(
    val collection: String,
    val key: String,
    val data: JsonElement? = null,
    val deleted: Boolean = false,
    val modifiedAt: Long,
    val updatedAt: String,
)

@Serializable
data class SyncResponse(
    /** Guarde para a próxima chamada. Se [hasMore], chame de novo logo em seguida. */
    val cursor: String,
    val items: List<SyncItem>,
    val hasMore: Boolean = false,
    /** Quantas mudanças enviadas foram aceitas (as mais antigas que o servidor já tem são ignoradas). */
    val applied: Int = 0,
)
