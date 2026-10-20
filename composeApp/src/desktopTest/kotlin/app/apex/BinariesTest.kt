package app.apex

import app.apex.source.Binaries
import app.apex.source.Checksums
import app.apex.source.YtDlpSignature
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O yt-dlp e o Deno só são instalados (ou atualizados) se a assinatura e os hashes oficiais baterem. */
class BinariesTest {
    // ---------------------------------------------------------------- assinatura real do yt-dlp

    /** `SHA2-256SUMS` e `SHA2-256SUMS.sig` reais da versão 2026.08.19 do yt-dlp (baixados do GitHub). */
    private val realSums = listOf(
        "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6  yt-dlp",
        "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a  yt-dlp.exe",
        "072aad4f2a7604e92155f61a275a4752dc64046c8f6d90df3710525d94cd37c1  yt-dlp.tar.gz",
        "05b438997bafc3affdfda9d041353c9d73e04dc842207254b655b0887c4445b0  yt-dlp_arm64.exe",
        "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  yt-dlp_linux",
        "32e72032766bef9199d99d15beb69fd52e46df8f8b06f0d8745db59e04d339e9  yt-dlp_linux.zip",
        "b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc  yt-dlp_linux_aarch64",
        "4e27ad43f3a34bacffd078694eb3edbb4e3b378e7da44edab2be02e98555516e  yt-dlp_linux_aarch64.zip",
        "bd51eb5fed7788008f4f30d281a182376a1297910b6c78e747403e757a546508  yt-dlp_linux_armv7l.zip",
        "0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202  yt-dlp_macos",
        "07e54b0865303c864006925913bce2604f8ee8cc6f18699bac9c309f9328a6d8  yt-dlp_macos.zip",
        "f3dec9cfeaf304cec98290fe41c6ad465d4b747d302473559643e7af24929722  yt-dlp_musllinux",
        "a8a9761628be455368db73ab54a2858da22342084f300f7e0d50bdcbef2acf82  yt-dlp_musllinux.zip",
        "17b164c4d258be92bb1ad146cb7c336b783aedb380814aabbcb7d52937f77e57  yt-dlp_musllinux_aarch64",
        "46087f4965f48612d056c6e4b00a0898e8230ba90d709a0a751c7cc5735a1da3  yt-dlp_musllinux_aarch64.zip",
        "30b4c14aafab6082becff7881e41b76df46dc43ea7633479410a91e29da492bf  yt-dlp_win.zip",
        "2ad49db7429a6204721f711a44199a8c9fa8d5df2183dd7fedc4ef6c9e2e50d3  yt-dlp_win_arm64.zip",
        "dc7c11f014af67f23d9fd989868048abedbce01709ff642a29eb3f7578b802f7  yt-dlp_win_x86.zip",
        "a8f91bd41452506bc81ebd2f369b186fea0ee7075413ba00cef9fd346a0a5d0c  yt-dlp_x86.exe",
    ).joinToString("") { "$it\n" }.toByteArray()

    private val realSig = Base64.getDecoder().decode(
        "iQIzBAABCgAdFiEErAy75oSNaoc0ZK9OV89lkztadYEFAmqGQLkACgkQV89lkztadYEDvBAA50RZc18DPwjW48fR5rDKN3cdfn7NTMvkeNsTAQ9SmmCY7sP71BEYrYu04r80cCDVvMlOC0OvDTlQUkHMf4yKjLGWgr4zaQgHZWv+k+Y348t2rSoNoPl6rh2Z+NLGcRzTNzK8CVeZkWroCEVe6DlC+2L1dy2esxlqxc27JxpJKFSxap7tPn0duFjLe019Dgi0Oulb86+AeI3smXTVThVoA5RljogbFptYd5G1aLxa6H2GLQh7k97N/6UdiNNZuZ/KZ3BpVqI0kAM63ipL0sJUcqHe80SX0WySYhH7S6RoScsLeHBZwuM+Ma92sdAs6cA0pxzcLaaZ4bBH4RDsfDncZHhknZblcXvsAUhiZsVBZHDYaM6ugvpoWuP6Z9/sBHbiCLAX/rIkx4jUsl605efiQfsZttAM8kxyz7LUAy/gA3CmYKt25swiaKC2xJAbFIUZfy948zKLk7VwFfjdErFXDlt5SyibRVIflutaZEBlYzz9EDdnJVfi/JyEK2rZQAntZAaq9xJWY5NjpufkV9VB3WskRyV4V+ZDCjhbayaaJz6SUdgSaxUyguzicE3/Jmtizva32Cq/PsIsopxoBbtLsohKJEYoojdQLRssDicZ8u7swut29bLeKlx0VAyABpaZgzK8m0AJwL0Qd/R3YmJhL+oClM49pquA22zV43jEkTg=",
    )

