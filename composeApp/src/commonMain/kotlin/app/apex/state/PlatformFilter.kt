package app.apex.state

import app.apex.model.Channel
import app.apex.model.HistoryEntry
import app.apex.model.Media
import app.apex.model.Platform

/*
 * Filtros por plataforma que se combinam: escolher YouTube e Twitch mostra as duas; nenhuma escolhida (ou as três) mostra todas.
 */

/** O filtro deixa passar esta plataforma? (Vazio = todas.) */
fun Set<Platform>.allows(platform: Platform): Boolean = isEmpty() || platform in this

/** Liga ou desliga uma plataforma; com as três ligadas volta a "todas" (vazio). */
fun Set<Platform>.toggled(platform: Platform): Set<Platform> {
    val next = if (platform in this) this - platform else this + platform
    return if (next.size >= Platform.entries.size) emptySet() else next
}

fun List<Channel>.channelsIn(platforms: Set<Platform>): List<Channel> = if (platforms.isEmpty()) this else filter { it.platform in platforms }

fun List<Media>.mediaIn(platforms: Set<Platform>): List<Media> = if (platforms.isEmpty()) this else filter { it.platform in platforms }

/** "YouTube e Twitch", "Kick", "todas as plataformas". */
fun Set<Platform>.describe(): String = when (size) {
    0 -> "todas as plataformas"
    1 -> first().label
    else -> map { it.label }.let { it.dropLast(1).joinToString(", ") + " e " + it.last() }
}

/** O que a tela inicial pode mostrar; a pessoa liga quantos quiser ao mesmo tempo (nenhum ligado = tudo, como sempre foi). */
enum class HomeSource(val label: String) {
    Subs("Inscrições"),
    Recs("Recomendados"),
    Trending("Em alta"),
    Live("Ao vivo"),
}

fun <E : Enum<E>> List<String>.toEnumSet(values: Array<E>): Set<E> = mapNotNull { name -> values.firstOrNull { it.name == name } }.toSet()

/** As categorias (já sem acento e em minúsculas) que a pessoa mais assistiu, da mais para a menos vista. */
fun watchedCategories(history: List<HistoryEntry>, limit: Int = 5): List<String> =
    history.mapNotNull { it.media.category?.takeIf { c -> c.isNotBlank() }?.let(::normalizeText) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(limit).map { it.key }

/** As lives que estão nessas categorias (a das categorias mais vistas primeiro; dentro de cada uma segue a ordem de público). */
fun List<Media>.inCategories(categories: List<String>): List<Media> {
    if (categories.isEmpty()) return emptyList()
    val rank = categories.withIndex().associate { (i, c) -> c to i }
    return mapNotNull { m -> m.category?.let(::normalizeText)?.let { c -> rank[c]?.let { r -> r to m } } }.sortedBy { it.first }.map { it.second }
}
