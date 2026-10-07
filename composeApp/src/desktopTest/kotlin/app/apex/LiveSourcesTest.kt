package app.apex

import app.apex.source.KickSource
import app.apex.source.TwitchSource
import app.apex.source.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** As consultas de lives da Twitch e da Kick (os limites e a paginação que os dois serviços exigem). */
class LiveSourcesTest {
    @Test
    fun kick_pede_a_proxima_pagina_com_after_e_nunca_passa_de_100() {
        val kick = KickSource()
        val first = kick.livesUrl("pt", "15", false, null, 24)
        assertTrue("language=pt" in first && "category_id=15" in first && "limit=24" in first && "sort=viewer_count_desc" in first, first)
        assertFalse("after=" in first || "cursor=" in first)

        val next = kick.livesUrl("pt", null, true, "eyJvIjoiMjQifQ==", 500)
        assertTrue("after=eyJvIjoiMjQifQ%3D%3D" in next, "o cursor vai em after= e codificado: $next")
        assertFalse("cursor=" in next, "o parâmetro cursor= é ignorado pela Kick e devolvia sempre a primeira página")
        assertTrue("limit=100" in next && "sort=viewer_count_asc" in next, next)
    }

    @Test
    fun twitch_so_aceita_30_por_consulta_e_completa_com_as_categorias_mais_vistas() {
        val tw = TwitchSource()
        val small = tw.topStreamsQuery(14, "")
        assertTrue("streams(first: 14" in small && "games(" !in small, small)
        assertTrue("streams(first: 30" in tw.topStreamsQuery(30, ""))

        val big = tw.topStreamsQuery(60, ", broadcasterLanguages: [PT]")
        assertTrue("streams(first: 30," in big, "a consulta principal nunca passa de 30: $big")
        assertTrue("first: 60" !in big && "first: 40" !in big, big)
        assertTrue("games(first: " in big, "as lives das categorias completam a lista")
        assertEquals(2, Regex("broadcasterLanguages: \\[PT]").findAll(big).count(), "o idioma vale para as duas partes")
        val games = Regex("games\\(first: (\\d+)").find(tw.topStreamsQuery(100, ""))!!.groupValues[1].toInt()
        assertTrue(games in 4..12, "categorias demais ou de menos para 100 lives: $games")
    }

    private fun node(login: String, viewers: Int) =
        """{"node":{"id":"$login","title":"live $login","viewersCount":$viewers,"game":{"displayName":"Jogo"},"broadcaster":{"login":"$login","displayName":"$login"}}}"""

    @Test
    fun twitch_reune_as_partes_sem_repetir_e_por_ordem_de_publico() {
        val tw = TwitchSource()
        val data = parseJson(
            """{"streams":{"edges":[${node("a", 900)},${node("b", 500)}]},
               "games":{"edges":[{"node":{"streams":{"edges":[${node("b", 500)},${node("c", 700)}]}}},
                                 {"node":{"streams":{"edges":[${node("d", 20)},${node("e", 800)}]}}}]}}""",
        )
        val all = tw.parseTopStreams(data, 10)
        assertEquals(listOf("a", "e", "c", "b", "d"), all.map { it.id }, "sem repetir o b e do maior para o menor público")
        assertEquals(listOf("a", "e", "c"), tw.parseTopStreams(data, 3).map { it.id }, "respeita o limite pedido")
        assertEquals(listOf("a", "b"), tw.parseTopStreams(parseJson("""{"streams":{"edges":[${node("a", 9)},${node("b", 5)}]}}"""), 30).map { it.id })
        assertEquals(emptyList(), tw.parseTopStreams(null, 30), "resposta vazia ou com erro: lista vazia, sem quebrar")
    }
}
