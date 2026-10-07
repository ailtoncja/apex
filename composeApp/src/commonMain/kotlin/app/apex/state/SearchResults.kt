package app.apex.state

import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.ChannelHit
import app.apex.source.RemotePlaylist
import app.apex.source.SearchFilters
import app.apex.source.SearchType

/*
 * A pesquisa como a do YouTube: uma lista só (canais, lives, vídeos e playlists na ordem em que importam), com abas de tipo no topo.
 * Esta parte não desenha nada: recebe o que cada fonte devolveu e decide o que aparece e em que ordem.
 */

/** As abas de tipo do topo dos resultados. Elas ligam os filtros de tipo do YouTube ([SearchFilters.kind]). */
enum class SearchKind(val label: String) {
    All("Tudo"), Videos("Vídeos"), Lives("Ao vivo"), Channels("Canais"), Playlists("Playlists"),
}

/** Qual aba de tipo está valendo nestes filtros. */
fun SearchFilters.kind(): SearchKind = when {
    liveOnly -> SearchKind.Lives
    channelsOnly || type == SearchType.Channel -> SearchKind.Channels
    type == SearchType.Playlist -> SearchKind.Playlists
    type == SearchType.Video -> SearchKind.Videos
    else -> SearchKind.All
}

/** Os mesmos filtros com a aba de tipo trocada (os outros filtros ficam). */
fun SearchFilters.withKind(kind: SearchKind): SearchFilters = when (kind) {
    SearchKind.All -> copy(type = SearchType.Any, liveOnly = false, channelsOnly = false)
    SearchKind.Videos -> copy(type = SearchType.Video, liveOnly = false, channelsOnly = false)
    SearchKind.Lives -> copy(type = SearchType.Any, liveOnly = true, channelsOnly = false)
    SearchKind.Channels -> copy(type = SearchType.Channel, liveOnly = false, channelsOnly = false)
    SearchKind.Playlists -> copy(type = SearchType.Playlist, liveOnly = false, channelsOnly = false)
}

/** Quantos filtros além da aba de tipo estão ligados (a aba já aparece sozinha no topo). */
val SearchFilters.extraCount: Int get() = activeCount - (if (type != SearchType.Any || channelsOnly) 1 else 0) - (if (liveOnly) 1 else 0)

/** Uma linha dos resultados. */
sealed interface SearchEntry {
    val key: String
}

data class ChannelEntry(val channel: Channel, val live: Media?) : SearchEntry {
    override val key: String get() = "c-" + channel.key
}

data class MediaEntry(val media: Media) : SearchEntry {
    override val key: String get() = "m-" + media.key
}

data class PlaylistEntry(val playlist: RemotePlaylist) : SearchEntry {
    override val key: String get() = "p-" + playlist.id
}

/** Tudo o que as fontes devolveram para a consulta. */
class SearchSources(
    val ytVideos: List<Media> = emptyList(),
    val ytChannels: List<Channel> = emptyList(),
    val ytPlaylists: List<RemotePlaylist> = emptyList(),
    val twitchLives: List<Media> = emptyList(),
    val twitchChannels: List<ChannelHit> = emptyList(),
    val kickChannels: List<ChannelHit> = emptyList(),
    /** O que combina com a busca entre os canais que a pessoa segue (já filtrado pelas plataformas). */
    val mine: SubscriptionMatches = SubscriptionMatches(emptyList(), emptyList()),
    /** As chaves dos canais que a pessoa segue: eles vêm antes dos outros. */
    val followed: Set<String> = emptySet(),
)

/** Quantas linhas de canais e de lives entram na aba "Tudo" antes dos vídeos (como no YouTube, só os melhores). */
private const val ALL_CHANNELS = 2
private const val ALL_LIVES = 5

/** As playlists entram no meio dos vídeos, nestas posições. */
private val PLAYLIST_SLOTS = listOf(4, 10)

/**
 * A lista de resultados da aba [kind] para as [platforms] ligadas (vazio = todas). Com [onlySubs] só entra o que vem dos canais
 * que a pessoa segue. Quem a pessoa segue vem antes; canais bloqueados nunca entram.
 */
fun buildSearchEntries(
    kind: SearchKind, platforms: Set<Platform>, onlySubs: Boolean, src: SearchSources, blocked: List<String> = emptyList(),
): List<SearchEntry> {
    fun first(c: Channel?) = c != null && c.key in src.followed
    val out: List<SearchEntry> = if (onlySubs) subscriptionEntries(kind, src, blocked) else {
        val yt = platforms.allows(Platform.YouTube)
        val tw = platforms.allows(Platform.Twitch)
        val kick = platforms.allows(Platform.Kick)
        val channels = interleave(
            listOf(
                if (yt) src.ytChannels.map { ChannelEntry(it, null) } else emptyList(),
                if (tw) src.twitchChannels.map { ChannelEntry(it.channel, it.live) } else emptyList(),
                if (kick) src.kickChannels.map { ChannelEntry(it.channel, it.live) } else emptyList(),
            ),
        ).filter { it.channel.key !in blocked }.sortedByDescending { first(it.channel) }
        val lives = interleave(
            listOf(
                if (tw) src.twitchLives else emptyList(),
                if (kick) src.kickChannels.mapNotNull { it.live } else emptyList(),
                if (yt) src.ytVideos.filter { it.isLive } else emptyList(),
            ),
        ).withoutBlocked(blocked).sortedByDescending { first(it.channel) }
        val videos = if (yt) src.ytVideos.filter { !it.isLive }.withoutBlocked(blocked) else emptyList()
        val playlists = if (yt) src.ytPlaylists else emptyList()
        when (kind) {
            SearchKind.Channels -> channels
            SearchKind.Lives -> lives.map(::MediaEntry)
            SearchKind.Videos -> videos.map(::MediaEntry)
            SearchKind.Playlists -> playlists.map(::PlaylistEntry)
            SearchKind.All ->
                channels.take(ALL_CHANNELS) + lives.take(ALL_LIVES).map(::MediaEntry) +
                    mixIn(videos.map(::MediaEntry), playlists.take(PLAYLIST_SLOTS.size).map(::PlaylistEntry), PLAYLIST_SLOTS)
        }
    }
    return out.distinctBy { it.key }
}

/** Só o que vem dos canais seguidos: os canais, depois as lives e os vídeos deles. */
private fun subscriptionEntries(kind: SearchKind, src: SearchSources, blocked: List<String>): List<SearchEntry> {
    val liveByChannel = src.mine.media.filter { it.isLive }.associateBy { it.channel?.key }
    val channels = src.mine.channels.filter { it.key !in blocked }.map { ChannelEntry(it, liveByChannel[it.key]) }
    val media = src.mine.media.withoutBlocked(blocked)
    return when (kind) {
        SearchKind.Channels -> channels
        SearchKind.Lives -> media.filter { it.isLive }.map(::MediaEntry)
        SearchKind.Videos -> media.filter { !it.isLive }.map(::MediaEntry)
        SearchKind.Playlists -> emptyList()
        SearchKind.All -> channels.take(3) + media.sortedByDescending { it.isLive }.map(::MediaEntry)
    }
}

/** Coloca cada item de [extras] na posição pedida de [base] (ou no fim, se a lista for mais curta). */
internal fun <T> mixIn(base: List<T>, extras: List<T>, positions: List<Int>): List<T> {
    val out = base.toMutableList()
    extras.forEachIndexed { i, item -> out.add(positions.getOrElse(i) { out.size }.coerceAtMost(out.size), item) }
    return out
}
