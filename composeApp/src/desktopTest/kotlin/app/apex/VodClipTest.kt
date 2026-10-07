package app.apex

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.CLIP_PREFIX
import app.apex.model.Channel
import app.apex.model.ClipSort
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.model.VOD_PREFIX
import app.apex.player.PlaySource
import app.apex.player.PlaybackSession
import app.apex.player.PlayerController
import app.apex.player.PlayerState
import app.apex.source.Extractor
import app.apex.source.ExtractorState
import app.apex.source.KickSource
import app.apex.source.LinkTarget
import app.apex.source.TwitchSource
import app.apex.source.YouTubeSource
import app.apex.source.InnerTube
import app.apex.source.parseJson
import app.apex.source.parseLink
import app.apex.util.parseIsoMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VODs, clipes, links colados e a retomada do ponto em que a pessoa parou. */
class VodClipTest {
    // ------------------------------------------------------------------ datas

    @Test
    fun datas_iso_da_twitch_e_da_kick_viram_milissegundos() {
        assertEquals(Instant.parse("2026-10-05T19:21:37Z").toEpochMilli(), parseIsoMillis("2026-10-05T19:21:37Z"))
        assertEquals(Instant.parse("2026-10-06T11:49:58Z").toEpochMilli(), parseIsoMillis("2026-10-06 11:49:58"))
        // Com frações de segundo e fuso "Z", como a Kick manda nos clipes.
        assertEquals(Instant.parse("2025-07-17T01:53:41Z").toEpochMilli(), parseIsoMillis("2025-07-17T01:53:41.482383Z"))
        assertNull(parseIsoMillis(null))
        assertNull(parseIsoMillis("ontem"))
    }

    // ------------------------------------------------------------------ Twitch

    @Test
    fun vod_da_twitch_vira_media_com_prefixo_e_sem_miniatura_de_erro() {
        val twitch = TwitchSource()
        val vod = twitch.vodToMedia(
            parseJson(
                """{"id":"2890990772","title":"RERUN: Jogos Variados!","lengthSeconds":172771,"createdAt":"2026-10-02T19:21:37Z","viewCount":34780,
                "previewThumbnailURL":"https://static-cdn.jtvnw.net/cf_vods/d1m7jfoe9zdc1j/a80b13bc/thumb/thumb0-440x248.jpg","game":{"displayName":"Frostpunk 2"},
                "owner":{"login":"gaules","displayName":"Gaules","profileImageURL":"https://static-cdn.jtvnw.net/a.png"}}""",
            ),
        )
        assertNotNull(vod)
        assertEquals(VOD_PREFIX + "2890990772", vod.id)
        assertTrue(vod.isVod && !vod.isClip && !vod.isLive)
        assertEquals("Twitch:vod-2890990772", vod.key)
        assertEquals(172771L, vod.durationSec)
        assertEquals("https://www.twitch.tv/videos/2890990772", vod.url)
        assertEquals("gaules", vod.channel?.id)
        assertEquals("Frostpunk 2", vod.category)
        assertNotNull(vod.publishedAt)

        // VOD ainda sendo processado: a Twitch devolve uma imagem de "404", que é melhor não mostrar.
        val processing = twitch.vodToMedia(
            parseJson("""{"id":"1","title":"t","previewThumbnailURL":"https://vod-secure.twitch.tv/_404/404_processing_440x248.png","owner":{"login":"x","displayName":"X"}}"""),
        )
        assertNull(processing?.thumbnailUrl)
    }

