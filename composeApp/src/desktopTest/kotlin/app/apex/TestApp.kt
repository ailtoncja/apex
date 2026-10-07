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
    override fun play(source: app.apex.player.PlaySource) {}
    override fun togglePause() {}
    override fun pause() {}
    override fun resume() {}
    override fun seekTo(positionMs: Long) {}
    override fun seekBy(deltaMs: Long) {}
    override fun setVolume(percent: Int) {}
    override fun setMuted(muted: Boolean) {}
    override fun setRate(rate: Float) {}
    override fun setSubtitle(url: String?) {}
    override fun stop() {}
    override fun release() {}
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
internal fun newTestApp(): Pair<AppContainer, FakeSystem> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val system = FakeSystem()
    val data = UserData(FileStore(Files.createTempDirectory("apex-test").toFile()), scope)
    return AppContainer(scope, system, data, FakeExtractor2(), FakePlayer2()) to system
}
