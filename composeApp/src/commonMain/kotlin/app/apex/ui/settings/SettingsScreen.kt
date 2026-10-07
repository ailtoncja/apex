package app.apex.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.apex.LocalApp
import app.apex.data.Account
import app.apex.model.Platform
import app.apex.model.AppSettings
import app.apex.source.ExtractorState
import app.apex.theme.ApexColors
import app.apex.update.UpdateState
import app.apex.theme.color
import app.apex.ui.components.ActionButton
import app.apex.ui.components.ApexChip
import app.apex.ui.components.Avatar
import app.apex.ui.components.Divider
import app.apex.ui.components.PlatformTag
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val settings by app.data.settings.collectAsState()
    val accounts by app.data.accounts.collectAsState()
    val subs by app.data.subscriptions.collectAsState()
    val history by app.data.history.collectAsState()
    val scope = rememberCoroutineScope()
    val engineState by app.extractor.state.collectAsState()
    var engineMessage by remember { mutableStateOf("") }

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 880.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

            Section("Conta do Apex", "Sincroniza inscrições, histórico, playlists e ajustes em todos os seus aparelhos.") {
                CloudSection()
            }

            Section("Contas das plataformas", "Entre para importar suas inscrições, curtir, comentar e escrever no chat.") {
                Platform.entries.forEach { p ->
                    AccountRow(p, accounts[p.name])
                    if (p != Platform.entries.last()) Divider()
                }
            }

            Section("Reprodução") {
                Setting("Qualidade padrão", "Máxima resolução ao abrir um vídeo (lives usam automático)") {
                    ChoiceMenu(
                        listOf(0 to "Automática (até 1080p)", 2160 to "2160p (4K)", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p"),
                        settings.defaultQuality,
                    ) { v -> app.data.updateSettings { it.copy(defaultQuality = v) } }
                }
                Setting("Velocidade padrão") {
                    ChoiceMenu(listOf(0.75f to "0,75x", 1f to "Normal", 1.25f to "1,25x", 1.5f to "1,5x", 2f to "2x"), settings.defaultRate) { v ->
                        app.data.updateSettings { it.copy(defaultRate = v) }
                    }
                }
                Setting("Reprodução automática", "Toca o próximo vídeo ao terminar") {
                    Toggle(settings.autoplayNext) { v -> app.data.updateSettings { it.copy(autoplayNext = v) } }
                }
                Setting("Avisar quando entrarem ao vivo", "Mostra um aviso quando um canal que você segue começa a transmitir") {
                    Toggle(settings.liveAlerts) { v -> app.data.updateSettings { it.copy(liveAlerts = v) } }
                }
                Setting("Continuar de onde parei", "Retoma vídeos longos pelo ponto em que você saiu") {
                    Toggle(settings.rememberPosition) { v -> app.data.updateSettings { it.copy(rememberPosition = v) } }
                }
                Setting("Legendas por padrão", "Liga as legendas do idioma escolhido quando existirem") {
                    Toggle(settings.subtitlesOnByDefault) { v -> app.data.updateSettings { it.copy(subtitlesOnByDefault = v) } }
                }
                Setting("Idioma das legendas") {
                    ChoiceMenu(listOf("pt" to "Português", "en" to "Inglês", "es" to "Espanhol", "ja" to "Japonês"), settings.subtitleLang) { v ->
                        app.data.updateSettings { it.copy(subtitleLang = v) }
                    }
                }
                Setting("Decodificação por hardware", "Usa a placa de vídeo. Vale na próxima vez que abrir o app") {
                    Toggle(settings.preferHardwareDecode) { v -> app.data.updateSettings { it.copy(preferHardwareDecode = v) } }
                }
                Setting("Chat nas lives", "Mostra o chat ao lado da transmissão") {
                    Toggle(settings.showChat) { v -> app.data.updateSettings { it.copy(showChat = v) } }
                }
            }

            Section("Lives") {
                Setting("Ocultar conteúdo +18 na Kick", "Esconde transmissões marcadas como adultas") {
                    Toggle(settings.hideMature) { v -> app.data.updateSettings { it.copy(hideMature = v) } }
                }
                Setting("Idioma na Twitch") {
                    ChoiceMenu(listOf("PT" to "Português", "EN" to "Inglês", "ES" to "Espanhol"), settings.twitchLanguage) { v ->
                        app.data.updateSettings { it.copy(twitchLanguage = v) }
                    }
                }
                Setting("Idioma na Kick") {
                    ChoiceMenu(listOf("pt" to "Português", "en" to "Inglês", "es" to "Espanhol"), settings.kickLanguage) { v ->
                        app.data.updateSettings { it.copy(kickLanguage = v) }
                    }
                }
            }

            Section("Canais ocultos", "Vídeos desses canais deixam de aparecer nas listas.") {
                if (settings.blockedChannels.isEmpty()) {
                    Text("Nenhum canal oculto.", color = ApexColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                settings.blockedChannels.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(key.substringAfter(':'), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        PlatformTag(runCatching { Platform.valueOf(key.substringBefore(':')) }.getOrDefault(Platform.YouTube))
                        ActionButton("Mostrar de novo", { app.data.unblockChannel(key) })
                    }
                }
            }

            Section("Atualizações do Apex", "O Apex confere se saiu versão nova ao abrir e a cada 6 horas. Nada é instalado sem a assinatura do projeto bater.") {
                val updater = app.system.updater
                val update by updater.state.collectAsState()
                val line = when (val u = update) {
                    UpdateState.Idle -> "Versão ${updater.currentVersion}"
                    UpdateState.Checking -> "Versão ${updater.currentVersion} • procurando atualização…"
                    UpdateState.UpToDate -> "Versão ${updater.currentVersion} • você está com a versão mais nova"
                    is UpdateState.Available -> "Versão ${updater.currentVersion} • a ${u.info.version} está disponível"
                    is UpdateState.Downloading -> "Baixando a versão ${u.info.version}… ${u.percent}%"
                    is UpdateState.Ready -> "A versão ${u.info.version} está pronta para instalar"
                    is UpdateState.Failed -> u.message
                }
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (update is UpdateState.Failed) ApexColors.Accent else ApexColors.Muted)
                (update as? UpdateState.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it.lines().filter { l -> l.isNotBlank() }.take(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
                }
                Setting("Baixar atualizações sozinho", "Baixa e confere a versão nova em segundo plano; você escolhe quando reiniciar") {
                    Toggle(settings.autoUpdate) { v -> app.data.updateSettings { it.copy(autoUpdate = v) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Verificar agora", { updater.check(manual = true) }, icon = Icons.Rounded.Refresh)
                    when (val u = update) {
                        is UpdateState.Available ->
                            if (updater.canInstall) ActionButton("Baixar versão ${u.info.version}", { updater.download() }, icon = Icons.Rounded.Download, primary = true)
                            else ActionButton("Abrir a página da versão", { app.system.openUrl(u.info.pageUrl) }, icon = Icons.Rounded.Download)
                        is UpdateState.Ready -> ActionButton("Reiniciar e atualizar", { updater.installAndRestart() }, icon = Icons.Rounded.Refresh, primary = true)
                        else -> {}
                    }
                }
            }

            Section("Mecanismo de vídeo (yt-dlp)", "Resolve os vídeos do YouTube. Atualize se o YouTube parar de funcionar.") {
                val status = when (val s = engineState) {
                    ExtractorState.Checking -> "Verificando…"
                    ExtractorState.Ready -> "Instalado e pronto"
                    is ExtractorState.Installing -> s.message
                    is ExtractorState.Missing -> s.reason
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
                if (engineMessage.isNotEmpty()) Text(engineMessage, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Reinstalar", {
                        engineMessage = "Verificando…"
                        scope.launch { engineMessage = runCatching { app.extractor.ensureReady(); "Pronto." }.getOrElse { it.message ?: "Falhou" } }
                    }, icon = Icons.Rounded.Download)
                    ActionButton("Atualizar yt-dlp", {
                        engineMessage = "Atualizando…"
                        scope.launch { engineMessage = runCatching { app.extractor.updateEngine() }.getOrElse { it.message ?: "Falhou" } }
                    }, icon = Icons.Rounded.Refresh)
                }
            }

            Section("Dados") {
                Text("${subs.size} inscrições • ${history.size} itens no histórico", color = ApexColors.Muted, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Limpar histórico", { app.data.clearHistory(); app.toast("Histórico limpo") }, icon = Icons.Rounded.Delete)
                    ActionButton("Limpar buscas", { app.data.updateSettings { it.copy(searchHistory = emptyList()) }; app.toast("Buscas limpas") }, icon = Icons.Rounded.Delete)
                }
            }

            Section("Atalhos do teclado") {
                listOf(
                    "Espaço ou K" to "Pausar e continuar", "F" to "Tela cheia", "T" to "Modo cinema", "M" to "Silenciar",
                    "← →" to "Voltar e avançar 5 s", "J L" to "Voltar e avançar 10 s", "↑ ↓" to "Volume",
                    "0 a 9" to "Pular para 0% a 90%", "C" to "Legendas", "Shift + N" to "Próximo vídeo", "Esc" to "Sair da tela cheia",
                ).forEach { (k, d) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(k, Modifier.widthIn(min = 120.dp), style = MaterialTheme.typography.labelLarge)
                        Text(d, style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
                    }
                }
            }
            Text("Apex • feito para assistir", color = ApexColors.Faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(ApexColors.Surface, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
        }
        content()
    }
}

@Composable
private fun Setting(title: String, subtitle: String? = null, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted)
        }
        control()
    }
}

