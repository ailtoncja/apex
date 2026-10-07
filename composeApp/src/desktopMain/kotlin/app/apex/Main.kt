package app.apex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.LibraryTab
import app.apex.nav.LiveFilter
import app.apex.nav.Route
import app.apex.player.VlcPlayerController
import app.apex.source.Binaries
import app.apex.source.Http
import app.apex.source.YtDlpExtractor
import app.apex.theme.ApexColors
import app.apex.theme.ApexTheme
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath
import java.awt.Desktop
import java.io.File
import java.net.URI

private object AppIcon : Painter() {
    override val intrinsicSize = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawRoundRect(Color(0xFF0A0A0F), cornerRadius = CornerRadius(size.width * 0.22f))
        drawCircle(ApexColors.Accent, radius = size.minDimension * 0.27f)
    }
}

/** O Apex não conseguiu abrir. O motivo mais comum: o VLC (o player) não está instalado. */
@Composable
private fun StartupProblem(error: Throwable) {
    val vlc = error.message?.contains("vlc", ignoreCase = true) == true || error is UnsatisfiedLinkError
    ApexTheme {
        Column(
            Modifier.fillMaxSize().background(ApexColors.Background).padding(40.dp),
            Arrangement.Center, Alignment.CenterHorizontally,
        ) {
            Text(if (vlc) "O Apex precisa do VLC" else "Não foi possível abrir o Apex", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(
                if (vlc) "O player do Apex usa o VLC (versão de 64 bits), que é gratuito e não foi encontrado neste computador. Instale o VLC e abra o Apex de novo."
                else "Algo impediu o Apex de iniciar. Feche e abra de novo; se continuar, avise quem mantém o app e informe o texto abaixo.",
                color = ApexColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp),
            )
            if (vlc) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { runCatching { Desktop.getDesktop().browse(URI("https://www.videolan.org/vlc/")) } },
                    colors = ButtonDefaults.buttonColors(containerColor = ApexColors.Accent, contentColor = Color.White),
                ) { Text("Baixar o VLC") }
            }
            Spacer(Modifier.height(24.dp))
            Text(error.message ?: error.toString(), color = ApexColors.Faint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        }
    }
}

fun main() = application {
    val dataDir = remember { FileStore.defaultDir() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val windowState = rememberWindowState(size = DpSize(1360.dp, 820.dp), position = WindowPosition.Aligned(Alignment.Center))

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(Http.client)) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(File(dataDir, "images").toOkioPath()).maxSizeBytes(400L * 1024 * 1024).build() }
            .build()
    }

    val boot = remember {
        runCatching {
            val data = UserData(FileStore(dataDir), scope)
            val bins = Binaries(File(dataDir, "bin"))
            val accounts = DesktopAccounts(dataDir)
            val system = DesktopSystem(windowState, accounts)
            val extractor = YtDlpExtractor(bins, cookieFile = { accounts.youtubeCookieFile.takeIf { data.account(Platform.YouTube) != null } })
            val player = VlcPlayerController(hardwareDecode = data.settings.value.preferHardwareDecode)
            AppContainer(scope, system, data, extractor, player).also { navigateFromEnv(it) }
        }
    }

    Window(
        onCloseRequest = {
            boot.getOrNull()?.player?.release()
            exitApplication()
        },
        title = "Apex",
        icon = AppIcon,
        state = windowState,
    ) {
        boot.fold(
            onSuccess = { app ->
                CompositionLocalProvider(LocalApp provides app) { ApexApp() }
            },
            onFailure = { StartupProblem(it) },
        )
    }
}

/** Atalho para testes: `APEX_START=search:rally`, `watch:ID`, `live`, `channel:UC…`, `library`, `settings`. */
private fun navigateFromEnv(app: AppContainer) {
    val start = System.getenv("APEX_START")?.takeIf { it.isNotBlank() } ?: return
    val kind = start.substringBefore(':')
    val arg = start.substringAfter(':', "")
    when (kind) {
        "search" -> app.search(arg)
        "watch" -> app.openMedia(Media(Platform.YouTube, arg, "Carregando…", url = "https://www.youtube.com/watch?v=$arg"))
        "twitch" -> app.openMedia(Media(Platform.Twitch, arg, arg, isLive = true, url = "https://www.twitch.tv/$arg"))
        "kick" -> app.openMedia(Media(Platform.Kick, arg, arg, isLive = true, url = "https://kick.com/$arg"))
        "live" -> app.nav.goRoot(Route.Live(LiveFilter.All))
        "channel" -> app.openChannel(Channel(Platform.YouTube, arg, arg))
        "library" -> app.nav.goRoot(Route.Library(LibraryTab.History))
        "settings" -> app.nav.goRoot(Route.Settings)
        "subs" -> app.nav.goRoot(Route.Subscriptions)
    }
}
