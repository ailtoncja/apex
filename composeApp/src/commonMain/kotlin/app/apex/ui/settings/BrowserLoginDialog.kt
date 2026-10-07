package app.apex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.model.Platform
import app.apex.theme.ApexColors
import app.apex.ui.components.ApexChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Escolhe um navegador instalado, abre a página de login e espera o usuário entrar. */
@Composable
fun BrowserLoginDialog(platform: Platform, onClose: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val browsers = remember { app.system.installedBrowsers }
    var selected by remember {
        mutableStateOf(app.system.defaultBrowserId?.takeIf { id -> browsers.any { it.id == id } } ?: browsers.firstOrNull()?.id)
    }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun close() {
        job?.cancel()
        onClose()
    }

    fun start() {
        val browser = selected ?: return
        running = true
        status = "Abrindo o navegador…"
        job = scope.launch {
            try {
                val account = app.system.browserLogin(platform, browser) { status = it }
                if (account == null) {
                    status = "Não consegui ler o login. Se fechou a janela, tente de novo."
                    running = false
                    return@launch
                }
                app.data.setAccount(account, platform)
                app.refreshAccountProfile(platform)
                val imported = runCatching { app.importFollows(platform) }
                app.toast(
                    imported.fold(
                        onSuccess = { "${platform.label} conectado • ${it.summary}" },
                        onFailure = { "${platform.label} conectado, mas não consegui importar: ${it.message}" },
                    ),
                )
                app.screens.subs.refreshFeed()
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Algo deu errado: ${e.message}"
                running = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Entrar no ${platform.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (browsers.isEmpty()) {
                    Text("Não encontrei um navegador compatível (Firefox, Chrome, Edge, Brave, Vivaldi ou Opera) neste computador.")
                } else {
                    Text(
                        "O Apex abre o navegador que você já tem, você entra na sua conta e ele importa o login. " +
                            "Sua senha nunca passa pelo Apex.",
                        style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        browsers.forEach { b -> ApexChip(b.label, selected == b.id, { if (!running) selected = b.id }) }
                    }
                    browsers.firstOrNull { it.id == selected }?.let {
                        Text(it.note, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
                    }
                    status?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ApexColors.Accent)
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (browsers.isNotEmpty()) TextButton({ if (!running) start() }) {
                Text(if (running) "Aguardando…" else "Abrir o navegador", color = if (running) ApexColors.Muted else ApexColors.Accent)
            }
        },
        dismissButton = { TextButton({ close() }) { Text(if (running) "Cancelar" else "Fechar") } },
        containerColor = ApexColors.SurfaceHigh,
        modifier = Modifier.padding(8.dp),
    )
}