@Composable
private fun Toggle(value: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = value, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = ApexColors.Accent, checkedThumbColor = Color.White),
    )
}

@Composable
private fun <T> ChoiceMenu(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ApexChip(options.firstOrNull { it.first == selected }?.second ?: "—", false, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

@Composable
private fun AccountRow(platform: Platform, account: Account?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (account != null) Avatar(account.avatarUrl, account.displayName, 40.dp)
            else Box(Modifier.padding(4.dp).background(platform.color(), RoundedCornerShape(50)).padding(14.dp))
            Column(Modifier.weight(1f)) {
                Text(platform.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    account?.let { "Conectado como ${it.displayName}" } ?: "Não conectado",
                    style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                )
            }
            if (account == null) {
                ActionButton("Entrar pelo navegador", { login = true }, icon = Icons.Rounded.Login, primary = true)
            } else {
                ActionButton("Importar inscrições", {
                    scope.launch {
                        message = "Importando…"
                        message = runCatching { app.importFollows(platform).summary }.getOrElse { "Não foi possível importar: ${it.message}" }
                        app.screens.subs.refreshFeed()
                    }
                }, icon = Icons.Rounded.Download)
                ActionButton("Sair", { app.signOut(platform) }, icon = Icons.Rounded.Logout)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted) }
        if (account != null && platform == Platform.Twitch) TwitchTurboStatus(account)
    }
    if (login) BrowserLoginDialog(platform) { login = false }
}

