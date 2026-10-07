package app.apex.update

import app.apex.source.Checksums
import app.apex.source.Http
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A atualização automática, contra um "GitHub" de mentira: o que instala, o que recusa e como reinicia. */
class UpdaterTest {
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val otherKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(data: ByteArray, key: java.security.PrivateKey = keys.private): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(key); update(data); sign() })

    private val msiBytes = ByteArray(300_000) { (it * 31).toByte() }
    private val zipBytes = ByteArray(120_000) { (it * 17).toByte() }

    private val files = mutableMapOf<String, ByteArray>()
    private var releaseJson = ""
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    /** Monta uma versão publicada: instalador, zip, lista de hashes e assinatura (que o teste pode estragar). */
    private fun publish(
        version: String, signWith: java.security.PrivateKey = keys.private, tamperMsi: Boolean = false,
        withSignature: Boolean = true, assetHost: String? = null, prerelease: Boolean = false,
    ) {
        val msiName = "Apex-$version.msi"
        val zipName = "Apex-$version-windows-x64.zip"
        val sums = "${Checksums.sha256Bytes(msiBytes)}  $msiName\n${Checksums.sha256Bytes(zipBytes)}  $zipName\n".toByteArray()
        files.clear()
        files["SHA256SUMS.txt"] = sums
        files["SHA256SUMS.txt.sig"] = sign(sums, signWith).toByteArray()
        files[msiName] = if (tamperMsi) msiBytes.copyOf().also { it[10] = (it[10] + 1).toByte() } else msiBytes
        files[zipName] = zipBytes
        val host = assetHost ?: "$base/dl"
        val assets = buildList {
            add(msiName to files.getValue(msiName).size)
            add(zipName to zipBytes.size)
            add("SHA256SUMS.txt" to sums.size)
            if (withSignature) add("SHA256SUMS.txt.sig" to files.getValue("SHA256SUMS.txt.sig").size)
        }.joinToString(",") { (n, s) -> """{"name":"$n","size":$s,"browser_download_url":"$host/$n"}""" }
        releaseJson = """{"tag_name":"v$version","name":"Apex $version","body":"- Novidade 1\n- Novidade 2","html_url":"https://github.com/ailtoncja/apex/releases/tag/v$version","draft":false,"prerelease":$prerelease,"assets":[$assets]}"""
    }

    init {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = when {
                path == "/latest" -> releaseJson.toByteArray()
                path.startsWith("/dl/") -> files[path.removePrefix("/dl/")]
                else -> null
            }
            if (body == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun updater(
        current: String = "1.0.0", mode: InstallMode = InstallMode.Msi, auto: Boolean = true, key: String = publicKey,
        prefix: String = "$base/dl/", dir: File = Files.createTempDirectory("apex-upd").toFile(), helper: (File) -> Unit = {},
    ) = DesktopUpdater(
        CoroutineScope(SupervisorJob() + Dispatchers.Default), dir, { auto }, current, Http.client, "$base/latest", key, mode, prefix, helper,
    )

    // ---------------------------------------------------------------- versões

    @Test
    fun compara_versoes() {
        assertTrue(Versions.isNewer("1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("2.0", "1.99.99"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
        assertEquals(0, Versions.compare("1.0", "1.0.0"))
        assertFalse(Versions.isNewer("lixo", "1.0.0"))
        assertTrue(Versions.isNewer("1.0.0", "lixo"))
    }

    @Test
    fun le_a_resposta_do_github_e_ignora_rascunho_e_pre_lancamento() {
        val ok = assertNotNull(parseRelease("""{"tag_name":"v1.2.3","body":"notas","html_url":"https://x","assets":[{"name":"a.msi","size":5,"browser_download_url":"https://x/a.msi"}]}"""))
        assertEquals("1.2.3", ok.version)
        assertEquals("notas", ok.notes)
        assertEquals(5L, ok.asset("a.msi")?.size)
        assertNull(parseRelease("""{"tag_name":"v9.0.0","prerelease":true,"assets":[]}"""))
        assertNull(parseRelease("""{"tag_name":"v9.0.0","draft":true,"assets":[]}"""))
        assertNull(parseRelease("isso não é json"))
        assertNull(parseRelease("""{"message":"Not Found"}"""))
    }

    // ---------------------------------------------------------------- baixar e conferir

    @Test
    fun versao_nova_e_baixada_conferida_e_fica_pronta() = runBlocking<Unit> {
        publish("1.0.1")
        val dir = Files.createTempDirectory("apex-upd").toFile()
        val u = updater(dir = dir)
        u.checkNow(manual = false)
        val ready = assertIs<UpdateState.Ready>(u.state.value)
        assertEquals("1.0.1", ready.info.version)
        assertTrue(ready.info.notes.contains("Novidade 1"))
        val file = File(dir, "Apex-1.0.1.msi")
        assertTrue(file.isFile && file.readBytes().contentEquals(msiBytes))
        assertTrue(dir.list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun sem_baixar_sozinho_so_avisa_e_o_download_vem_depois() = runBlocking<Unit> {
        publish("1.0.1")
        val u = updater(auto = false)
        u.checkNow(manual = false)
        assertIs<UpdateState.Available>(u.state.value)
        u.downloadNow()
        assertIs<UpdateState.Ready>(u.state.value)
    }

    @Test
    fun ja_esta_na_versao_mais_nova() = runBlocking<Unit> {
        publish("1.0.0")
        val u = updater(current = "1.0.0")
        u.checkNow(manual = true)
        assertEquals(UpdateState.UpToDate, u.state.value)
        publish("1.0.1", prerelease = true)
        val v = updater(current = "1.0.0")
        v.checkNow(manual = true)
        assertEquals(UpdateState.UpToDate, v.state.value)
    }

    @Test
    fun instalador_adulterado_e_recusado() = runBlocking<Unit> {
        publish("1.0.1", tamperMsi = true)
        val dir = Files.createTempDirectory("apex-upd").toFile()
        val u = updater(dir = dir)
        u.checkNow(manual = false)
        val failed = assertIs<UpdateState.Failed>(u.state.value)
        assertTrue("hash" in failed.message, failed.message)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".msi") }, "o arquivo adulterado não pode ficar guardado")
    }

    @Test
    fun lista_de_hashes_assinada_por_outra_chave_e_recusada() = runBlocking<Unit> {
        publish("1.0.1", signWith = otherKeys.private)
        val u = updater()
        u.checkNow(manual = false)
        val failed = assertIs<UpdateState.Failed>(u.state.value)
        assertTrue("assinatura" in failed.message, failed.message)
        // E uma chave pública errada no app também não deixa passar uma assinatura boa.
        publish("1.0.1")
        val v = updater(key = Base64.getEncoder().encodeToString(otherKeys.public.encoded))
        v.checkNow(manual = false)
        assertIs<UpdateState.Failed>(v.state.value)
    }

    @Test
    fun sem_assinatura_ou_de_fora_do_github_nao_instala() = runBlocking<Unit> {
        publish("1.0.1", withSignature = false)
        val a = updater()
        a.checkNow(manual = false)
        assertIs<UpdateState.Failed>(a.state.value)

        // Arquivos hospedados em outro lugar (um release forjado apontando para um servidor qualquer).
        publish("1.0.1", assetHost = "https://invasor.example.com/dl")
        val b = updater()
        b.checkNow(manual = false)
        val failed = assertIs<UpdateState.Failed>(b.state.value)
        assertTrue("confiável" in failed.message, failed.message)
    }

    @Test
    fun rodando_pelo_gradle_so_avisa() = runBlocking<Unit> {
        publish("1.0.1")
        val u = updater(mode = InstallMode.Dev)
        assertFalse(u.canInstall)
        u.checkNow(manual = false)
        assertIs<UpdateState.Available>(u.state.value)
        u.downloadNow()
        assertIs<UpdateState.Available>(u.state.value)
    }

    @Test
    fun sem_internet_nao_incomoda_a_menos_que_a_pessoa_peca() = runBlocking<Unit> {
        publish("1.0.1")
        val u = DesktopUpdater(CoroutineScope(SupervisorJob() + Dispatchers.Default), Files.createTempDirectory("apex-upd").toFile(), { true }, "1.0.0", Http.client, "http://127.0.0.1:1/latest", publicKey, InstallMode.Msi, "$base/dl/")
        u.checkNow(manual = false)
        assertEquals(UpdateState.Idle, u.state.value)
        u.checkNow(manual = true)
        assertIs<UpdateState.Failed>(u.state.value)
    }

    // ---------------------------------------------------------------- reiniciar e instalar

    @Test
    fun instalador_msi_e_aplicado_depois_que_o_app_fecha() = runBlocking<Unit> {
        publish("1.0.1")
        var script: File? = null
        var closed = false
        var before = false
        val u = updater(helper = { script = it })
        u.beforeExit = { before = true }
        u.exit = { closed = true }
        u.checkNow(manual = false)
        u.installAndRestart()
        assertTrue(before && closed)
        val text = assertNotNull(script).readText()
        assertTrue(text.startsWith("@echo off\r\nping -n 4 127.0.0.1 >nul\r\n"), text)
        assertTrue("""msiexec /i """" in text && "Apex-1.0.1.msi\" /passive /norestart" in text, text)
        assertTrue(text.trimEnd().lines().last().startsWith("start \"\" \""), text)
    }

    @Test
    fun versao_portatil_copia_por_cima_da_pasta() = runBlocking<Unit> {
        publish("1.0.1")
        var script: File? = null
        val u = updater(mode = InstallMode.Portable, helper = { script = it })
        u.exit = {}
        u.checkNow(manual = false)
        u.installAndRestart()
        val text = assertNotNull(script).readText()
        assertTrue("tar -xf" in text && "Apex-1.0.1-windows-x64.zip" in text && "robocopy" in text, text)
    }

    @Test
    fun sem_nada_pronto_nao_fecha_o_app() {
        var closed = false
        val u = updater(helper = { error("não deveria rodar") })
        u.exit = { closed = true }
        u.installAndRestart()
        assertFalse(closed)
    }

    @Test
    fun limpa_o_que_sobrou_de_atualizacoes_antigas() {
        val dir = Files.createTempDirectory("apex-upd").toFile()
        File(dir, "Apex-1.0.0.msi").writeText("velho")
        File(dir, "Apex-1.0.1.msi.part").writeText("incompleto")
        File(dir, "Apex-1.0.2.msi").writeText("mais novo, vale guardar")
        File(dir, "aplicar-atualizacao.cmd").writeText("x")
        updater(current = "1.0.0", dir = dir)
        assertEquals(listOf("Apex-1.0.2.msi"), dir.list()!!.sorted())
    }
}

/** Confere, antes de publicar, que o que o `Empacotar.cmd` gerou em dist\ está assinado com a chave que está dentro do app. */
class DistSignatureTest {
    @Test
    fun a_assinatura_do_dist_confere_com_a_chave_do_app() {
        val dist = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "dist") }.firstOrNull { File(it, "SHA256SUMS.txt.sig").isFile }
            ?: return // ainda não foi empacotado neste computador
        val sums = File(dist, "SHA256SUMS.txt").readBytes()
        val sig = File(dist, "SHA256SUMS.txt.sig").readText()
        assertTrue(UpdateVerifier.verify(UpdateKeys.PUBLIC_KEY, sums, sig), "a assinatura de dist/SHA256SUMS.txt não confere com UpdateKeys.PUBLIC_KEY")
        // E cada arquivo listado tem mesmo o hash da lista.
        for (line in sums.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }) {
            val hash = line.substringBefore(' ')
            val name = line.substringAfter(' ').trim().removePrefix("*")
            assertEquals(hash, Checksums.sha256(File(dist, name)), "hash de $name")
        }
        // Mexer em um único byte da lista invalida a assinatura.
        assertFalse(UpdateVerifier.verify(UpdateKeys.PUBLIC_KEY, sums.copyOf().also { it[0] = (it[0] + 1).toByte() }, sig))
    }
}
