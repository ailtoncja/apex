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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.apex.update.DesktopUpdater
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
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
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

/**
 * `APEX_SELFTEST=1`: confere, sem abrir janela, o que o app empacotado precisa para funcionar (pasta temporária, rede e o VLC)
 * e escreve o resultado em `selftest.txt` na pasta de dados. Serve para validar o pacote antes de distribuir.
 */
/**
 * Endereços da atualização. As variáveis `APEX_UPDATE_FEED` e `APEX_UPDATE_PREFIX` só servem para ensaiar uma atualização contra um servidor
 * de teste: quem as mudar não consegue instalar nada, porque a assinatura continua tendo de bater com a chave do projeto.
 */
private fun updateFeed() = System.getenv("APEX_UPDATE_FEED")?.takeIf { it.isNotBlank() } ?: DesktopUpdater.FEED_URL
private fun updatePrefix() = System.getenv("APEX_UPDATE_PREFIX")?.takeIf { it.isNotBlank() } ?: DesktopUpdater.TRUSTED_PREFIX

private fun selfTest(updateRehearsal: Boolean = false): Nothing {
    val dir = FileStore.defaultDir().also { it.mkdirs() }
    val out = StringBuilder()
    fun step(name: String, block: () -> String) {
        out.appendLine("$name: " + runCatching(block).getOrElse { "FALHOU - ${it::class.simpleName}: ${it.message}" })
    }
    step("java") { "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})" }
    step("tmpdir") { System.getProperty("java.io.tmpdir") }
    step("atualização") { "versão ${app.apex.BuildInfo.VERSION}; modo ${app.apex.update.InstallMode.detect()}; exe ${app.apex.update.InstallMode.currentExe()?.name}" }
    step("rede") {
        kotlinx.coroutines.runBlocking {
            val status = Http.client.get("https://apex-server-mg5l.onrender.com/health") { timeout { requestTimeoutMillis = 90_000 } }
            "HTTP ${status.status.value}"
        }
    }
    step("vlc") { VlcPlayerController(hardwareDecode = false).also { it.release() }.let { "libVLC carregou" } }
    if (updateRehearsal) {
        // Ensaio da atualização de ponta a ponta: confere, baixa, verifica a assinatura e reinicia como o app faria.
        val updater = DesktopUpdater(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), File(dir, "updates"), { true },
            feedUrl = updateFeed(), trustedPrefix = updatePrefix(),
        )
        kotlinx.coroutines.runBlocking { updater.checkNow(manual = true) }
        out.appendLine("ensaio da atualização: ${updater.state.value}")
        File(dir, "selftest.txt").writeText(out.toString())
        if (updater.state.value is app.apex.update.UpdateState.Ready) updater.installAndRestart()
        kotlin.system.exitProcess(if (updater.state.value is app.apex.update.UpdateState.Ready) 0 else 1)
    }
    File(dir, "selftest.txt").writeText(out.toString())
    kotlin.system.exitProcess(if ("FALHOU" in out) 1 else 0)
}

fun main(args: Array<String>) {
    // No Windows o Java usa um soquete interno (AF_UNIX) numa pasta temporária para cada Selector (rede, Ktor). Em alguns PCs a pasta
    // padrão (AppData\Local\Temp) não aceita isso e a rede inteira dá "Connect timeout". Uma pasta simples só do Apex resolve.
    // Precisa ser definido antes de qualquer uso de rede.
    File(System.getProperty("user.home"), ".apex/tmp").also { it.mkdirs() }.takeIf { it.isDirectory }?.let {
        System.setProperty("jdk.net.unixdomain.tmpdir", it.absolutePath)
        System.setProperty("java.io.tmpdir", it.absolutePath)
    }
    runCatching { app.apex.source.CookieJar.deleteStale() }
    when (System.getenv("APEX_SELFTEST")) {
        "1" -> selfTest()
        "update" -> selfTest(updateRehearsal = true)
    }
    // As listas mandam baixar as imagens antes de aparecerem (vão para o cache em disco, que as telas usam depois).
    app.apex.util.ImagePrefetch.enqueue = { urls ->
        val loader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        urls.forEach { url ->
            loader.enqueue(
                coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(url)
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).size(coil3.size.Size(480, 270)).build(),
            )
        }
    }
    runApp()
}