/** Se a conta da Twitch tem Turbo (sem anúncios) e como trazer a conta certa do navegador. */
@Composable
private fun TwitchTurboStatus(account: Account) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val turbo by app.twitchTurbo.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().background(ApexColors.SurfaceHigh, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (turbo) {
            true -> Text("Twitch Turbo ativo em ${account.displayName}: as lives tocam sem anúncios.", style = MaterialTheme.typography.bodyMedium, color = ApexColors.OnSurface)
            false -> {
                Text(
                    "A conta ${account.displayName} não tem o Twitch Turbo, então a Twitch mostra anúncios nas lives (em geral a cada 4 ou 5 minutos).",
                    style = MaterialTheme.typography.bodyMedium, color = ApexColors.OnSurface,
                )
                Text(
                    "O Apex já usa a sua sessão da Twitch no player; quem tem Turbo, ou é inscrito no canal, não vê anúncios. Se o Turbo está em outra conta, " +
                        "entre com ela na Twitch pelo navegador e toque em Sincronizar.",
                    style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted,
                )
            }
            null -> Text("Conferindo o Twitch Turbo…", style = MaterialTheme.typography.bodyMedium, color = ApexColors.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Sincronizar com o navegador", {
                scope.launch {
                    message = "Sincronizando…"
                    val changed = runCatching { app.refreshAccountFromBrowser(Platform.Twitch) }.getOrDefault(false)
                    app.refreshTwitchTurbo()
                    message = if (changed) "Sessão atualizada com a conta que está no navegador." else "A sessão já estava igual à do navegador."
                }
            })
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ApexColors.Muted) }
        }
    }
}