    @Test
    fun assinatura_real_do_yt_dlp_confere_com_a_chave_fixa() {
        assertTrue(YtDlpSignature.verify(realSums, realSig), "a assinatura real do yt-dlp tem de conferir")
        // Um único byte trocado na lista (um hash de yt-dlp.exe adulterado) invalida a assinatura.
        val tampered = realSums.copyOf().also { it[100] = if (it[100] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte() }
        assertFalse(YtDlpSignature.verify(tampered, realSig))
        // Assinatura de outra chave, impressão digital diferente da fixa, lixo: tudo recusado.
        val other = TestPgpKey()
        assertFalse(YtDlpSignature.verify(realSums, other.sign(realSums)))
        assertFalse(YtDlpSignature.verify(realSums, realSig, fingerprint = other.fingerprint))
        assertFalse(YtDlpSignature.verify(realSums, "não é assinatura".toByteArray()))
        assertFalse(YtDlpSignature.verify(realSums, realSig, publicKey = other.armoredPublic, fingerprint = YtDlpSignature.FINGERPRINT))
    }

    // ---------------------------------------------------------------- instalar e atualizar, contra um "GitHub" de mentira

    private val key = TestPgpKey()
    private val files = mutableMapOf<String, ByteArray>()
    private val latest = mutableMapOf<String, String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val repo = path.removePrefix("/").substringBefore('/')
            if (path.endsWith("/releases/latest") && repo in latest) {
                ex.responseHeaders.add("Location", "$base/$repo/releases/tag/${latest.getValue(repo)}")
                ex.sendResponseHeaders(302, -1)
            } else {
                val body = files[path]
                if (body == null) ex.sendResponseHeaders(404, -1) else {
                    ex.sendResponseHeaders(200, if (ex.requestMethod == "HEAD") -1 else body.size.toLong())
                    if (ex.requestMethod != "HEAD") ex.responseBody.use { it.write(body) }
                }
            }
            ex.close()
        }
        start()
    }
    private val base: String = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    private fun publishYtDlp(tag: String, exe: ByteArray, signWith: TestPgpKey = key, listedHash: String = Checksums.sha256Bytes(exe)) {
        val sums = "${"0".repeat(64)}  yt-dlp\n$listedHash  yt-dlp.exe\n".toByteArray()
        files["/yt/releases/download/$tag/SHA2-256SUMS"] = sums
        files["/yt/releases/download/$tag/SHA2-256SUMS.sig"] = signWith.sign(sums)
        files["/yt/releases/download/$tag/yt-dlp.exe"] = exe
        latest["yt"] = tag
    }

    private fun publishDeno(tag: String, exe: ByteArray, listedHash: String? = null) {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("deno.exe"))
                z.write(exe)
                z.closeEntry()
            }
        }.toByteArray()
        val hash = (listedHash ?: Checksums.sha256Bytes(zip)).uppercase()
        files["/deno/releases/download/$tag/${Binaries.DENO_ZIP}"] = zip
        // O Deno publica o hash num texto do PowerShell (Get-FileHash | Format-List).
        files["/deno/releases/download/$tag/${Binaries.DENO_ZIP}.sha256sum"] =
            "\r\nAlgorithm : SHA256\r\nHash      : $hash\r\nPath      : C:\\a\\deno\\target\\release\\${Binaries.DENO_ZIP}\r\n".toByteArray()
        latest["deno"] = tag
    }

    private fun binaries(dir: File = Files.createTempDirectory("apex-bin").toFile()) =
        Binaries(dir, "$base/yt", "$base/deno", key.armoredPublic, key.fingerprint)

    @Test
    fun instala_e_atualiza_so_com_assinatura_e_hash_certos() = runBlocking<Unit> {
        val yt1 = ByteArray(5_000) { (it * 7).toByte() }
        val deno1 = ByteArray(3_000) { (it * 3).toByte() }
        publishYtDlp("2026.01.01", yt1)
        publishDeno("v2.0.0", deno1)
        val bins = binaries()
        val steps = mutableListOf<String>()
        bins.install { steps += it }
        assertContentEquals(yt1, bins.ytdlp.readBytes())
        assertContentEquals(deno1, bins.deno.readBytes())
        assertTrue(steps.size == 2, steps.toString())
        assertTrue(bins.dir.list()!!.none { it.endsWith(".part") || it == "deno.zip" }, bins.dir.list()!!.toList().toString())

        // Mesma versão: nada é baixado de novo.
        val same = bins.update()
        assertTrue("yt-dlp já está" in same && "Deno já está" in same, same)

        // Versão nova dos dois: troca os programas.
        val yt2 = ByteArray(6_000) { (it * 11).toByte() }
        val deno2 = ByteArray(4_000) { (it * 13).toByte() }
        publishYtDlp("2026.02.02", yt2)
        publishDeno("v2.1.0", deno2)
        val msg = bins.update()
        assertTrue("yt-dlp atualizado para 2026.02.02" in msg && "Deno atualizado para v2.1.0" in msg, msg)
        assertContentEquals(yt2, bins.ytdlp.readBytes())
        assertContentEquals(deno2, bins.deno.readBytes())
    }

    @Test
    fun yt_dlp_com_assinatura_de_outra_chave_nao_e_instalado() = runBlocking<Unit> {
        publishYtDlp("2026.01.01", ByteArray(100) { 1 }, signWith = TestPgpKey())
        publishDeno("v2.0.0", ByteArray(100) { 2 })
        val bins = binaries()
        val error = assertFailsWith<IllegalStateException> { bins.install {} }
        assertTrue("assinatura" in error.message.orEmpty(), error.message)
        assertFalse(bins.ytdlp.exists())
    }

    @Test
    fun arquivos_que_nao_batem_com_o_hash_nao_sao_instalados() = runBlocking<Unit> {
        // yt-dlp: a lista assinada diz um hash, o arquivo servido é outro.
        publishYtDlp("2026.01.01", ByteArray(100) { 1 }, listedHash = Checksums.sha256Bytes(ByteArray(100) { 9 }))
        publishDeno("v2.0.0", ByteArray(100) { 2 })
        val a = binaries()
        val e1 = assertFailsWith<IllegalStateException> { a.install {} }
        assertTrue("hash" in e1.message.orEmpty(), e1.message)
        assertFalse(a.ytdlp.exists())
        assertTrue(a.dir.list()!!.none { it.endsWith(".part") })

        // Deno: zip diferente do hash publicado. O yt-dlp (certo) fica, o Deno não.
        publishYtDlp("2026.01.01", ByteArray(100) { 1 })
        publishDeno("v2.0.0", ByteArray(100) { 2 }, listedHash = "ab".repeat(32))
        val b = binaries()
        val e2 = assertFailsWith<IllegalStateException> { b.install {} }
        assertTrue("hash" in e2.message.orEmpty(), e2.message)
        assertTrue(b.ytdlp.isFile)
        assertFalse(b.deno.exists())
        assertFalse(File(b.dir, "deno.zip").exists())
    }
}

/** Um par de chaves OpenPGP descartável, só para os testes assinarem listas de hashes. */
private class TestPgpKey {
    private val pair = BcPGPKeyPair(
        PublicKeyAlgorithmTags.RSA_GENERAL,
        RSAKeyPairGenerator().apply { init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), SecureRandom(), 2048, 80)) }.generateKeyPair(),
        Date(),
    )
    val fingerprint: String = pair.publicKey.fingerprint.joinToString("") { "%02X".format(it) }
    val armoredPublic: String = ByteArrayOutputStream().also { out -> ArmoredOutputStream(out).use { pair.publicKey.encode(it) } }.toString(Charsets.US_ASCII)

    fun sign(data: ByteArray): ByteArray {
        val generator = PGPSignatureGenerator(BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256), pair.publicKey)
        generator.init(PGPSignature.BINARY_DOCUMENT, pair.privateKey)
        generator.update(data)
        return generator.generate().encoded
    }
}
