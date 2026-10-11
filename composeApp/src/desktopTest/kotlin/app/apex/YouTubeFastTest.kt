package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.model.SubtitleTrack
import app.apex.player.LoadState
import app.apex.player.PlaybackSession
import app.apex.source.Extractor
import app.apex.source.ExtractorState
import app.apex.source.InnerTube
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import app.apex.source.YouTubeFast
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A abertura rápida dos vídeos do YouTube (sem o yt-dlp) e a conclusão dos dados em segundo plano. */
class YouTubeFastTest {
    private val video = Media(Platform.YouTube, "abcdefghijk", "Título velho", url = "https://www.youtube.com/watch?v=abcdefghijk")

    private val player = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"abcdefghijk","title":"Título novo","lengthSeconds":"125","channelId":"UC123","author":"Canal Tal","viewCount":"1500",
           "shortDescription":"Capítulos:\n0:00 Introdução\n1:10 Meio\n2:00 Fim","keywords":["a","b"],"isLive":false},
         "microformat":{"playerMicroformatRenderer":{"uploadDate":"2026-10-09T08:00:00-07:00","publishDate":"2026-10-09T08:00:00-07:00"}},
         "streamingData":{"adaptiveFormats":[
           {"itag":137,"url":"https://v/1080avc","mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":4000000,"width":1920,"height":1080,"fps":30},
           {"itag":299,"url":"https://v/1080avc60","mimeType":"video/mp4; codecs=\"avc1.64002a\"","bitrate":6000000,"width":1920,"height":1080,"fps":60},
           {"itag":248,"url":"https://v/1080vp9","mimeType":"video/webm; codecs=\"vp9\"","bitrate":3000000,"height":1080,"fps":30},
           {"itag":313,"url":"https://v/2160vp9","mimeType":"video/webm; codecs=\"vp9\"","bitrate":15000000,"height":2160,"fps":30},
           {"itag":140,"url":"https://a/aac","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000},
           {"itag":251,"url":"https://a/opus","mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000},
           {"itag":251,"url":"https://a/drc","mimeType":"audio/webm; codecs=\"opus\"","bitrate":170000,"isDrc":true},
           {"itag":140,"url":"https://a/dublado","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":200000,"audioTrack":{"audioIsDefault":false}}
         ],"formats":[{"itag":18,"url":"https://m/360","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","height":360,"fps":30}]}}
    """.trimIndent()

    private class Fake(val reply: () -> String) {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            bodies += (request.body as? TextContent)?.text.orEmpty() + "|" + request.headers[HttpHeaders.UserAgent]
            respond(reply(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    private fun fast(reply: () -> String): Pair<YouTubeFast, Fake> = Fake(reply).let { YouTubeFast(InnerTube(it.client)) to it }

    // ------------------------------------------------------------------ o que sai da resposta

    @Test
    fun monta_as_qualidades_o_audio_e_os_dados_do_video() = runBlocking {
        val (fast, fake) = fast { player }
        val r = assertNotNull(fast.resolve(video))
        assertEquals(listOf(2160, 1080, 360), r.qualities.map { it.height })
        val q1080 = r.qualities[1]
        assertEquals("1080p60", q1080.label)
        assertEquals("https://v/1080avc60", q1080.videoUrl, "na mesma altura vence o h264 de 60 quadros")
        assertEquals("https://a/opus", q1080.audioUrl, "o áudio é o melhor da faixa principal: sem a versão de volume nivelado nem a dublagem")
        assertEquals("https://v/2160vp9", r.qualities[0].videoUrl, "acima de 1080p vence o vp9")
        assertNull(r.qualities[2].audioUrl, "o 360p já vem com áudio")
        assertEquals("Título novo", r.media.title)
        assertEquals("Canal Tal", r.media.channel?.name)
        assertEquals(125L, r.media.durationSec)
        assertEquals(1500L, r.media.viewCount)
        assertEquals("20261009", r.uploadDate)
        assertEquals(listOf("a", "b"), r.tags)
        assertEquals(listOf("Introdução", "Meio", "Fim"), r.chapters.map { it.title })
        assertEquals(listOf(0L, 70L, 120L), r.chapters.map { it.startSec })
        assertTrue(r.userAgent.orEmpty().startsWith("com.google.ios.youtube"), "o endereço é pedido ao YouTube como o app do iPhone")
        assertEquals(false, r.complete, "legendas e curtidas ainda faltam")
        val request = fake.bodies.single()
        assertTrue("\"clientName\":\"IOS\"" in request && "\"videoId\":\"abcdefghijk\"" in request, request)
    }

    @Test
    fun quando_o_youtube_pede_login_ou_e_live_ou_nao_e_video_devolve_null_e_o_yt_dlp_assume() = runBlocking {
        val login = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Faça login para confirmar que você não é um robô"}}"""
        assertNull(fast { login }.first.resolve(video))
        val live = player.replace("\"isLive\":false", "\"isLive\":true")
        assertNull(fast { live }.first.resolve(video))
        val noAudio = player.replace("audio/", "x/")
        // sem áudio separado só sobra o 360p que já vem com som: ainda dá para tocar
        assertEquals(listOf(360), assertNotNull(fast { noAudio }.first.resolve(video)).qualities.map { it.height })
        val nothing = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"x"},"streamingData":{}}"""
        assertNull(fast { nothing }.first.resolve(video))

        // clipe, live conhecida e endereço que não é de vídeo nem chegam a perguntar ao YouTube
        val (f, fake) = fast { player }
        assertNull(f.resolve(video.copy(isLive = true)))
        assertNull(f.resolve(video.copy(id = "clip-curto")))
        assertNull(f.resolve(video.copy(id = app.apex.model.CLIP_PREFIX + "abcdef")))
        assertTrue(fake.bodies.isEmpty())
    }

    @Test
    fun erro_de_rede_tambem_devolve_null() = runBlocking {
        val broken = YouTubeFast(InnerTube(HttpClient(MockEngine { throw java.io.IOException("sem rede") })))
        assertNull(broken.resolve(video))
    }

    @Test
    fun capitulos_so_valem_com_zero_tres_ou_mais_e_crescentes() {
        val f = YouTubeFast(InnerTube(HttpClient(MockEngine { respond("{}") })))
        assertEquals(3, f.chaptersFrom("0:00 A\n1:00 B\n2:30 C").size)
        assertEquals(3, f.chaptersFrom("00:00 - A\n01:00 - B\n1:02:30 - C").size)
        assertTrue(f.chaptersFrom("0:00 A\n1:00 B").isEmpty(), "só dois")
        assertTrue(f.chaptersFrom("0:10 A\n1:00 B\n2:30 C").isEmpty(), "não começa em 0:00")
        assertTrue(f.chaptersFrom("0:00 A\n2:00 B\n1:00 C").isEmpty(), "fora de ordem")
        assertTrue(f.chaptersFrom(null).isEmpty())
    }

    // ------------------------------------------------------------------ dentro da sessão

    private class Fakes(val fullCalls: AtomicInteger, val fileUrls: List<String> = emptyList()) : Extractor {
        override val state = MutableStateFlow<ExtractorState>(ExtractorState.Ready)
        override suspend fun ensureReady() = ExtractorState.Ready
        override suspend fun resolve(media: Media): Resolved {
            fullCalls.incrementAndGet()
            delay(300) // o yt-dlp de verdade leva uns 4 s
            return Resolved(
                media, "descrição completa", 1234L, 777L, "20261009", emptyList(),
                listOf(SubtitleTrack("pt", "Português", "https://legenda/pt.vtt", auto = false)),
                listOf(Quality("720p", 720, "https://yt-dlp/720.mp4")), "UA do yt-dlp", fileUrls = fileUrls,
            )
        }
        override suspend fun comments(media: Media, limit: Int, newest: Boolean) = emptyList<app.apex.model.Comment>()
        override suspend fun channelDetails(channelId: String) = error("não usado")
        override suspend fun updateEngine() = ""
    }

    @Test
    fun a_sessao_toca_pela_via_rapida_na_hora_e_completa_curtidas_e_legendas_depois() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-fast").toFile()), scope)
        val calls = AtomicInteger()
        val player = FakePlayer2()
        val played = mutableListOf<app.apex.player.PlaySource>()
        val recording = object : app.apex.player.PlayerController by player {
            override fun play(source: app.apex.player.PlaySource) { played += source; player.play(source) }
        }
        val lite = Resolved(
            video, "descrição curta", null, null, "20261009", emptyList(), emptyList(),
            listOf(Quality("1080p", 1080, "https://rapido/1080.mp4?itag=137&clen=1000", "https://rapido/audio?itag=140&clen=500")), "UA do iPhone",
            complete = false, rangeBridge = true,
        )
        val fileUrls = listOf("https://yt-dlp/v.mp4?itag=137&clen=1000", "https://yt-dlp/a.m4a?itag=140&clen=500", "https://yt-dlp/x?itag=251&clen=400")
        val session = PlaybackSession(scope, recording, Fakes(calls, fileUrls), TwitchSource(), KickSource(), data) { lite }
        session.open(video)
        withTimeout(2_000) { while (session.load.value !is LoadState.Ready) delay(10) }
        // Pronto antes de o yt-dlp responder: ainda sem curtidas, e o player já recebeu o endereço da via rápida.
        assertEquals(null, (session.load.value as LoadState.Ready).resolved.likes)
        withTimeout(2_000) { while (played.isEmpty()) delay(10) }
        assertEquals("https://rapido/1080.mp4?itag=137&clen=1000", played.first().videoUrl)
        assertTrue(played.first().bridgeRanges)
        // O player recebe, junto, os endereços completos do yt-dlp (que a ponte usa depois do primeiro minuto).
        val access = withTimeout(5_000) { played.first().fullAccess!!.await()!! }
        assertEquals("UA do yt-dlp", access.userAgent)
        assertEquals("https://yt-dlp/v.mp4?itag=137&clen=1000", access.match(played.first().videoUrl))
        assertEquals("https://yt-dlp/a.m4a?itag=140&clen=500", access.match(played.first().audioUrl!!))

        withTimeout(5_000) { while ((session.load.value as LoadState.Ready).resolved.likes == null) delay(20) }
        val done = (session.load.value as LoadState.Ready).resolved
        assertEquals(1234L, done.likes)
        assertEquals("descrição completa", done.description)
        assertEquals(listOf("Português"), done.subtitles.map { it.name })
        assertEquals(true, done.complete)
        assertEquals("https://rapido/1080.mp4?itag=137&clen=1000", done.qualities.single().videoUrl, "o que toca não muda: os endereços continuam os da via rápida")
        assertEquals(1, calls.get(), "o yt-dlp roda uma vez só: o mesmo resultado serve para os dados e para os endereços completos")
        assertEquals(1, played.size, "completar os dados não reabre o vídeo")
    }

    @Test
    fun se_o_yt_dlp_nao_tem_o_mesmo_arquivo_a_sessao_troca_para_a_qualidade_dele_na_posicao_atual() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-fast3").toFile()), scope)
        val player = FakePlayer2()
        val played = mutableListOf<app.apex.player.PlaySource>()
        val recording = object : app.apex.player.PlayerController by player {
            override fun play(source: app.apex.player.PlaySource) { played += source; player.play(source) }
        }
        val lite = Resolved(
            video, "descrição curta", null, null, "20261009", emptyList(), emptyList(),
            listOf(Quality("1080p", 1080, "https://rapido/1080.mp4?itag=137&clen=1000", "https://rapido/audio?itag=140&clen=500")), "UA do iPhone",
            complete = false, rangeBridge = true,
        )
        // O yt-dlp não lista o áudio (itag 140) deste vídeo: a ponte não teria de onde continuar o áudio depois do primeiro minuto.
        val fileUrls = listOf("https://yt-dlp/v.mp4?itag=137&clen=1000")
        val session = PlaybackSession(scope, recording, Fakes(AtomicInteger(), fileUrls), TwitchSource(), KickSource(), data) { lite }
        session.open(video)
        withTimeout(5_000) { while (played.size < 2) delay(20) }
        assertTrue(played[0].bridgeRanges)
        assertEquals("https://yt-dlp/720.mp4", played[1].videoUrl)
        assertEquals(false, played[1].bridgeRanges, "os endereços do yt-dlp tocam direto, sem ponte")
        assertEquals("UA do yt-dlp", played[1].userAgent)
        val ready = (session.load.value as LoadState.Ready).resolved
        assertEquals("https://yt-dlp/720.mp4", ready.qualities.single().videoUrl)
        assertEquals(false, ready.rangeBridge)
    }

    @Test
    fun se_o_yt_dlp_falha_o_erro_aparece_na_hora_em_vez_de_o_video_morrer_depois_do_primeiro_minuto() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-fast5").toFile()), scope)
        val failing = object : Extractor by Fakes(AtomicInteger()) {
            override suspend fun resolve(media: Media): Resolved {
                delay(100)
                throw app.apex.source.ExtractionException("O YouTube pediu confirmação de que você não é um robô.")
            }
        }
        val lite = Resolved(
            video, "descrição curta", null, null, "20261009", emptyList(), emptyList(),
            listOf(Quality("1080p", 1080, "https://rapido/1080.mp4?itag=137&clen=1000", "https://rapido/audio?itag=140&clen=500")), "UA do iPhone",
            complete = false, rangeBridge = true,
        )
        val session = PlaybackSession(scope, FakePlayer2(), failing, TwitchSource(), KickSource(), data) { lite }
        session.open(video)
        withTimeout(3_000) { while (session.load.value !is LoadState.Error) delay(10) }
        assertEquals("O YouTube pediu confirmação de que você não é um robô.", (session.load.value as LoadState.Error).message)
    }

    @Test
    fun a_via_rapida_que_nao_serve_nao_dispara_o_yt_dlp_duas_vezes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-fast4").toFile()), scope)
        val calls = AtomicInteger()
        val session = PlaybackSession(scope, FakePlayer2(), Fakes(calls), TwitchSource(), KickSource(), data) { null }
        session.open(video)
        withTimeout(3_000) { while (session.load.value !is LoadState.Ready) delay(10) }
        delay(300)
        assertEquals(1, calls.get(), "a via rápida recusou: vale o yt-dlp que já tinha partido junto")
    }

    @Test
    fun se_a_via_rapida_nao_serve_a_sessao_usa_o_yt_dlp_como_sempre() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-fast2").toFile()), scope)
        val calls = AtomicInteger()
        val session = PlaybackSession(scope, FakePlayer2(), Fakes(calls), TwitchSource(), KickSource(), data) { null }
        session.open(video)
        withTimeout(3_000) { while (session.load.value !is LoadState.Ready) delay(10) }
        assertEquals(1234L, (session.load.value as LoadState.Ready).resolved.likes)
        assertEquals(1, calls.get())
    }
}
