package app.apex

import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.TWITCH_AD_FREE_SERVERS
import app.apex.source.TwitchSource
import app.apex.source.adFreeMasterUrl
import app.apex.source.trustedTwitchMaster
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lives da Twitch sem anúncio pelos servidores do Xtra, com a Twitch direta de reserva. */
class TwitchAdFreeTest {
    private fun master(host: String = "euc13.playlist.ttvnw.net") = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://$host/v1/playlist/fonte.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p30",FRAME-RATE=30.000
        https://$host/v1/playlist/720.m3u8
    """.trimIndent()

    @Test
    fun monta_o_endereco_pedindo_qualidade_maxima_e_baixa_latencia() {
        val url = adFreeMasterUrl("eu.luminous.dev/live/\$channel", "Gaules")!!
        assertTrue(url.startsWith("https://eu.luminous.dev/live/gaules?"), url)
        assertTrue("allow_source=true" in url && "fast_bread=true" in url && "supported_codecs=h264" in url, url)
        // com https:// e já com consulta, continua certo
        assertTrue(adFreeMasterUrl("https://x.example/live/\$channel?a=1", "abc")!!.startsWith("https://x.example/live/abc?a=1&allow_source"))
        // sem $channel, com espaço, ou um "canal" que não é de verdade: não serve
        assertNull(adFreeMasterUrl("x.example/live", "abc"))
        assertNull(adFreeMasterUrl("x.example/live/\$channel", "abc/../../admin"))
        assertNull(adFreeMasterUrl("x.example/li ve/\$channel", "abc"))
    }

    @Test
    fun so_aceita_lista_que_aponta_para_a_twitch() {
        assertTrue(trustedTwitchMaster(master()))
        // um servidor de fora não pode mandar o app buscar outra coisa
        assertFalse(trustedTwitchMaster(master("evil.example")))
        assertFalse(trustedTwitchMaster(master("ttvnw.net.evil.example")))
        assertFalse(trustedTwitchMaster(master().replace("https://euc13", "http://euc13")))
        assertFalse(trustedTwitchMaster(master() + "\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"https://evil.example/a.m3u8\""))
        // erro em JSON, lista vazia ou lista de uma qualidade só (não a mestra)
        assertFalse(trustedTwitchMaster("""{"code":404,"error":"offline"}"""))
        assertFalse(trustedTwitchMaster("#EXTM3U\n#EXTINF:2.0,live\nhttps://x.ttvnw.net/seg.ts"))
    }

    /** Uma Twitch de mentira: o GQL dá o token, o usher a lista direta, e os servidores sem anúncio respondem como [servers] mandar. */
    private fun twitch(servers: (host: String) -> Pair<Int, String>?, asked: ConcurrentLinkedQueue<String>, offline: Boolean = false) = TwitchSource(
        HttpClient(
            MockEngine { request ->
                val host = request.url.host
                asked += host
                when {
                    host == "gql.twitch.tv" -> respond("""{"data":{"streamPlaybackAccessToken":{"value":"{}","signature":"s"}}}""", HttpStatusCode.OK)
                    host == "usher.ttvnw.net" ->
                        if (offline) respond("""[{"error":"twirp error not_found"}]""", HttpStatusCode.NotFound)
                        else respond(master("direto.playlist.ttvnw.net"), HttpStatusCode.OK)
                    else -> {
                        val answer = servers(host)
                        if (answer == null) { delay(10_000); respond("", HttpStatusCode.GatewayTimeout) }
                        else respond(answer.second, HttpStatusCode.fromValue(answer.first))
                    }
                }
            },
        ),
    )

    private val live = Media(Platform.Twitch, "canal", "canal", isLive = true, url = "https://www.twitch.tv/canal")

    @Test
    fun abre_pela_lista_sem_anuncio_quando_o_servidor_responde() = runBlocking {
        val asked = ConcurrentLinkedQueue<String>()
        val source = twitch({ host -> if (host == "eu2.luminous.dev") 200 to master() else 404 to """{"code":404}""" }, asked)
        source.adFreeServers = TWITCH_AD_FREE_SERVERS
        val resolved = source.resolve(live)
        assertTrue(resolved.qualities.drop(1).all { it.videoUrl.startsWith("https://euc13.playlist.ttvnw.net/") }, resolved.qualities.toString())
        assertEquals("1080p60 (Fonte)", resolved.qualities[1].label)
        // da próxima vez o que respondeu é o primeiro
        asked.clear()
        source.resolve(live)
        assertEquals("eu2.luminous.dev", asked.first { it.endsWith(".dev") || it.endsWith(".com") })
    }

    // ------------------------------------------------------------------ a lista publicada no repositório

    @Test
    fun a_lista_publicada_no_repositorio_esta_assinada_com_a_chave_do_app() {
        // o arquivo de verdade (config/), conferido com a chave pública que vai dentro do Apex: se alguém editar e esquecer de assinar, falha aqui
        val dir = listOf(java.io.File("../config"), java.io.File("config")).first { it.isDirectory }
        val bytes = java.io.File(dir, "twitch-ad-free.json").readBytes()
        val sig = java.io.File(dir, "twitch-ad-free.json.sig").readText().trim()
        assertTrue(app.apex.update.UpdateVerifier.verify(app.apex.update.UpdateKeys.PUBLIC_KEY, bytes, sig), "a assinatura não bate com o arquivo")
        val list = app.apex.source.parseAdFreeList(bytes.decodeToString())!!
        assertTrue(list.version >= 1 && list.servers.isNotEmpty())
    }

    private val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = java.util.Base64.getEncoder().encodeToString(keys.public.encoded)
    private fun sign(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(
        java.security.Signature.getInstance("Ed25519").run { initSign(keys.private); update(bytes); sign() },
    )
    private val verify: (ByteArray, String) -> Boolean = { data, sig -> app.apex.update.UpdateVerifier.verify(publicKey, data, sig) }

    private fun published(body: String, signature: String) = HttpClient(
        MockEngine { request ->
            if (request.url.toString().endsWith(".sig")) respond(signature, HttpStatusCode.OK) else respond(body, HttpStatusCode.OK)
        },
    )

    @Test
    fun so_aceita_a_lista_publicada_com_a_assinatura_certa() = runBlocking<Unit> {
        val body = """{"version": 3, "servers": ["novo.example/live/${'$'}channel", "outro.example/x/${'$'}channel?p=1"]}"""
        val ok = app.apex.source.fetchAdFreeList(published(body, sign(body.toByteArray())), verify)!!
        assertEquals(3, ok.version)
        assertEquals(listOf("novo.example/live/\$channel", "outro.example/x/\$channel?p=1"), ok.servers)
        // alguém no caminho trocou a lista (a assinatura era de outra): recusada
        val tampered = body.replace("novo.example", "evil.example")
        assertNull(app.apex.source.fetchAdFreeList(published(tampered, sign(body.toByteArray())), verify))
        // assinatura de outra chave: recusada
        val other = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val foreign = java.util.Base64.getEncoder().encodeToString(java.security.Signature.getInstance("Ed25519").run { initSign(other.private); update(body.toByteArray()); sign() })
        assertNull(app.apex.source.fetchAdFreeList(published(body, foreign), verify))
    }

    @Test
    fun lista_publicada_invalida_e_recusada_mesmo_assinada() {
        assertNull(app.apex.source.parseAdFreeList("""{"version": 0, "servers": ["a.example/live/${'$'}channel"]}"""))
        assertNull(app.apex.source.parseAdFreeList("""{"version": 2, "servers": []}"""))
        assertNull(app.apex.source.parseAdFreeList("""{"version": 2, "servers": ["sem-canal.example/live"]}"""))
        assertNull(app.apex.source.parseAdFreeList("""{"version": 2, "servers": [3]}"""))
        assertNull(app.apex.source.parseAdFreeList("isto não é json"))
    }

    @Test
    fun qual_lista_vale() {
        val base = app.apex.model.AppSettings()
        assertEquals(TWITCH_AD_FREE_SERVERS, app.apex.source.adFreeServersFor(base), "sem nada, a que veio no app")
        val published = base.copy(twitchAdFreePublished = listOf("pub.example/live/\$channel"), twitchAdFreePublishedVersion = 2)
        assertEquals(listOf("pub.example/live/\$channel"), app.apex.source.adFreeServersFor(published), "a publicada passa na frente")
        val mine = published.copy(twitchAdFreeServers = listOf("meu.example/live/\$channel"))
        assertEquals(listOf("meu.example/live/\$channel"), app.apex.source.adFreeServersFor(mine), "a da pessoa passa na frente de todas")
    }

    @Test
    fun servidor_que_deixou_passar_anuncio_fica_de_fora() = runBlocking<Unit> {
        val asked = ConcurrentLinkedQueue<String>()
        // todos respondem bem; o primeiro a ganhar é guardado como preferido
        val source = twitch({ 200 to master() }, asked)
        source.adFreeServers = TWITCH_AD_FREE_SERVERS.take(2)
        val first = source.resolve(live).adFreeServer!!
        // a live dele caiu num anúncio: a próxima vem pelo outro
        source.reportAd(first)
        val second = source.resolve(live).adFreeServer
        assertTrue(second != null && second != first, "$first -> $second")
        // com os dois de fora, vai direto da Twitch
        source.reportAd(second!!)
        val third = source.resolve(live)
        assertNull(third.adFreeServer)
        assertTrue(third.qualities.drop(1).all { it.videoUrl.startsWith("https://direto.playlist.ttvnw.net/") })
    }

    @Test
    fun quando_o_player_ve_anuncio_a_sessao_reabre_pelo_outro_servidor_na_mesma_qualidade() = runBlocking<Unit> {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val data = app.apex.data.UserData(app.apex.data.FileStore(java.nio.file.Files.createTempDirectory("apex-adfree").toFile()), scope)
        // cada servidor devolve qualidades num host próprio, para saber por onde a live veio
        val source = twitch({ host -> 200 to master("${host.substringBefore('.')}.playlist.ttvnw.net") }, ConcurrentLinkedQueue())
        source.adFreeServers = TWITCH_AD_FREE_SERVERS.take(2)
        val player = FakePlayer2()
        val session = app.apex.player.PlaybackSession(scope, player, FakeExtractor2(), source, app.apex.source.KickSource(), data)
        try {
            session.open(live)
            kotlinx.coroutines.withTimeout(5_000) { while (player.played.isEmpty()) delay(10) }
            val first = (session.load.value as app.apex.player.LoadState.Ready).resolved.adFreeServer!!
            session.setQuality((session.load.value as app.apex.player.LoadState.Ready).resolved.qualities.first { it.label.startsWith("720") })
            kotlinx.coroutines.withTimeout(5_000) { while (player.played.size < 2) delay(10) }
            // o player viu um anúncio
            player.state.value = player.state.value.copy(adsSeen = 1)
            kotlinx.coroutines.withTimeout(5_000) { while (player.played.size < 3) delay(10) }
            val second = (session.load.value as app.apex.player.LoadState.Ready).resolved.adFreeServer!!
            assertTrue(second != first, "$first -> $second")
            assertTrue(player.played.last().videoUrl.startsWith("https://${second.substringBefore('.')}.playlist.ttvnw.net/"), player.played.last().videoUrl)
            assertTrue(player.played.last().videoUrl.endsWith("/720.m3u8"), "a mesma qualidade")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun se_nenhum_servidor_serve_abre_direto_da_twitch() = runBlocking {
        val asked = ConcurrentLinkedQueue<String>()
        // uns recusam, outros devolvem lista apontando para fora, e o resto nem responde (cada um tem no máximo 3 s)
        val source = twitch({ host ->
            when {
                host.startsWith("eu.") -> 403 to "proibido"
                host.startsWith("eu3.") -> 200 to master("evil.example")
                else -> null
            }
        }, asked)
        source.adFreeServers = TWITCH_AD_FREE_SERVERS
        val started = System.currentTimeMillis()
        val resolved = source.resolve(live)
        assertTrue(resolved.qualities.drop(1).all { it.videoUrl.startsWith("https://direto.playlist.ttvnw.net/") })
        assertTrue(System.currentTimeMillis() - started < 4_500, "todos juntos: não espera mais que os 3 s dos servidores")
        assertTrue("usher.ttvnw.net" in asked)
    }

    @Test
    fun canal_offline_avisa_na_hora_sem_esperar_os_servidores() = runBlocking<Unit> {
        // os servidores nem respondem; a Twitch direta diz que o canal não está no ar
        val source = twitch({ null }, ConcurrentLinkedQueue(), offline = true)
        source.adFreeServers = TWITCH_AD_FREE_SERVERS
        val started = System.currentTimeMillis()
        val error = runCatching { source.resolve(live) }.exceptionOrNull()
        assertTrue(error is app.apex.source.ChannelOfflineException, error.toString())
        assertTrue(System.currentTimeMillis() - started < 2_000, "não espera os servidores")
    }

    @Test
    fun desligado_vai_direto_sem_falar_com_os_servidores() = runBlocking {
        val asked = ConcurrentLinkedQueue<String>()
        val source = twitch({ 200 to master() }, asked)
        source.resolve(live)
        assertTrue(asked.none { it.endsWith("luminous.dev") || it.endsWith("perfprod.com") }, asked.toString())
    }
}
