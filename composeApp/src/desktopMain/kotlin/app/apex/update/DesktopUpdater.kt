package app.apex.update

import app.apex.BuildInfo
import app.apex.source.Checksums
import app.apex.source.Http
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Como o app está instalado: define de que jeito ele se atualiza. */
enum class InstallMode {
    /** Rodando pelo Gradle ou com um Java qualquer: só avisa que há versão nova. */
    Dev,

    /** Instalado pelo `.msi`/`.exe` (pasta do usuário ou Arquivos de Programas): o instalador novo atualiza por cima. */
    Msi,

    /** Pasta extraída do `.zip`: os arquivos novos são copiados por cima da pasta. */
    Portable;

    companion object {
        fun detect(): InstallMode {
            val exe = currentExe()?.path ?: return Dev
            if (!exe.endsWith("Apex.exe", ignoreCase = true)) return Dev
            val roots = listOfNotNull(System.getenv("LOCALAPPDATA"), System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            return if (roots.any { exe.startsWith("$it\\Apex\\", ignoreCase = true) }) Msi else Portable
        }

        /** O `Apex.exe` que está rodando (no pacote do Windows), ou `null`. */
        fun currentExe(): File? = ProcessHandle.current().info().command().map { File(it) }.orElse(null)
    }
}

/** Quem assina as atualizações: só o que for assinado com a chave privada do projeto é instalado. */
object UpdateKeys {
    /** Chave pública Ed25519 (X.509, Base64). A privada fica só com quem publica as versões (`tools/Sign.java`). */
    const val PUBLIC_KEY = "MCowBQYDK2VwAyEAjDEhe1wNM7Uo4zJ2wPHIRDRuGBYWyE8LD5hHuEjNWRg="
}

object UpdateVerifier {
    /** `true` só se [signatureBase64] for a assinatura Ed25519 de [data] feita com a chave privada que combina com [publicKeyBase64]. */
    fun verify(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(data)
            verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }
    }.getOrDefault(false)
}

/**
 * Atualização automática pelo GitHub (Releases): confere a versão mais nova, baixa o instalador, confere a assinatura da lista de hashes
 * (`SHA256SUMS.txt.sig`) e o hash do arquivo, e só então deixa instalar. Nada é instalado sem a assinatura bater.
 */