    @Test
    fun clipe_da_twitch_e_suas_qualidades_com_assinatura() {
        val twitch = TwitchSource()
        val clip = twitch.clipToMedia(
            parseJson(
                """{"slug":"ReliableGlutenFreeEmuRickroll-rYlhlD4V80oK6-r-","title":"peidano","viewCount":130,"createdAt":"2026-09-14T15:41:19Z","durationSeconds":59,
                "thumbnailURL":"https://static-cdn.jtvnw.net/t.jpg","url":"https://www.twitch.tv/gaules/clip/ReliableGlutenFreeEmuRickroll-rYlhlD4V80oK6-r-",
                "broadcaster":{"login":"gaules","displayName":"Gaules"},"game":{"displayName":"Lycans"}}""",
            ),
        )
        assertNotNull(clip)
        assertEquals(CLIP_PREFIX + "ReliableGlutenFreeEmuRickroll-rYlhlD4V80oK6-r-", clip.id)
        assertTrue(clip.isClip && !clip.isVod)
        assertEquals(59L, clip.durationSec)
        assertEquals("Gaules", clip.channel?.name)

        val qualities = twitch.clipQualities(
            parseJson(
                """[{"frameRate":30.0055,"quality":"480","sourceURL":"https://cdn.exemplo.com/480/index.mp4"},
                    {"frameRate":60,"quality":"1080","sourceURL":"https://cdn.exemplo.com/1080/index.mp4"},
                    {"frameRate":60.011,"quality":"720","sourceURL":"https://cdn.exemplo.com/720/index.mp4"}]""",
            ).let { (it as kotlinx.serialization.json.JsonArray).toList() },
            token = """{"clip_uri":"x y","version":3}""", sig = "abc123",
        )
        assertEquals(listOf("1080p60", "720p60", "480p"), qualities.map { it.label })
        // O token vai codificado no endereço (tem aspas, chaves e espaços); sem assinatura o arquivo não toca.
        assertTrue(qualities.first().videoUrl.startsWith("https://cdn.exemplo.com/1080/index.mp4?sig=abc123&token="))
        assertFalse(qualities.first().videoUrl.contains(" ") || qualities.first().videoUrl.contains("{"))
    }

    // ------------------------------------------------------------------ Kick

    @Test
    fun vod_e_clipe_da_kick() {
        val kick = KickSource()
        val canal = Channel(Platform.Kick, "gaules", "Gaules", url = "https://kick.com/gaules")

        val vod = kick.vodToMedia(
            parseJson(
                """{"is_live":false,"session_title":"ESL PRO LEAGUE DIA 4","is_mature":false,"duration":84996000,"start_time":"2026-10-06T11:49:58+00:00",
                "thumbnail":{"src":"https://images.kick.com/v.webp"},"categories":[{"name":"Counter-Strike 2"}],
                "video":{"uuid":"5c3fd60c-08c0-4631-baef-19fcbf7a5426","views":67494,"status":"public","is_private":false,"is_pruned":false}}""",
            ),
            canal,
        )
        assertNotNull(vod)
        assertEquals("vod-5c3fd60c-08c0-4631-baef-19fcbf7a5426", vod.id)
        assertEquals(84996L, vod.durationSec)
        assertEquals("https://kick.com/gaules/videos/5c3fd60c-08c0-4631-baef-19fcbf7a5426", vod.url)
        assertEquals("Counter-Strike 2", vod.category)

        // Ao vivo agora, apagado e privado não entram na lista de VODs.
        assertNull(kick.vodToMedia(parseJson("""{"is_live":true,"video":{"uuid":"u1"}}"""), canal))
        assertNull(kick.vodToMedia(parseJson("""{"is_live":false,"video":{"uuid":"u2","is_pruned":true}}"""), canal))
        assertNull(kick.vodToMedia(parseJson("""{"is_live":false,"video":{"uuid":"u3","is_private":true}}"""), canal))

        val clip = kick.clipToMedia(
            parseJson(
                """{"id":"clip_01K0B19VP2GKVQXRNHCS8ZMS52","title":"é isso","clip_url":"https://clips.kick.com/p.m3u8","thumbnail_url":"https://clips.kick.com/t.webp",
                "views":22964,"view_count":22964,"duration":60,"created_at":"2025-07-17T01:53:41.482383Z","category":{"name":"Just Chatting"},
                "channel":{"slug":"gaules","username":"Gaules"}}""",
            ),
            canal,
        )
        assertNotNull(clip)
        assertEquals("clip-clip_01K0B19VP2GKVQXRNHCS8ZMS52", clip.id)
        assertEquals(60L, clip.durationSec)
        assertEquals("https://kick.com/gaules/clips/clip_01K0B19VP2GKVQXRNHCS8ZMS52", clip.url)
        assertEquals(22964L, clip.viewCount)
        assertEquals(canal, clip.channel)
    }

