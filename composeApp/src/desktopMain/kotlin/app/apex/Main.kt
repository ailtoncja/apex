package app.apex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
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
import java.io.File

private object AppIcon : Painter() {
    override val intrinsicSize = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawRoundRect(Color(0xFF0A0A0F), cornerRadius = CornerRadius(size.width * 0.22f))
        drawCircle(ApexColors.Accent, radius = size.minDimension * 0.27f)
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
            onFailure = {
                ApexTheme {
                    Box(Modifier.fillMaxSize().background(ApexColors.Background).padding(32.dp), Alignment.Center) {
                        Text("Não foi possível iniciar o libVLC. Instale o VLC 3.x (64 bits).\n${it.message}", color = Color.White)
                    }
                }
            },
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
