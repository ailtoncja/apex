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
import androidx.compose.ui.platform.LocalWindowInfo
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
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.nav.LibraryTab
import app.apex.nav.LiveFilter
import app.apex.source.SearchFilters
import app.apex.source.SearchType
import app.apex.state.SearchKind
import app.apex.state.withKind
import app.apex.state.YOUTUBE_TOPIC_CATEGORIES
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
import kotlinx.coroutines.launch
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
                    colors = ButtonDefaults.buttonColors(containerColor = ApexColors.Accent, contentColor = ApexColors.OnAccent),
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
    // `APEX_TIMING=1`: grava em `timing.txt` (na pasta de dados) os tempos de cada etapa ao abrir um vídeo.
    // `APEX_FRAMES=1`: grava em `frames.txt` a fluidez (quadros, GC, CPU, vídeo) a cada 2 s.
    if (System.getenv("APEX_FRAMES") == "1") app.apex.util.FrameMeter.enabled = true
    if (System.getenv("APEX_TIMING") == "1") {
        val file = File(System.getenv("APEX_DATA") ?: ".", "timing.txt")
        app.apex.util.Timing.sink = { line -> runCatching { file.appendText(line + System.lineSeparator()) } }
    }
    // No Windows o Java usa um soquete interno (AF_UNIX) numa pasta temporária para cada Selector (rede, Ktor). Em alguns PCs a pasta
    // padrão (AppData\Local\Temp) não aceita isso e a rede inteira dá "Connect timeout". Uma pasta simples só do Apex resolve.
    // Precisa ser definido antes de qualquer uso de rede.
    File(System.getProperty("user.home"), ".apex/tmp").also { it.mkdirs() }.takeIf { it.isDirectory }?.let {
        System.setProperty("jdk.net.unixdomain.tmpdir", it.absolutePath)
        System.setProperty("java.io.tmpdir", it.absolutePath)
    }
    runCatching { app.apex.source.CookieJar.deleteStale() }
    // A silhueta do carro dos temas Subaru, se a pessoa pôs uma imagem (ver RallyArt).
    runCatching { app.apex.ui.shell.RallyArt.silhouette = app.apex.ui.shell.loadRallySilhouette() }
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
    // `-Dapex.train=1` (só o Apex.cmd usa): abre numa janela fora da tela, passeia pelas telas e fecha sozinho, para o JVM aprender como o app
    // roda e montar o cache de inicialização (veja o Apex.cmd). Não mexe nos dados da pessoa: o Apex.cmd aponta `apex.data` para uma pasta à parte.
    val training = remember { System.getProperty("apex.train") == "1" }
    val windowState = rememberWindowState(
        size = DpSize(1360.dp, 820.dp),
        position = if (training) WindowPosition(-30000.dp, 0.dp) else WindowPosition.Aligned(Alignment.Center),
    )

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
                scope, File(dataDir, "updates"), autoDownload = { data.settings.value.autoUpdate && !training },
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
            AppContainer(scope, system, data, extractor, player, newPlayer = { VlcPlayerController(hardwareDecode = data.settings.value.preferHardwareDecode) })
                .also { navigateFromEnv(it) }
        }
    }

    // Tela cheia como o F11 (sem barra de título nem bordas, cobrindo o monitor): ver WindowsFullscreen.
    val requested = boot.getOrNull()?.system?.fullscreen?.collectAsState()?.value ?: false
    val fullscreenController = remember { FullscreenController(windowState) }

    // Atalho para testes: `APEX_FULLSCREEN=1` abre já em tela cheia (F11), `player` na tela cheia do player; `cycle` entra e, depois de alguns segundos, sai.
    LaunchedEffect(Unit) {
        val app = boot.getOrNull() ?: return@LaunchedEffect
        if (training) {
            launch {
                trainWarmup(app)
                app.data.flush()
                app.player.release()
                exitApplication()
            }
        }
        // `APEX_SEEK=1800@15`: 15 s depois de abrir, pula o vídeo para os 1800 s (para ensaiar o salto na barra de progresso).
        System.getenv("APEX_SEEK")?.takeIf { it.isNotBlank() }?.let { spec ->
            launch {
                kotlinx.coroutines.delay((spec.substringAfter('@', "15").toLongOrNull() ?: 15) * 1_000)
                spec.substringBefore('@').toLongOrNull()?.let { app.player.seekTo(it * 1_000) }
            }
        }
        when (System.getenv("APEX_FULLSCREEN")) {
            "1" -> app.toggleWindowFullscreen()
            "player" -> {
                // Espera a primeira tela (a navegação do APEX_START) assentar; a tela cheia do player só vale na página do vídeo.
                kotlinx.coroutines.delay(1_500)
                app.setPlayerFullscreen(true)
            }
            "cycle" -> {
                kotlinx.coroutines.delay(2_000)
                app.toggleWindowFullscreen()
                kotlinx.coroutines.delay(6_000)
                app.toggleWindowFullscreen()
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
        if (app.apex.util.FrameMeter.enabled) LaunchedEffect(Unit) {
            app.apex.util.FrameMeter.run(File(FileStore.defaultDir(), "frames.txt"))
        }
        boot.fold(
            onSuccess = { app ->
                // Voltou para a janela (de outro programa): confere na hora quem entrou ao vivo enquanto a pessoa estava fora.
                val focused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(focused) { if (focused) app.liveWatcher.refreshIfStale(5_000) }
                CompositionLocalProvider(LocalApp provides app) { ApexApp() }
            },
            onFailure = { StartupProblem(it) },
        )
    }
}

/** A volta pelas telas que o treino do cache de inicialização faz (ver `-Dapex.train`). */
private suspend fun trainWarmup(app: AppContainer) {
    kotlinx.coroutines.delay(4_000)
    val steps = listOf(
        Route.Live(LiveFilter.All), Route.Subscriptions, Route.Library(LibraryTab.History), Route.Library(LibraryTab.WatchLater),
        Route.Library(LibraryTab.Liked), Route.Library(LibraryTab.Clips), Route.Library(LibraryTab.Playlists), Route.Settings, Route.Home,
    )
    for (route in steps) {
        app.nav.goRoot(route)
        kotlinx.coroutines.delay(1_800)
    }
    app.screens.home.selected = 2
    kotlinx.coroutines.delay(1_500)
    app.search("rally")
    kotlinx.coroutines.delay(5_000)
    app.nav.goRoot(Route.Home)
    kotlinx.coroutines.delay(1_000)
}

/** Atalho para testes: `APEX_START=search:rally`, `watch:ID`, `link:URL`, `live`, `channel:UC…`, `library`, `settings`. */
private fun navigateFromEnv(app: AppContainer) {
    if (System.getenv("APEX_THEATER") == "1") app.ui.theater = true
    val start = System.getenv("APEX_START")?.takeIf { it.isNotBlank() } ?: return
    val kind = start.substringBefore(':')
    val arg = start.substringAfter(':', "")
    when (kind) {
        // `search:consulta` ou `search:consulta|twitch,kick|subs` (já com essas plataformas e "Inscrições" ligadas).
        "search" -> {
            val parts = arg.split('|')
            val query = parts[0].trim()
            // O quarto trecho escolhe a aba: videos, lives, canais ou playlists.
            val kindName = parts.getOrNull(3).orEmpty()
            val filters = SearchFilters(type = SearchType.Any).withKind(
                when (kindName) { "videos" -> SearchKind.Videos; "lives" -> SearchKind.Lives; "canais" -> SearchKind.Channels; "playlists" -> SearchKind.Playlists; else -> SearchKind.All },
            )
            val state = app.screens.search(query, filters)
            state.platforms = parts.getOrNull(1).orEmpty().split(',').mapNotNull { n -> Platform.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }.toSet()
            state.onlySubs = parts.getOrNull(2) == "subs"
            app.search(query, filters)
        }
        "watch" -> app.openMedia(Media(Platform.YouTube, arg, "Carregando…", url = "https://www.youtube.com/watch?v=$arg"))
        "ytlive" -> app.openMedia(Media(Platform.YouTube, arg, arg, isLive = true, url = "https://www.youtube.com/watch?v=$arg"))
        "twitch" -> app.openMedia(Media(Platform.Twitch, arg, arg, isLive = true, url = "https://www.twitch.tv/$arg"))
        "multi" -> app.nav.goRoot(Route.Multi)
        "kick" -> app.openMedia(Media(Platform.Kick, arg, arg, isLive = true, url = "https://kick.com/$arg"))
        // `live` ou `live:youtube,twitch` (já com essas plataformas ligadas).
        "live" -> {
            app.screens.live.platforms = arg.split(',').mapNotNull { n -> Platform.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }.toSet()
            app.nav.goRoot(Route.Live(LiveFilter.All))
        }
        // `channel:UC…` abre o canal do YouTube; `channel:UC…:2` já na aba 2 (Playlists).
        // `channel:UC…:2:rock` abre o canal na aba 2 já com "rock" no campo de pesquisar.
        "channel" -> {
            val channel = Channel(Platform.YouTube, arg.substringBefore(':'), arg.substringBefore(':'))
            arg.split(':').getOrNull(2)?.takeIf { it.isNotBlank() }?.let { app.screens.channel(channel).updateQuery(it) }
            app.nav.push(Route.ChannelPage(channel, arg.split(':').getOrNull(1)?.toIntOrNull() ?: 0))
        }
        "library" -> app.nav.goRoot(Route.Library(LibraryTab.History))
        "clips" -> app.nav.goRoot(Route.Library(LibraryTab.Clips))
        "playlist" -> app.nav.push(Route.RemotePlaylist(arg, "Playlist"))
        // `category:twitch:Just Chatting` ou `category:kick:just-chatting` abre a categoria.
        "category" -> {
            val platform = when (arg.substringBefore(':')) { "kick" -> Platform.Kick; "youtube" -> Platform.YouTube; else -> Platform.Twitch }
            val name = arg.substringAfter(':')
            // No YouTube: `category:youtube:musica` (assunto) ou `category:youtube:game:Minecraft` (jogo).
            val category = if (platform != Platform.YouTube) LiveCategory(name, name, null, null)
            else YOUTUBE_TOPIC_CATEGORIES.firstOrNull { it.id == "yt:$name" } ?: LiveCategory("yt:" + name, name.substringAfter(':'), null, null)
            app.nav.push(Route.Category(platform, category))
        }
        // `tchannel:gaules:2` abre o canal da Twitch na aba 2 (Clipes); `kchannel:` é o da Kick.
        "tchannel", "kchannel" -> {
            val platform = if (kind == "tchannel") Platform.Twitch else Platform.Kick
            val login = arg.substringBefore(':')
            app.nav.push(Route.ChannelPage(Channel(platform, login, login), arg.substringAfter(':', "0").toIntOrNull() ?: 0))
        }
        // `home:2` abre o início já no assunto de número 2 (Música).
        "home" -> {
            app.screens.home.selected = arg.toIntOrNull() ?: 0
            app.nav.goRoot(Route.Home)
        }
        "settings" -> app.nav.goRoot(Route.Settings)
        "subs" -> app.nav.goRoot(Route.Subscriptions)
        "link" -> app.openLink(arg)
    }
}