    // ------------------------------------------------------------------ YouTube

    @Test
    fun clipe_do_youtube_da_lista_da_conta_traz_o_video_e_o_trecho() {
        val yt = YouTubeSource(InnerTube())
        val item = parseJson(
            """{"videoId":"KV_u63XrjyA","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/KV_u63XrjyA/hqdefault.jpg","width":168,"height":94},
              {"url":"https://i.ytimg.com/vi/KV_u63XrjyA/hqdefault.jpg?x=1","width":336,"height":188}]},
            "title":{"runs":[{"text":"PIKA"}]},"longBylineText":{"runs":[{"text":"de "},{"text":"Dj Aimi"}]},
            "publishedTimeText":{"runs":[{"text":"Clipe criado "},{"text":"há 6 meses"}]},"viewCountText":{"simpleText":"2 visualizações"},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"KV_u63XrjyA","watchEndpointClipConfig":{"clipConfig":{
              "postId":"UgkxGS2nGX8PbBfbmRww5ev16PdCsH_VNaZw","startTimeMs":"1991669","endTimeMs":"2041234"}}}}}""",
        )!!
        val clip = yt.clipItemToMedia(item, System.currentTimeMillis())
        assertNotNull(clip)
        assertEquals("clip-UgkxGS2nGX8PbBfbmRww5ev16PdCsH_VNaZw", clip.id)
        assertEquals("PIKA", clip.title)
        assertEquals(49L, clip.durationSec)
        assertEquals(1_991_669L, clip.clipStartMs)
        assertEquals(2_041_234L, clip.clipEndMs)
        assertEquals("https://www.youtube.com/watch?v=KV_u63XrjyA", clip.url)
        // O id usado nas chamadas do YouTube é o do vídeo original, não o "clip-…".
        assertEquals("KV_u63XrjyA", clip.videoId)
        assertEquals("Dj Aimi", clip.channel?.name)
        assertNotNull(clip.publishedAt)
        assertEquals(2L, clip.viewCount)

        // Sem trecho não há clipe.
        assertNull(yt.clipItemToMedia(parseJson("""{"videoId":"x","title":{"simpleText":"t"}}""")!!, 0))
    }

    @Test
    fun clipe_do_youtube_aberto_pelo_link_le_a_pagina_dele() {
        val yt = YouTubeSource(InnerTube())
        val html = """<html><script>var ytInitialData = {"currentVideoEndpoint":{"watchEndpoint":{"videoId":"vWx8pFWvhik"}},
            "contents":{"clipAttributionRenderer":{"title":{"runs":[{"text":"Meu clipe"}]},"createdText":{"simpleText":"8 visualizações · há 4 anos"}},
            "videoOwnerRenderer":{"title":{"runs":[{"text":"Canal X","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}]},
            "thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a"}]}}}};</script>
            <script>var ytInitialPlayerResponse = {"clipConfig":{"postId":"UgkxABC","startTimeMs":"1655843","endTimeMs":"1688913"}};</script></html>"""
        val clip = yt.clipFromPage("UgkxABC", html)
        assertNotNull(clip)
        assertEquals("clip-UgkxABC", clip.id)
        assertEquals("Meu clipe", clip.title)
        assertEquals(33L, clip.durationSec)
        assertEquals(1_655_843L, clip.clipStartMs)
        assertEquals(1_688_913L, clip.clipEndMs)
        assertEquals("vWx8pFWvhik", clip.videoId)
        assertEquals("UC123", clip.channel?.id)
        assertEquals(8L, clip.viewCount)
        assertNotNull(clip.publishedAt)

        // Página sem o trecho (clipe apagado): não inventa nada.
        assertNull(yt.clipFromPage("x", "<html><script>var ytInitialData = {\"a\":1};</script></html>"))
    }

