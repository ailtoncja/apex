package app.apex

import app.apex.model.ChannelSort
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.sortedBy
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

    @Test
    fun ordena_pela_data_e_pelas_visualizacoes_com_os_sem_informacao_no_fim() {
        assertEquals(listOf("a", "c", "b", "sem-data"), sortedBy(results, ChannelSort.Recent).map { it.id })
        assertEquals(listOf("b", "c", "a", "sem-data"), sortedBy(results, ChannelSort.Oldest).map { it.id })
        assertEquals(listOf("sem-data", "b", "a", "c"), sortedBy(results, ChannelSort.Popular).map { it.id })
    }
}
