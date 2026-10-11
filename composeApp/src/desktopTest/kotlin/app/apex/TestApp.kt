package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files

// Peças de mentira para montar o app inteiro nos testes de interface (sem rede, sem VLC, sem Windows).

internal class FakeUpdater : app.apex.update.Updater {
    override val currentVersion = "0"
    override val state = kotlinx.coroutines.flow.MutableStateFlow<app.apex.update.UpdateState>(app.apex.update.UpdateState.Idle)
    override val canInstall = false
    override fun check(manual: Boolean) {}
    override fun download() {}
    override fun installAndRestart() {}
}

internal class FakeSystem : SystemServices {
    override val updater = FakeUpdater()
    override val fullscreen = kotlinx.coroutines.flow.MutableStateFlow(false)
    override fun setFullscreen(on: Boolean) { fullscreen.value = on }
    override fun openUrl(url: String) {}
    override fun copyText(text: String) { copied = text }
    var copied: String? = null
    override fun saveDownload(fileName: String, text: String): String? = null
    override val installedBrowsers = emptyList<BrowserOption>()
    override val defaultBrowserId: String? = null
    override suspend fun browserLogin(platform: Platform, browserId: String, onStatus: (String) -> Unit): app.apex.data.Account? = null
    override fun signOut(platform: Platform) {}
    override suspend fun refreshAccount(account: app.apex.data.Account): app.apex.data.Account? = null
}

internal class FakePlayer2 : app.apex.player.PlayerController {
    override val state = kotlinx.coroutines.flow.MutableStateFlow(app.apex.player.PlayerState())
    /** O que mandaram tocar, na ordem. */
    val played = mutableListOf<app.apex.player.PlaySource>()
    var silenced = false
    var stops = 0
    var released = false
    override fun play(source: app.apex.player.PlaySource) { played += source }
    override fun togglePause() {}
    override fun pause() {}
    override fun resume() {}
    /** Para onde os atalhos pediram para pular (em ms). */
    val seeks = mutableListOf<Long>()
    override fun seekTo(positionMs: Long) { seeks += positionMs }
    override fun seekBy(deltaMs: Long) {}
    override fun setVolume(percent: Int) {}
    override fun setMuted(muted: Boolean) { silenced = muted }
    override fun setRate(rate: Float) { state.value = state.value.copy(rate = rate) }
    /** Quantas vezes pediram para voltar ao ao vivo. */
    var jumps = 0
    override fun jumpToLive() { jumps++ }
    override fun setSubtitle(url: String?) {}
    override fun stop() { stops++ }
    override fun release() { released = true }
    @androidx.compose.runtime.Composable override fun Video(modifier: androidx.compose.ui.Modifier) {}
}

internal class FakeExtractor2 : app.apex.source.Extractor {
    override val state = kotlinx.coroutines.flow.MutableStateFlow<app.apex.source.ExtractorState>(app.apex.source.ExtractorState.Ready)
    override suspend fun ensureReady() = app.apex.source.ExtractorState.Ready
    override suspend fun resolve(media: Media): app.apex.model.Resolved = error("não usado")
    override suspend fun comments(media: Media, limit: Int, newest: Boolean) = emptyList<app.apex.model.Comment>()
    override suspend fun channelDetails(channelId: String): app.apex.model.ChannelDetails = error("não usado")
    override suspend fun updateEngine() = ""
}


/** Um [AppContainer] de verdade com os dados numa pasta temporária e as peças do sistema trocadas por falsas. */
internal fun newTestApp(youtubeHttp: io.ktor.client.HttpClient? = null): Pair<AppContainer, FakeSystem> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val system = FakeSystem()
    val data = UserData(FileStore(Files.createTempDirectory("apex-test").toFile()), scope)
    return AppContainer(
        scope, system, data, FakeExtractor2(), FakePlayer2(), newPlayer = { FakePlayer2() }, youtubeHttp = youtubeHttp ?: app.apex.source.Http.client,
        fastYoutube = false, tileResolve = ::fakeTileResolve,
    ) to system
}

/** O "endereço" de uma live do Multi nos testes: qualidades de mentira, sem rede. */
internal suspend fun fakeTileResolve(media: Media): app.apex.model.Resolved {
    kotlinx.coroutines.delay(20)
    return app.apex.model.Resolved(
        media.copy(title = "Live de ${media.id}"), null, null, null, null, emptyList(), emptyList(),
        listOf(
            app.apex.model.Quality("1080p", 1080, "https://fake/${media.id}/1080.m3u8"),
            app.apex.model.Quality("720p", 720, "https://fake/${media.id}/720.m3u8"),
            app.apex.model.Quality("480p", 480, "https://fake/${media.id}/480.m3u8"),
        ),
        null, isLive = true,
    )
}
