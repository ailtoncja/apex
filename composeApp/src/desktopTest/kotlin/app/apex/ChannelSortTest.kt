package app.apex

import app.apex.model.ChannelSort
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.sortedBy
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals

/** A ordem do resultado de uma busca dentro do canal (mais recentes, mais vistos, mais antigos). */
class ChannelSortTest {
    private fun video(id: String, published: Long?, views: Long?) =
        Media(Platform.YouTube, id, id, publishedAt = published, viewCount = views, url = "https://youtu.be/$id")

    private val results = listOf(
        video("a", published = 3_000, views = 10),
        video("sem-data", published = null, views = 5_000),
        video("b", published = 1_000, views = 900),
        video("c", published = 2_000, views = null),
    )

    /** Um clipe da Twitch na resposta do GraphQL. */
    private fun clip(slug: String, created: String, views: Int) =
        """{"node":{"slug":"$slug","title":"clipe $slug","createdAt":"$created","viewCount":$views,"durationSeconds":30,"thumbnailURL":"https://x/$slug.jpg","url":"https://www.twitch.tv/c/clip/$slug","broadcaster":{"login":"c","displayName":"C"}}}"""

    @Test
    fun os_clipes_da_twitch_juntam_as_listas_e_ordenam_aqui_mesmo_sem_em_alta() = kotlinx.coroutines.runBlocking<Unit> {
        // Como num canal sem clipe recente: o "em alta" vem vazio; os mais vistos de sempre e os do mês se repetem em parte.
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            val body = (request.body as io.ktor.content.TextContent).text
            val edges = when {
                "TRENDING" in body -> ""
                "ALL_TIME" in body -> listOf(clip("velho", "2021-01-01T00:00:00Z", 9_000), clip("mes", "2026-10-01T00:00:00Z", 500)).joinToString(",")
                "LAST_MONTH" in body -> listOf(clip("mes", "2026-10-01T00:00:00Z", 500), clip("novo", "2026-10-20T00:00:00Z", 40)).joinToString(",")
                else -> ""
            }
            respond(
                """{"data":{"user":{"clips":{"edges":[$edges]}}}}""", io.ktor.http.HttpStatusCode.OK,
                io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        })
        val twitch = app.apex.source.TwitchSource(client)
        assertEquals(listOf("velho", "mes", "novo"), twitch.clips("c", app.apex.model.ClipSort.Popular).map { it.id.removePrefix(app.apex.model.CLIP_PREFIX) })
        assertEquals(listOf("novo", "mes", "velho"), twitch.clips("c", app.apex.model.ClipSort.Recent).map { it.id.removePrefix(app.apex.model.CLIP_PREFIX) }, "recentes mesmo sem o em alta")
    }

    @Test
    fun ordena_pela_data_e_pelas_visualizacoes_com_os_sem_informacao_no_fim() {
        assertEquals(listOf("a", "c", "b", "sem-data"), sortedBy(results, ChannelSort.Recent).map { it.id })
        assertEquals(listOf("b", "c", "a", "sem-data"), sortedBy(results, ChannelSort.Oldest).map { it.id })
        assertEquals(listOf("sem-data", "b", "a", "c"), sortedBy(results, ChannelSort.Popular).map { it.id })
    }
}
