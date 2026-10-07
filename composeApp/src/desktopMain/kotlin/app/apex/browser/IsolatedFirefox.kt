package app.apex.browser

import app.apex.Cookie
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Firefox aberto num perfil só do Apex, que é descartado depois.
 *
 * Por que não ler o perfil do dia a dia: um navegador aberto gira os cookies de login do Google/YouTube a cada poucos minutos, e a cópia que
 * o Apex guardou deixa de valer (a sessão "cai" sozinha). Num perfil separado, fechado assim que o login é lido, ninguém gira os cookies.
 */
class IsolatedFirefox private constructor(val profile: File, private val launcher: Process) : AutoCloseable {
    private val startedAt = System.currentTimeMillis()
    private var browserPids: List<Long> = emptyList()

    /** Cookies gravados no perfil até agora (vazio se o Firefox ainda não criou o banco). */
    fun cookies(hosts: List<String> = FirefoxCookies.DEFAULT_HOSTS): List<Cookie> =
        runCatching { FirefoxCookies.readProfile(profile, hosts) }.getOrDefault(emptyList())

    /** `false` quando o usuário fechou a janela. Nos primeiros segundos o Firefox ainda está abrindo, então vale `true`. */
    val isRunning: Boolean
        get() {
            if (System.currentTimeMillis() - startedAt < STARTUP_GRACE_MS) return true
            if (launcher.isAlive) return true
            if (browserPids.isEmpty()) browserPids = BrowserProcesses.pidsUsing(profile)
            return browserPids.any { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
        }

    /** Fecha o Firefox deste perfil e apaga o perfil (ele guarda a sessão em texto puro). */
    override fun close() {
        BrowserProcesses.closeUsing(profile)
        // Os processos do Firefox demoram um pouco a soltar os arquivos do perfil; tenta de novo por alguns segundos.
        repeat(40) { if (!profile.exists() || profile.deleteRecursively()) return; Thread.sleep(300) }
    }

    companion object {
        private const val STARTUP_GRACE_MS = 12_000L

        fun open(exe: File, profile: File, startUrl: String, headless: Boolean = false): IsolatedFirefox {
            // Sempre começa do zero: nada de sessão velha (e nunca mexe fora da pasta de login do Apex).
            require("login-browser" in profile.path) { "O perfil isolado precisa ficar na pasta login-browser." }
            profile.deleteRecursively()
            profile.mkdirs()
            val args = buildList {
                add(exe.absolutePath)
                add("-no-remote")
                if (headless) add("-headless")
                add("-profile")
                add(profile.absolutePath)
                add(startUrl)
            }
            val process = ProcessBuilder(args).redirectErrorStream(true).also { it.redirectOutput(ProcessBuilder.Redirect.DISCARD) }.start()
            return IsolatedFirefox(profile, process)
        }
    }
}

/**
 * Acha e encerra processos de navegador pelo perfil que usam, sem tocar em nenhuma outra janela do usuário.
 * No Windows o Java não enxerga a linha de comando dos outros processos; por isso a busca é feita pelo WMI (PowerShell).
 */
object BrowserProcesses {
    /** PIDs de processos cuja linha de comando cita a pasta [profile]. */
    fun pidsUsing(profile: File): List<Long> = runCatching {
        val script = "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -and \$_.CommandLine.IndexOf(\$env:APEX_PROFILE, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | ForEach-Object { \$_.ProcessId }"
        val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true)
        pb.environment()["APEX_PROFILE"] = profile.absolutePath
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        output.lines().mapNotNull { it.trim().toLongOrNull() }.filter { it != ProcessHandle.current().pid() }
    }.getOrDefault(emptyList())

    fun using(profile: File): List<ProcessHandle> = pidsUsing(profile).mapNotNull { ProcessHandle.of(it).orElse(null) }

    fun closeUsing(profile: File) {
        val found = using(profile)
        if (found.isEmpty()) return
        // O Firefox tem um processo principal e vários filhos; encerramos a árvore toda.
        found.forEach { h -> h.descendants().forEach { it.destroy() }; h.destroy() }
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline && found.any { it.isAlive }) Thread.sleep(200)
        found.filter { it.isAlive }.forEach { h -> h.descendants().forEach { it.destroyForcibly() }; h.destroyForcibly() }
    }
}
