package app.apex

import app.apex.model.Platform
import app.apex.source.InnerTube
import app.apex.source.LinkTarget
import app.apex.source.YouTubeSource
import app.apex.source.parseLink
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lives do YouTube no Multi: pelo @handle, pelo nome do canal ou pela página do canal, com a live procurada em segundo plano. */
class MultiYouTubeTest {

    /** Um YouTube de mentira: resolve o @cazetv, acha "Cazé" na busca, e o canal UCCAZE tem uma live no ar; o UCVAZIO não tem nada. */
    private class FakeTube {
        private val json = headersOf(HttpHeaders.ContentType, "application/json")
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = (request.body as? TextContent)?.text.orEmpty()
            calls += path.substringAfterLast("v1/")
            when {
                path.endsWith("navigation/resolve_url") ->
                    if ("/@cazetv\"" in body.lowercase()) respond("""{"endpoint":{"browseEndpoint":{"browseId":"UCCAZE000000000000000000"}}}""", HttpStatusCode.OK, json)
                    else respond("""{"error":{"code":404}}""", HttpStatusCode.NotFound, json)
                path.endsWith("search") ->
                    if ("Caz" in body) respond("""{"contents":[{"channelRenderer":{"channelId":"UCCAZE000000000000000000","title":{"simpleText":"CazéTV"}}}]}""", HttpStatusCode.OK, json)
                    else respond("""{"contents":[]}""", HttpStatusCode.OK, json)
                path.endsWith("browse") ->
                    if ("UCCAZE" in body || "VLUULVCAZE" in body) respond(
                        """{"contents":[{"videoRenderer":{"videoId":"liveCAZE001","title":{"simpleText":"AO VIVO: jogo"},"badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW"}}],
                           "ownerText":{"runs":[{"text":"CazéTV","navigationEndpoint":{"browseEndpoint":{"browseId":"UCCAZE000000000000000000"}}}]}}},
                           {"videoRenderer":{"videoId":"oldCAZE0001","title":{"simpleText":"gravado"}}}]}""", HttpStatusCode.OK, json,
                    ) else respond("""{"contents":[]}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        })
    }

    @Test
    fun links_de_canal_do_youtube_sao_reconhecidos() {
        assertEquals(LinkTarget.YouTubeChannel("@CazeTV"), parseLink("https://www.youtube.com/@CazeTV"))
        assertEquals(LinkTarget.YouTubeChannel("@CazeTV"), parseLink("youtube.com/@CazeTV/live"))
        assertEquals(LinkTarget.YouTubeChannel("UCZiYbVptd3PVPf4f6eR6UaQ"), parseLink("https://www.youtube.com/channel/UCZiYbVptd3PVPf4f6eR6UaQ/live"))
        assertEquals(LinkTarget.YouTubeChannel("canalx"), parseLink("https://www.youtube.com/c/canalx"))
        assertTrue(parseLink("https://www.youtube.com/watch?v=q_ugzqXh5NQ") is LinkTarget.Play, "vídeo continua vídeo")
        assertNull(parseLink("https://www.youtube.com/feed/subscriptions"))
        assertNull(parseLink("https://www.youtube.com/channel/curto"))
    }

    @Test
    fun o_canal_e_achado_pelo_id_pelo_handle_ou_pelo_nome() = runBlocking {
        val fake = FakeTube()
        val yt = YouTubeSource(InnerTube(fake.client))
        assertEquals("UCZiYbVptd3PVPf4f6eR6UaQ", yt.findChannelId("UCZiYbVptd3PVPf4f6eR6UaQ"))
        assertTrue(fake.calls.isEmpty(), "um id não precisa da rede")
        assertEquals("UCCAZE000000000000000000", yt.findChannelId("@CazeTV"))
        assertEquals(listOf("navigation/resolve_url"), fake.calls)
        assertEquals("UCCAZE000000000000000000", yt.findChannelId("Cazé TV"), "nome com espaço não é @handle: vai pela busca")
        assertEquals("search", fake.calls.last())
        assertNull(yt.findChannelId("@naoexiste"), "um @ que não existe não vira busca")
        assertNull(yt.findChannelId("zzz nada"))
        assertEquals("liveCAZE001", yt.channelLive("UCCAZE000000000000000000")?.id, "a live no ar do canal")
    }

    @Test
    fun no_multi_o_nome_do_canal_do_youtube_vira_a_live_dele_e_sem_live_avisa() = runBlocking {
        val fake = FakeTube()
        val (app, _) = newTestApp(fake.client)
        assertTrue(app.addToMulti("@cazetv", Platform.YouTube), "aceito: a live é procurada em segundo plano")
        withTimeout(5_000) { while (app.multi.medias.isEmpty()) delay(10) }
        assertEquals(listOf("YouTube:liveCAZE001"), app.multi.medias.map { it.key })
        assertTrue(app.ui.toast!!.contains("entrou no Multi"))

        assertTrue(app.addToMulti("https://www.youtube.com/@cazetv/live", Platform.Twitch), "a página do canal vale de qualquer chip")
        withTimeout(5_000) { while (app.ui.toast?.contains("já está") != true) delay(10) }
        assertEquals(1, app.multi.medias.size, "a mesma live não entra duas vezes")

        assertTrue(app.addToMulti("@naoexiste", Platform.YouTube))
        withTimeout(5_000) { while (app.ui.toast?.contains("Não achei") != true) delay(10) }

        // Um canal que existe mas não está ao vivo.
        assertTrue(app.addToMulti("UCVAZIO00000000000000000", Platform.YouTube))
        withTimeout(5_000) { while (app.ui.toast?.contains("não está ao vivo") != true) delay(10) }
        assertEquals(1, app.multi.medias.size)
    }
}
