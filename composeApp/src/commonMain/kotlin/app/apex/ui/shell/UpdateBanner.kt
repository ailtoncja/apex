package app.apex.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.theme.ApexColors
import app.apex.ui.components.ActionButton
import app.apex.update.UpdateState

/** Aviso de versão nova no topo do app: aparece quando há atualização para baixar ou pronta para instalar. */
@Composable
fun UpdateBanner() {
    val app = LocalApp.current
    val updater = app.system.updater
    val state by updater.state.collectAsState()
    var dismissed by remember { mutableStateOf<String?>(null) }

    val (version, text) = when (val s = state) {
        is UpdateState.Available -> s.info.version to "Nova versão ${s.info.version} do Apex disponível."
        is UpdateState.Downloading -> s.info.version to "Baixando a versão ${s.info.version}… ${s.percent}%"
        is UpdateState.Ready -> s.info.version to "A versão ${s.info.version} está pronta. O Apex reinicia para instalar."
        else -> return
    }
    if (dismissed == version && state !is UpdateState.Ready) return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(ApexColors.SurfaceHigh, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        when (val s = state) {
            is UpdateState.Available ->
                if (updater.canInstall) ActionButton("Baixar", { updater.download() }, icon = Icons.Rounded.Download, primary = true)
                else ActionButton("Ver novidades", { app.system.openUrl(s.info.pageUrl) }, icon = Icons.Rounded.Download)
            is UpdateState.Ready -> ActionButton("Reiniciar e atualizar", { updater.installAndRestart() }, icon = Icons.Rounded.Refresh, primary = true)
            else -> {}
        }
        if (state !is UpdateState.Downloading) TextButton({ dismissed = version }) { Text("Depois", color = ApexColors.Muted) }
    }
}