class DesktopUpdater(
    private val scope: CoroutineScope,
    private val dir: File,
    private val autoDownload: () -> Boolean,
    override val currentVersion: String = BuildInfo.VERSION,
    private val http: HttpClient = Http.client,
    private val feedUrl: String = FEED_URL,
    private val publicKey: String = UpdateKeys.PUBLIC_KEY,
    private val mode: InstallMode = InstallMode.detect(),
    private val trustedPrefix: String = TRUSTED_PREFIX,
    private val runHelper: (File) -> Unit = ::launchHelper,
) : Updater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    override val canInstall: Boolean get() = mode != InstallMode.Dev

    /** Chamado antes de fechar o app para instalar (gravar os dados, soltar o player). */
    @Volatile var beforeExit: () -> Unit = {}
    @Volatile var exit: () -> Unit = { kotlin.system.exitProcess(0) }

    private val lock = Mutex()
    private var release: Release? = null
    private var ready: File? = null

    init {
        runCatching { cleanOldFiles() }
    }

    override fun check(manual: Boolean) {
        scope.launch { checkNow(manual) }
    }

    override fun download() {
        scope.launch { downloadNow() }
    }

    suspend fun checkNow(manual: Boolean) = lock.withLock {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Ready) return@withLock
        if (manual) _state.value = UpdateState.Checking
        val latest = try {
            fetchRelease()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sem internet ou GitHub fora do ar: em silêncio, a não ser que a pessoa tenha pedido.
            if (manual) _state.value = UpdateState.Failed("Não consegui verificar as atualizações. Confira a internet e tente de novo.")
            return@withLock
        }
        if (latest == null || !Versions.isNewer(latest.version, currentVersion)) {
            if (manual || state.value is UpdateState.Idle || state.value is UpdateState.Checking) _state.value = UpdateState.UpToDate
            return@withLock
        }
        release = latest
        _state.value = UpdateState.Available(UpdateInfo(latest.version, latest.notes, latest.pageUrl))
        if (canInstall && autoDownload()) downloadLocked()
    }

    suspend fun downloadNow() = lock.withLock { downloadLocked() }

    private suspend fun downloadLocked() {
        val rel = release ?: return
        val info = UpdateInfo(rel.version, rel.notes, rel.pageUrl)
        if (state.value is UpdateState.Ready || state.value is UpdateState.Downloading) return
        val assetName = when (mode) {
            InstallMode.Msi -> "Apex-${rel.version}.msi"
            InstallMode.Portable -> "Apex-${rel.version}-windows-x64.zip"
            InstallMode.Dev -> return
        }
        var part: File? = null
        try {
            _state.value = UpdateState.Downloading(info, 0)
            val sums = rel.asset("SHA256SUMS.txt")
            val sig = rel.asset("SHA256SUMS.txt.sig")
            val file = rel.asset(assetName)
            if (sums == null || sig == null || file == null) error("A versão ${rel.version} não tem todos os arquivos (instalador, hashes e assinatura).")
            listOf(sums, sig, file).forEach { check(it.url.startsWith(trustedPrefix)) { "Endereço de download não confiável: ${it.url.take(80)}" } }
            check(file.size in 1..MAX_DOWNLOAD) { "Tamanho do instalador fora do esperado." }

            // 1) a lista de hashes só vale se estiver assinada com a chave do projeto
            val sumsBytes = fetchBytes(sums.url, 64 * 1024)
            val signature = fetchBytes(sig.url, 4 * 1024).toString(Charsets.UTF_8)
            check(UpdateVerifier.verify(publicKey, sumsBytes, signature)) { "A assinatura da atualização não confere. Nada foi instalado." }
            val expected = Checksums.fromSumsFile(sumsBytes.toString(Charsets.UTF_8), assetName) ?: error("O arquivo $assetName não está na lista de hashes.")

            // 2) o instalador tem de ter exatamente o hash que a lista assinada diz
            dir.mkdirs()
            val target = File(dir, assetName)
            if (!(target.isFile && Checksums.sha256(target).equals(expected, ignoreCase = true))) {
                val tmp = File(dir, "$assetName.part").also { part = it }
                download(file.url, tmp, file.size) { _state.value = UpdateState.Downloading(info, it) }
                if (!Checksums.sha256(tmp).equals(expected, ignoreCase = true)) {
                    tmp.delete()
                    error("O arquivo baixado não bate com o hash assinado. Nada foi instalado.")
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ready = target
            _state.value = UpdateState.Ready(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            part?.delete()
            _state.value = UpdateState.Failed(e.message ?: "Não foi possível baixar a atualização.")
        }
    }

    override fun installAndRestart() {
        val file = ready ?: return
        val exe = InstallMode.currentExe() ?: return
        val script = File(dir, "aplicar-atualizacao.cmd")
        val unsafe = Regex("[%&^|<>\"]")
        if (listOf(file, exe, dir).any { unsafe.containsMatchIn(it.path) }) {
            _state.value = UpdateState.Failed("O caminho do app tem caracteres que impedem a atualização automática. Instale a versão nova pelo site.")
            return
        }
        val commands = when (mode) {
            InstallMode.Msi -> listOf(
                """msiexec /i "${file.path}" /passive /norestart""",
                """start "" "${exe.path}"""",
            )
            InstallMode.Portable -> {
                val extract = File(dir, "extraido")
                listOf(
                    """rd /s /q "${extract.path}" 2>nul""",
                    """mkdir "${extract.path}"""",
                    """tar -xf "${file.path}" -C "${extract.path}"""",
                    """robocopy "${extract.path}\Apex" "${exe.parentFile.path}" /E /R:10 /W:1 /NFL /NDL /NJH /NJS /NP >nul""",
                    """start "" "${exe.path}"""",
                )
            }
            InstallMode.Dev -> return
        }
        // Espera o Apex fechar (alguns segundos), aplica a versão nova e abre o app de novo.
        script.writeText((listOf("@echo off", "ping -n 4 127.0.0.1 >nul") + commands).joinToString("\r\n") + "\r\n")
        beforeExit()
        runHelper(script)
        exit()
    }

    // ---------------------------------------------------------------- rede

    private suspend fun fetchRelease(): Release? {
        val response = http.get(feedUrl) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Apex/$currentVersion")
            timeout { requestTimeoutMillis = 20_000 }
        }
        if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
        return parseRelease(response.bodyAsText())
    }

    private suspend fun fetchBytes(url: String, limit: Int): ByteArray {
        val bytes = http.get(url) {
            header("User-Agent", "Apex/$currentVersion")
            timeout { requestTimeoutMillis = 60_000 }
        }.also { check(it.status.isSuccess()) { "Falha ao baixar ${url.substringAfterLast('/')} (HTTP ${it.status.value})" } }
            .readRawBytes()
        check(bytes.size <= limit) { "Arquivo grande demais: ${url.substringAfterLast('/')}" }
        return bytes
    }

    private suspend fun download(url: String, target: File, expectedSize: Long, progress: (Int) -> Unit) {
        http.prepareGet(url) {
            header("User-Agent", "Apex/$currentVersion")
            timeout { requestTimeoutMillis = 30 * 60_000L; socketTimeoutMillis = 60_000 }
        }.execute { response ->
            check(response.status.isSuccess()) { "Falha ao baixar a atualização (HTTP ${response.status.value})" }
            val total = (response.contentLength() ?: expectedSize).takeIf { it > 0 } ?: expectedSize
            check(total <= MAX_DOWNLOAD) { "Tamanho do instalador fora do esperado." }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var lastPercent = -1
            target.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    done += n
                    check(done <= MAX_DOWNLOAD) { "Arquivo maior que o esperado." }
                    val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        progress(percent)
                    }
                }
            }
        }
    }

    /** Apaga downloads incompletos e instaladores de versões que já estão instaladas. */
    private fun cleanOldFiles() {
        dir.listFiles()?.forEach { f ->
            val version = Regex("^Apex-(\\d+(?:\\.\\d+)*)").find(f.name)?.groupValues?.get(1)
            val old = version != null && !Versions.isNewer(version, currentVersion)
            if (f.name.endsWith(".part") || old || f.name == "aplicar-atualizacao.cmd") f.delete()
            else if (f.isDirectory && f.name == "extraido") f.deleteRecursively()
        }
    }

    companion object {
        const val FEED_URL = "https://api.github.com/repos/ailtoncja/apex/releases/latest"
        const val TRUSTED_PREFIX = "https://github.com/ailtoncja/apex/releases/download/"
        private const val MAX_DOWNLOAD = 400L * 1024 * 1024

        /** Abre o script numa janela minimizada, solta do app (que vai fechar). */
        fun launchHelper(script: File) {
            ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.absolutePath).start()
        }
    }
}