    // ------------------------------------------------------------------ links

    private fun media(text: String): Media? = (parseLink(text) as? LinkTarget.Play)?.media

    @Test
    fun links_de_video_live_vod_e_clipe_sao_reconhecidos() {
        assertEquals("dQw4w9WgXcQ", media("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")?.id)
        assertEquals("dQw4w9WgXcQ", media("youtu.be/dQw4w9WgXcQ?si=abc")?.id)
        assertEquals("dQw4w9WgXcQ", media("https://youtube.com/live/dQw4w9WgXcQ")?.id)
        assertEquals(LinkTarget.YouTubeClip("Ugkx2S9T0AKiMNFCFUgzra2ajzTKcNj0FAGT"), parseLink("https://www.youtube.com/clip/Ugkx2S9T0AKiMNFCFUgzra2ajzTKcNj0FAGT"))

        assertEquals("vod-2890990772", media("https://www.twitch.tv/videos/2890990772?t=1h2m")?.id)
        assertEquals("clip-ReliableGlutenFreeEmuRickroll-rYlhlD4V80oK6-r-", media("https://www.twitch.tv/gaules/clip/ReliableGlutenFreeEmuRickroll-rYlhlD4V80oK6-r-?filter=clips")?.id)
        assertEquals("clip-ObeseGracefulDaikonBrainSlug-BROgVd", media("https://clips.twitch.tv/ObeseGracefulDaikonBrainSlug-BROgVd")?.id)
        val live = media("https://www.twitch.tv/Gaules")
        assertEquals("gaules", live?.id)
        assertTrue(live?.isLive == true)

        assertEquals("vod-5c3fd60c-08c0-4631-baef-19fcbf7a5426", media("https://kick.com/gaules/videos/5c3fd60c-08c0-4631-baef-19fcbf7a5426")?.id)
        assertEquals("clip-clip_01K0B19VP2GKVQXRNHCS8ZMS52", media("https://kick.com/gaules/clips/clip_01K0B19VP2GKVQXRNHCS8ZMS52")?.id)
        assertEquals("clip-clip_01K0B19VP2GKVQXRNHCS8ZMS52", media("https://kick.com/gaules?clip=clip_01K0B19VP2GKVQXRNHCS8ZMS52")?.id)
        assertEquals("yoda", media("kick.com/yoda")?.id)
    }

    @Test
    fun texto_comum_e_paginas_do_site_nao_viram_link() {
        listOf(
            "gaules clipes", "cs2 highlights", "o que é kotlin", "",
            "https://www.twitch.tv/directory", "https://www.twitch.tv/videos", "https://kick.com/categories",
            "https://exemplo.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            // Endereços que não são http(s) nunca são aceitos.
            "javascript:alert(1)", "file:///C:/Windows/system.ini",
        ).forEach { assertNull(parseLink(it), "não devia reconhecer: $it") }
    }

    // ------------------------------------------------------------------ retomar de onde parou

    private class FakePlayer : PlayerController {
        val played = mutableListOf<PlaySource>()
        val rates = mutableListOf<Float>()
        private val _state = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = _state
        override fun play(source: PlaySource) { played += source }
        override fun togglePause() {}
        override fun pause() {}
        override fun resume() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(deltaMs: Long) {}
        override fun setVolume(percent: Int) {}
        override fun setMuted(muted: Boolean) {}
        override fun setRate(rate: Float) { rates += rate }
        override fun setSubtitle(url: String?) {}
        override fun stop() {}
        override fun release() {}
        @Composable override fun Video(modifier: Modifier) {}
    }

