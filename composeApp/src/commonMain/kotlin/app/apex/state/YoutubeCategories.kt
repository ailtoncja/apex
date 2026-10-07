package app.apex.state

import app.apex.model.LiveCategory

/*
 * O YouTube não tem uma área de categorias como a da Twitch e da Kick, então as daqui são buscas prontas:
 * cada categoria tem uma busca de lives ("Ao vivo") e uma de vídeos ("Vídeos").
 */

private const val GAME_PREFIX = "yt:game:"

/** Categorias de assunto (sem capa: o cartão ganha uma cor). O [LiveCategory.id] é a chave de [TOPIC_QUERIES]. */
val YOUTUBE_TOPIC_CATEGORIES: List<LiveCategory> = listOf(
    "musica" to "Música", "futebol" to "Futebol", "esportes" to "Esportes", "noticias" to "Notícias", "humor" to "Humor",
    "podcasts" to "Podcasts", "culinaria" to "Culinária", "filmes" to "Filmes e séries", "tecnologia" to "Tecnologia",
    "educacao" to "Educação", "ciencia" to "Ciência", "carros" to "Carros", "animacao" to "Animação", "viagens" to "Viagens",
    "beleza" to "Beleza e moda", "criancas" to "Infantil",
).map { (id, name) -> LiveCategory("yt:$id", name, null, null) }

/** A busca de lives e a de vídeos de cada assunto. */
private val TOPIC_QUERIES: Map<String, Pair<String, String>> = mapOf(
    "yt:musica" to ("música ao vivo" to "música clipe oficial"),
    "yt:futebol" to ("futebol ao vivo" to "futebol melhores momentos gols"),
    "yt:esportes" to ("esportes ao vivo" to "esportes melhores momentos"),
    "yt:noticias" to ("notícias ao vivo" to "notícias hoje"),
    "yt:humor" to ("humor ao vivo" to "humor comédia"),
    "yt:podcasts" to ("podcast ao vivo" to "podcast episódio completo"),
    "yt:culinaria" to ("culinária ao vivo" to "receitas culinária"),
    "yt:filmes" to ("cinema ao vivo" to "trailer filme série"),
    "yt:tecnologia" to ("tecnologia ao vivo" to "tecnologia review"),
    "yt:educacao" to ("aula ao vivo" to "aula tutorial"),
    "yt:ciencia" to ("ciência ao vivo" to "ciência documentário"),
    "yt:carros" to ("carros ao vivo" to "carros review"),
    "yt:animacao" to ("animação ao vivo" to "animação episódio"),
    "yt:viagens" to ("viagem ao vivo" to "viagem vlog"),
    "yt:beleza" to ("moda beleza ao vivo" to "maquiagem moda tutorial"),
    "yt:criancas" to ("infantil ao vivo" to "desenho infantil"),
)

/** Categorias da Twitch que não são jogos (não fazem sentido como "jogo" no YouTube). */
private val NOT_GAMES = setOf(
    "just chatting", "special events", "talk shows & podcasts", "music", "art", "irl", "sports", "slots", "pools, hot tubs, and beaches",
    "travel & outdoors", "food & drink", "asmr", "fitness & health", "science & technology", "software and game development", "politics",
    "makers & crafting", "animals, aquariums, and zoos", "just sleeping", "gambling", "i'm only sleeping", "crypto",
)

/** Os jogos mais assistidos (a lista e as capas vêm da Twitch). Sem a lista, não há capas: a fileira some. */
fun youtubeGameCategories(twitch: List<LiveCategory>): List<LiveCategory> =
    twitch.filter { it.name.lowercase() !in NOT_GAMES }.take(18).map { LiveCategory(GAME_PREFIX + it.name, it.name, null, it.imageUrl) }

/** (busca de lives, busca de vídeos) de uma categoria do YouTube. */
fun youtubeCategoryQueries(category: LiveCategory): Pair<String, String> = when {
    category.id.startsWith(GAME_PREFIX) -> category.name to "${category.name} gameplay"
    else -> TOPIC_QUERIES[category.id] ?: (category.name to category.name)
}
