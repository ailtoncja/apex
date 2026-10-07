package app.apex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.cloud.CloudException
import app.apex.cloud.DEFAULT_SERVER_URL
import app.apex.cloud.SyncStatus
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.Avatar
import app.apex.util.relativeTime
import kotlinx.coroutines.launch

/** "Conta do Apex": entrar, criar conta e acompanhar a sincronização. */
@Composable
fun CloudSection() {
    val app = LocalApp.current
    val cloud = app.cloud
    val session by cloud.session.collectAsState()
    val status by cloud.status.collectAsState()
    val syncState by cloud.syncState.collectAsState()
    val foreign by cloud.foreignData.collectAsState()
    val settings by app.data.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var signUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }

    fun submit() {
        if (busy) return
        if (email.isBlank() || password.isBlank()) {
            message = "Preencha o e-mail e a senha."
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                if (signUp) cloud.signUp(email, password, name) else cloud.signIn(email, password)
                password = ""
            } catch (e: CloudException) {
                message = e.message
            } catch (e: Exception) {
                message = "Algo deu errado: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val field = Modifier.fillMaxWidth().onFocusChanged { app.ui.typing = it.isFocused }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val current = session
        if (current == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ApexChip("Entrar", !signUp, { signUp = false; message = null })
                ApexChip("Criar conta", signUp, { signUp = true; message = null })
            }
            OutlinedTextField(
                email, { email = it }, field, singleLine = true, label = { Text("E-mail") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            if (signUp) OutlinedTextField(name, { name = it }, field, singleLine = true, label = { Text("Como quer ser chamado (opcional)") })
            OutlinedTextField(
                password, { password = it }, field, singleLine = true,
                label = { Text(if (signUp) "Senha (mínimo 8 caracteres)" else "Senha") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ApexColors.Accent) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(if (busy) "Aguarde…" else if (signUp) "Criar conta" else "Entrar", { submit() }, primary = true)
                if (!signUp) TextButton({
                    if (email.isBlank()) message = "Digite o e-mail para receber o link."
                    else scope.launch {
                        message = try {
                            cloud.forgot(email)
                            "Se existir uma conta com esse e-mail, enviamos o link para trocar a senha."
                        } catch (e: CloudException) {
                            e.message
                        }
                    }
                }) { Text("Esqueci minha senha", color = ApexColors.Muted) }
                TextButton({ advanced = !advanced }) { Text("Servidor", color = ApexColors.Faint) }
            }
            if (advanced) {
                OutlinedTextField(
                    settings.serverUrl, { v -> app.data.updateSettings { it.copy(serverUrl = v) } }, field, singleLine = true,
                    label = { Text("Endereço do servidor (padrão: $DEFAULT_SERVER_URL)") },
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(current.user.avatarUrl, current.user.displayName ?: current.user.email, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(current.user.displayName ?: current.user.email, style = MaterialTheme.typography.titleSmall)
                    Text(current.user.email, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
                }
                Text(
                    if (current.user.emailVerified) "E-mail confirmado" else "E-mail ainda não confirmado",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current.user.emailVerified) ApexColors.Muted else ApexColors.Accent,
                )
            }
            val line = when (val s = status) {
                SyncStatus.Syncing -> "Sincronizando…"
                is SyncStatus.Error -> s.message
                else -> syncState.lastSyncAt?.let { "Sincronizado ${relativeTime(it)}" } ?: "Aguardando a primeira sincronização"
            }
            Text(
                line, style = MaterialTheme.typography.bodySmall,
                color = if (status is SyncStatus.Error) ApexColors.Accent else ApexColors.Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Sincronizar agora", { cloud.syncNow() }, icon = Icons.Rounded.Sync)
                ActionButton("Sair", { confirmSignOut = true }, icon = Icons.Rounded.Logout)
                ActionButton("Apagar conta", { confirmDelete = true }, icon = Icons.Rounded.Delete)
            }
        }
    }

    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sair da conta do Apex?") },
        text = { Text("Você pode manter os seus dados neste aparelho ou apagá-los (eles continuam guardados na conta).") },
        confirmButton = { TextButton({ confirmSignOut = false; scope.launch { cloud.signOut(wipeLocal = false) } }) { Text("Sair e manter os dados") } },
        dismissButton = {
            Row {
                TextButton({ confirmSignOut = false }) { Text("Cancelar") }
                TextButton({ confirmSignOut = false; scope.launch { cloud.signOut(wipeLocal = true) } }) { Text("Sair e apagar daqui", color = ApexColors.Accent) }
            }
        },
        containerColor = ApexColors.SurfaceHigh,
    )

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false; deletePassword = "" },
        title = { Text("Apagar a conta do Apex?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Isso apaga a conta e tudo o que está guardado no servidor. Não dá para desfazer. Digite a senha para confirmar.")
                OutlinedTextField(
                    deletePassword, { deletePassword = it }, field, singleLine = true, label = { Text("Senha") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    try {
                        cloud.deleteAccount(deletePassword)
                        app.toast("Conta apagada")
                    } catch (e: CloudException) {
                        app.toast(e.message ?: "Não foi possível apagar a conta")
                    }
                    confirmDelete = false
                    deletePassword = ""
                }
            }) { Text("Apagar", color = ApexColors.Accent) }
        },
        dismissButton = { TextButton({ confirmDelete = false; deletePassword = "" }) { Text("Cancelar") } },
        containerColor = ApexColors.SurfaceHigh,
    )

    foreign?.let { f ->
        AlertDialog(
            onDismissRequest = { cloud.cancelForeign() },
            title = { Text("Este aparelho tem dados de outra conta") },
            text = { Text("Você entrou como ${f.newUserEmail}, mas aqui ainda estão inscrições e histórico de outra conta. Quer juntar tudo na conta nova ou começar do zero?") },
            confirmButton = { TextButton({ cloud.resolveForeign(merge = true) }) { Text("Juntar") } },
            dismissButton = {
                Row {
                    TextButton({ cloud.cancelForeign() }) { Text("Cancelar") }
                    TextButton({ cloud.resolveForeign(merge = false) }) { Text("Começar do zero", color = ApexColors.Accent) }
                }
            },
            containerColor = ApexColors.SurfaceHigh,
        )
    }
}