    private class FakeExtractor(private val resolved: Resolved) : Extractor {
        override val state = MutableStateFlow<ExtractorState>(ExtractorState.Ready)
        override suspend fun ensureReady(): ExtractorState = ExtractorState.Ready
        override suspend fun resolve(media: Media): Resolved = resolved
        override suspend fun comments(media: Media, limit: Int, newest: Boolean) = emptyList<app.apex.model.Comment>()
        override suspend fun channelDetails(channelId: String) = error("não usado")
        override suspend fun updateEngine() = ""
    }

    private fun resolved(media: Media, live: Boolean = false) = Resolved(
        media = media, description = null, likes = null, subscribers = null, uploadDate = null,
        chapters = emptyList(), subtitles = emptyList(), qualities = listOf(Quality("720p", 720, "https://exemplo.com/v.mp4")),
        userAgent = null, isLive = live,
    )

    private fun session(media: Media, live: Boolean = false, setup: (UserData) -> Unit = {}): Triple<PlaybackSession, FakePlayer, UserData> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-session").toFile()), scope)
        setup(data)
        val player = FakePlayer()
        return Triple(PlaybackSession(scope, player, FakeExtractor(resolved(media, live)), TwitchSource(), KickSource(), data), player, data)
    }

    private suspend fun FakePlayer.waitForPlay(): PlaySource {
        repeat(100) { if (played.isNotEmpty()) return played.first(); delay(50) }
        error("o player não recebeu nada para tocar")
    }

    @Test
    fun retoma_de_onde_a_pessoa_parou_e_nao_zera_o_historico() = runBlocking<Unit> {
        val video = Media(Platform.YouTube, "abcdefghijk", "Vídeo", url = "https://www.youtube.com/watch?v=abcdefghijk", durationSec = 600)
        val (session, player, data) = session(video) { it.recordHistory(video, 120_000, 600_000) }
        session.open(video)
        assertEquals(120_000L, player.waitForPlay().startMs)
        // Abrir o vídeo sobe ele no histórico, mas o ponto em que parou continua lá.
        assertEquals(120_000L, data.history.value.first().positionMs)
        assertEquals(600_000L, data.history.value.first().durationMs)
        session.close()
    }

    @Test
    fun clipe_do_youtube_toca_so_o_trecho_e_nao_retoma() = runBlocking<Unit> {
        val clip = Media(
            Platform.YouTube, "clip-UgkxABC", "Meu clipe", url = "https://www.youtube.com/watch?v=vWx8pFWvhik",
            durationSec = 33, clipStartMs = 1_655_843, clipEndMs = 1_688_913,
        )
        val (session, player, data) = session(clip) { it.recordHistory(clip, 20_000, 33_000) }
        session.open(clip)
        val source = player.waitForPlay()
        assertEquals(1_655_843L, source.startMs)
        assertEquals(1_688_913L, source.endMs)
        session.close()
        assertNotNull(data)
    }

    @Test
    fun live_toca_sempre_em_velocidade_normal() = runBlocking<Unit> {
        val live = Media(Platform.Twitch, "gaules", "Live", isLive = true, url = "https://www.twitch.tv/gaules")
        val (session, player, _) = session(live, live = true) { d -> d.updateSettings { it.copy(defaultRate = 2f) } }
        session.open(live)
        player.waitForPlay()
        delay(200)
        assertEquals(1f, player.rates.last())
        session.close()
    }

    @Test
    fun video_gravado_usa_a_velocidade_padrao() = runBlocking<Unit> {
        val video = Media(Platform.YouTube, "abcdefghijk", "Vídeo", url = "https://www.youtube.com/watch?v=abcdefghijk")
        val (session, player, _) = session(video) { d -> d.updateSettings { it.copy(defaultRate = 1.5f) } }
        session.open(video)
        player.waitForPlay()
        delay(200)
        assertEquals(1.5f, player.rates.last())
        session.close()
    }

    @Test
    fun ordens_de_clipe_tem_rotulos_em_portugues() {
        assertEquals(listOf("Mais vistos", "Mais recentes"), ClipSort.entries.map { it.label })
    }
}