private fun runApp() = application {
    val dataDir = remember { FileStore.defaultDir() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val windowState = rememberWindowState(size = DpSize(1360.dp, 820.dp), position = WindowPosition.Aligned(Alignment.Center))

    setSingletonImageLoaderFactory { context ->
        // Baixa várias imagens ao mesmo tempo (o padrão é 8) e guarda mais no disco: a primeira entrega do Kick e da Twitch demora quase 1 s.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val downloads = Dispatchers.IO.limitedParallelism(24)
        ImageLoader.Builder(context)
            .fetcherCoroutineContext(downloads)
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
            val updater = DesktopUpdater(
                scope, File(dataDir, "updates"), autoDownload = { data.settings.value.autoUpdate },
                feedUrl = updateFeed(), trustedPrefix = updatePrefix(),
            )
            val system = DesktopSystem(accounts, updater)
            val extractor = YtDlpExtractor(bins, cookieHeader = { data.account(Platform.YouTube)?.credential })
            val player = VlcPlayerController(hardwareDecode = data.settings.value.preferHardwareDecode)
            // Ao instalar uma versão nova o app fecha: grava o que estava esperando e solta o player antes.
            updater.beforeExit = {
                data.flush()
                player.release()
            }
            AppContainer(scope, system, data, extractor, player).also { navigateFromEnv(it) }
        }
    }

    // Tela cheia como o F11 (sem barra de título nem bordas, cobrindo o monitor): ver WindowsFullscreen.
    val requested = boot.getOrNull()?.system?.fullscreen?.collectAsState()?.value ?: false
    val fullscreenController = remember { FullscreenController(windowState) }

    // Atalho para testes: `APEX_FULLSCREEN=1` abre já em tela cheia; `cycle` entra e, depois de alguns segundos, sai.
    LaunchedEffect(Unit) {
        val app = boot.getOrNull() ?: return@LaunchedEffect
        when (System.getenv("APEX_FULLSCREEN")) {
            "1" -> app.system.setFullscreen(true)
            "cycle" -> {
                kotlinx.coroutines.delay(2_000)
                app.system.setFullscreen(true)
                kotlinx.coroutines.delay(6_000)
                app.system.setFullscreen(false)
            }
        }
    }

    Window(
        onCloseRequest = {
            // Grava o que ainda estava esperando (as mudanças dos últimos instantes).
            boot.getOrNull()?.data?.flush()
            boot.getOrNull()?.player?.release()
            exitApplication()
        },
        title = "Apex",
        icon = AppIcon,
        state = windowState,
    ) {
        LaunchedEffect(requested) { fullscreenController.apply(window, requested) }
        boot.fold(
            onSuccess = { app ->
                CompositionLocalProvider(LocalApp provides app) { ApexApp() }
            },
            onFailure = { StartupProblem(it) },
        )
    }
}

/** Atalho para testes: `APEX_START=search:rally`, `watch:ID`, `link:URL`, `live`, `channel:UC…`, `library`, `settings`. */
private fun navigateFromEnv(app: AppContainer) {
    if (System.getenv("APEX_THEATER") == "1") app.ui.theater = true
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
        "clips" -> app.nav.goRoot(Route.Library(LibraryTab.Clips))
        // `tchannel:gaules:2` abre o canal da Twitch na aba 2 (Clipes); `kchannel:` é o da Kick.
        "tchannel", "kchannel" -> {
            val platform = if (kind == "tchannel") Platform.Twitch else Platform.Kick
            val login = arg.substringBefore(':')
            app.nav.push(Route.ChannelPage(Channel(platform, login, login), arg.substringAfter(':', "0").toIntOrNull() ?: 0))
        }
        "settings" -> app.nav.goRoot(Route.Settings)
        "subs" -> app.nav.goRoot(Route.Subscriptions)
        "link" -> app.openLink(arg)
    }
}
