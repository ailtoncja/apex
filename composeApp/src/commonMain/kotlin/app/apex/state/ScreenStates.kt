package app.apex.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.apex.AppContainer
import app.apex.model.Channel
import app.apex.model.ChannelDetails
import app.apex.model.ClipSort
import app.apex.model.Comment
import app.apex.model.LiveCategory
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.source.RemotePlaylist
import app.apex.nav.LiveFilter
import app.apex.source.ChannelHit
import app.apex.source.ChannelTab
import app.apex.source.SearchDate
import app.apex.source.SearchFilters
import app.apex.source.SearchSort
import app.apex.util.ImagePrefetch
import app.apex.util.currentTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Guarda o estado de cada tela para que ele sobreviva à navegação (voltar não recarrega tudo). */
class ScreenStates(private val app: AppContainer) {
    val home = HomeState(app)
    val live = LiveState(app)
    val subs = SubscriptionsState(app)

    private val searches = LinkedHashMap<String, SearchState>()
    private val sharedSearches = LinkedHashMap<String, SharedSearch>()
    private val channels = LinkedHashMap<String, ChannelState>()
    private val categories = LinkedHashMap<String, CategoryState>()
    val watch = WatchState(app)

    fun search(query: String, filters: SearchFilters): SearchState {
        val key = "$query|$filters"
        return searches.getOrPut(key) {
            if (searches.size > 8) searches.remove(searches.keys.first())
            SearchState(app, query, filters, sharedSearch(query))
        }
    }

    /** O que a Twitch e a Kick devolveram para a consulta: vale para qualquer filtro do YouTube, então não é buscado de novo ao trocar de aba. */
    private fun sharedSearch(query: String): SharedSearch = sharedSearches.getOrPut(query) {
        if (sharedSearches.size > 8) sharedSearches.remove(sharedSearches.keys.first())
        SharedSearch(app, query)
    }

    fun channel(channel: Channel): ChannelState = channels.getOrPut(channel.key) {
        if (channels.size > 12) channels.remove(channels.keys.first())
        ChannelState(app, channel)
    }

    fun category(platform: Platform, category: LiveCategory): CategoryState =
        categories.getOrPut("${platform.name}:${category.id}") {
            if (categories.size > 10) categories.remove(categories.keys.first())
            CategoryState(app, platform, category)
        }
}

/** Idioma que se pode escolher dentro de uma categoria. [code] é o código ISO em minúsculas (a Twitch usa o mesmo em maiúsculas). */
class CategoryLanguage(val label: String, val code: String?)

val CATEGORY_LANGUAGES = listOf(
    CategoryLanguage("Todos os idiomas", null),
    CategoryLanguage("Português", "pt"), CategoryLanguage("Inglês", "en"), CategoryLanguage("Espanhol", "es"),
    CategoryLanguage("Francês", "fr"), CategoryLanguage("Alemão", "de"), CategoryLanguage("Italiano", "it"),
    CategoryLanguage("Russo", "ru"), CategoryLanguage("Japonês", "ja"), CategoryLanguage("Coreano", "ko"),
    CategoryLanguage("Turco", "tr"), CategoryLanguage("Árabe", "ar"), CategoryLanguage("Polonês", "pl"),
)

enum class ViewerSort(val label: String) { Most("Mais espectadores"), Least("Menos espectadores") }

enum class ViewerRange(val label: String, val min: Long, val max: Long) {
    Any("Qualquer público", 0, Long.MAX_VALUE),
    Tiny("Até 100", 0, 100),
    Small("100 a 1 mil", 100, 1_000),
    Medium("1 mil a 10 mil", 1_000, 10_000),
    Big("Mais de 10 mil", 10_000, Long.MAX_VALUE),
}

/** As duas abas de uma categoria do YouTube (a Twitch e a Kick só têm lives). */
enum class CategoryTab(val label: String) { Live("Ao vivo"), Videos("Vídeos") }

/**
 * Lives de uma categoria com filtro de idioma, ordem e faixa de espectadores e busca pelo nome.
 * No YouTube a categoria é uma busca pronta (ver [youtubeCategoryQueries]): a aba "Ao vivo" lista as lives e a aba "Vídeos" os vídeos,
 * com ordem e data da pesquisa do YouTube.
 */
class CategoryState(private val app: AppContainer, val platform: Platform, val category: LiveCategory) {
    var language by mutableStateOf(CATEGORY_LANGUAGES.first())
        private set
    var sort by mutableStateOf(ViewerSort.Most)
        private set
    var range by mutableStateOf(ViewerRange.Any)
    var query by mutableStateOf("")

    var tab by mutableStateOf(CategoryTab.Live)
        private set
    var videoSort by mutableStateOf(SearchSort.Relevance)
        private set
    var videoDate by mutableStateOf(SearchDate.Any)
        private set

    val streams = Paged<Media>(app.scope, { it.key }) { token ->
        when (platform) {
            Platform.Twitch -> Page(app.twitch.streamsByCategory(category.name, 100, language.code?.uppercase(), sort == ViewerSort.Least), null)
            Platform.Kick -> app.kick.lives(language.code, category.id, sort == ViewerSort.Least, token).let { Page(it.items, it.next) }
            Platform.YouTube -> app.youtube.search(youtubeCategoryQueries(category).first, SearchFilters(liveOnly = true), token)
                .let { Page(it.videos, it.continuation) }
        }
    }

    /** Só no YouTube: os vídeos do assunto. */
    val videos = Paged<Media>(app.scope, { it.key }) { token ->
        app.youtube.search(youtubeCategoryQueries(category).second, SearchFilters(sort = videoSort, date = videoDate), token)
            .let { Page(it.videos, it.continuation) }
    }

    /** A lista que está na tela agora. */
    val current: Paged<Media> get() = if (platform == Platform.YouTube && tab == CategoryTab.Videos) videos else streams

    /** O idioma e a ordem por público só existem na Twitch e na Kick; o YouTube não filtra a busca por idioma. */
    val hasLanguage: Boolean get() = platform != Platform.YouTube

    fun selectTab(value: CategoryTab) {
        if (value == tab) return
        tab = value
        current.loadIfNeeded()
    }

    fun selectVideoSort(value: SearchSort) {
        if (value == videoSort) return
        videoSort = value
        videos.refresh()
    }

    fun selectVideoDate(value: SearchDate) {
        if (value == videoDate) return
        videoDate = value
        videos.refresh()
    }

    fun selectLanguage(value: CategoryLanguage) {
        if (value == language) return
        language = value
        streams.refresh()
    }

    fun selectSort(value: ViewerSort) {
        if (value == sort) return
        sort = value
        streams.refresh()
    }

    /** O que aparece: a faixa de espectadores e as palavras da busca (no título, no canal e na categoria; sem ligar para acentos). */
    fun visible(items: List<Media>): List<Media> {
        val words = searchWords(query)
        // A faixa de público vale para lives; na aba de vídeos o número é de visualizações, então não filtra.
        val byAudience = current === streams
        return items.filter { m ->
            val viewers = m.viewCount ?: 0
            (!byAudience || (viewers >= range.min && (viewers < range.max || range.max == Long.MAX_VALUE))) &&
                matchesWords(words, m.title, m.channel?.name, m.category)
        }
    }
}

fun List<Media>.withoutBlocked(blocked: List<String>): List<Media> =
    if (blocked.isEmpty()) this else filterNot { m -> m.channel?.key in blocked }

// ---------------------------------------------------------------------------------------------

class HomeTopic(val label: String, val query: String?)

private val DISCOVER_QUERIES = listOf(
    "música clipe oficial", "jogos gameplay", "futebol melhores momentos", "humor comédia",
    "tecnologia review", "trailer filme", "notícias hoje", "podcast corte", "receita culinária",
)

class HomeState(private val app: AppContainer) {
    val topics = listOf(
        HomeTopic("Tudo", null),
        HomeTopic("Jogos", "jogos gameplay"),
        HomeTopic("Música", "música clipe oficial"),
        HomeTopic("Notícias", "notícias hoje"),
        HomeTopic("Esportes", "esportes melhores momentos"),
        HomeTopic("Futebol", "futebol gols"),
        HomeTopic("Tecnologia", "tecnologia review"),
        HomeTopic("Filmes", "trailer filme"),
        HomeTopic("Culinária", "receitas culinária"),
        HomeTopic("Humor", "humor comédia"),
        HomeTopic("Podcasts", "podcast episódio completo"),
        HomeTopic("Aprender", "aula tutorial"),
    )

    var selected by mutableIntStateOf(0)

    private val _followedLive = MutableStateFlow<List<Media>>(emptyList())
    val followedLive: StateFlow<List<Media>> = _followedLive.asStateFlow()

    private val _topLive = MutableStateFlow<List<Media>>(emptyList())
    val topLive: StateFlow<List<Media>> = _topLive.asStateFlow()

    private val _liveLoading = MutableStateFlow(false)
    val liveLoading: StateFlow<Boolean> = _liveLoading.asStateFlow()

    val recommendations = Paged<Media>(app.scope, { it.key }) { token ->
        val page = app.youtube.accountHome(token)
        Page(page.items, page.continuation)
    }

    val discover = Paged<Media>(app.scope, { it.key }) { token ->
        discoverPage(token)
    }

    /** "Em alta": os mais vistos da semana em vários assuntos (sem olhar o que a pessoa assistiu). */
    val trending = Paged<Media>(app.scope, { it.key }) { trendingPage() }

    private val topicLists = HashMap<String, Paged<Media>>()
    private val topicLiveLists = HashMap<String, Loadable<List<Media>>>()

    /** As lives mais vistas de uma plataforma (a mesma lista da tela Ao vivo); o YouTube não tem lista de "só lives" aqui. */
    fun topLives(platform: Platform): Paged<Media>? = when (platform) {
        Platform.Twitch -> app.screens.live.twitch
        Platform.Kick -> app.screens.live.kick
        Platform.YouTube -> null
    }

    /** Lives da Twitch e da Kick sobre o assunto, para quando o filtro de plataforma deixa de fora o YouTube. */
    fun topicLives(topic: HomeTopic): Loadable<List<Media>> = topicLiveLists.getOrPut(topic.label) {
        Loadable(app.scope, emptyList()) {
            coroutineScope {
                val t = async { runCatching { app.twitch.searchStreams(topic.label, 24) }.getOrDefault(emptyList()) }
                val k = async { runCatching { app.kick.search(topic.label).mapNotNull { it.live } }.getOrDefault(emptyList()) }
                interleave(listOf(t.await(), k.await()))
            }
        }
    }

    fun topicList(topic: HomeTopic): Paged<Media> = topicLists.getOrPut(topic.label) {
        Paged(app.scope, { it.key }) { token ->
            val page = app.youtube.search(topic.query.orEmpty(), SearchFilters(), token)
            Page(page.videos, page.continuation)
        }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        refreshLive()
        if (app.tube.loggedIn) recommendations.loadIfNeeded() else discover.loadIfNeeded()
        app.screens.subs.refreshFeedIfStale()
    }

    fun refresh() {
        refreshLive()
        if (app.tube.loggedIn) recommendations.refresh() else discover.refresh()
        if (trending.loaded.value) trending.refresh()
        app.screens.subs.refreshFeed()
        topicLists.values.forEach { it.refresh() }
        topicLiveLists.values.forEach { it.reload() }
        listOf(Platform.Twitch, Platform.Kick).mapNotNull { topLives(it) }.filter { it.loaded.value }.forEach { it.refresh() }
    }

    fun refreshLive() {
        _liveLoading.value = true
        app.scope.launch {
            try {
                val followed = loadFollowedLive(app)
                _followedLive.value = followed
                val settings = app.data.settings.value
                val top = coroutineScope {
                    val t = async { runCatching { app.twitch.topStreams(14, settings.twitchLanguage) }.getOrDefault(emptyList()) }
                    val k = async { runCatching { app.kick.liveStreams(settings.kickLanguage, pages = 1).take(14) }.getOrDefault(emptyList()) }
                    val y = async {
                        runCatching { app.youtube.search("ao vivo", SearchFilters(liveOnly = true)).videos.take(14) }.getOrDefault(emptyList())
                    }
                    interleave(listOf(t.await(), k.await(), y.await()))
                }
                _topLive.value = top.filter { t -> followed.none { it.key == t.key } }
            } finally {
                _liveLoading.value = false
            }
        }
    }

    private suspend fun discoverPage(token: String?): Page<Media> {
        if (token == null || token == "related") {
            val seeds = app.data.history.value.map { it.media }.filter { it.platform == Platform.YouTube && !it.isLive && !it.isClip }.take(3)
            if (seeds.isNotEmpty()) {
                val watched = app.data.history.value.map { it.media.key }.toSet()
                val items = coroutineScope { seeds.map { s -> async { runCatching { app.youtube.related(s.id) }.getOrDefault(emptyList()) } }.awaitAll() }
                val merged = interleave(items).filter { it.key !in watched }
                if (merged.isNotEmpty()) return Page(merged, null)
            }
        }
        return trendingPage()
    }

    private suspend fun trendingPage(): Page<Media> {
        val lists = coroutineScope {
            DISCOVER_QUERIES.map { q ->
                async {
                    runCatching {
                        app.youtube.search(q, SearchFilters(sort = SearchSort.Views, date = SearchDate.Week)).videos.take(10)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        return Page(interleave(lists), null)
    }
}

fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = mutableListOf<T>()
    val max = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out += it }
    return out
}

/** Lives dos canais que o usuário segue nas três plataformas. */
suspend fun loadFollowedLive(app: AppContainer): List<Media> = coroutineScope {
    val subs = app.data.subscriptions.value
    val twitch = async {
        runCatching {
            val logins = subs.filter { it.platform == Platform.Twitch }.map { it.id }
            app.twitch.channels(logins).mapNotNull { it.live }
        }.getOrDefault(emptyList())
    }
    val kick = async {
        runCatching {
            val slugs = subs.filter { it.platform == Platform.Kick }.map { it.id }
            app.kick.channels(slugs).mapNotNull { it.live }
        }.getOrDefault(emptyList())
    }
    val youtube = async {
        runCatching {
            subs.filter { it.platform == Platform.YouTube }.take(40).map { ch ->
                async { runCatching { app.youtube.channelLive(ch.id)?.let { m -> m.copy(channel = m.channel ?: ch) } }.getOrNull() }
            }.awaitAll().filterNotNull()
        }.getOrDefault(emptyList())
    }
    (twitch.await() + kick.await() + youtube.await()).sortedByDescending { it.viewCount ?: 0 }
}

// ---------------------------------------------------------------------------------------------

class LiveState(private val app: AppContainer) {
    /** Plataformas escolhidas (vazio = todas); dá para ligar duas ao mesmo tempo. */
    var platforms by mutableStateOf<Set<Platform>>(emptySet())

    private val _followed = MutableStateFlow<List<Media>>(emptyList())
    val followed: StateFlow<List<Media>> = _followed.asStateFlow()

    val twitchCategories = Loadable(app.scope, emptyList<LiveCategory>()) { app.twitch.categories(24) }
    val kickCategories = Loadable(app.scope, emptyList<LiveCategory>()) { app.kick.categories(24) }

    val all = Paged<Media>(app.scope, { it.key }) {
        val s = app.data.settings.value
        coroutineScope {
            val t = async { runCatching { app.twitch.topStreams(40, s.twitchLanguage) }.getOrDefault(emptyList()) }
            val k = async { runCatching { app.kick.liveStreams(s.kickLanguage, pages = 2) }.getOrDefault(emptyList()) }
            val y = async { runCatching { app.youtube.search("ao vivo", SearchFilters(liveOnly = true)).videos }.getOrDefault(emptyList()) }
            Page(interleave(listOf(t.await(), k.await(), y.await())), null)
        }
    }

    val twitch = Paged<Media>(app.scope, { it.key }) {
        Page(app.twitch.topStreams(60, app.data.settings.value.twitchLanguage), null)
    }

    val kick = Paged<Media>(app.scope, { it.key }) {
        Page(app.kick.liveStreams(app.data.settings.value.kickLanguage, pages = 4), null)
    }

    val youtube = Paged<Media>(app.scope, { it.key }) { token ->
        val page = app.youtube.search("ao vivo", SearchFilters(liveOnly = true), token)
        Page(page.videos, page.continuation)
    }

    /** As listas que formam a tela para as plataformas escolhidas (a mistura das três, ou uma lista por plataforma). */
    fun lists(): List<Paged<Media>> =
        if (platforms.isEmpty()) listOf(all)
        else platforms.map { p -> when (p) { Platform.YouTube -> youtube; Platform.Twitch -> twitch; Platform.Kick -> kick } }

    /** O que vem escolhido da rota (Ao vivo › Twitch…) vira o conjunto de plataformas. */
    fun select(filter: LiveFilter) {
        platforms = when (filter) {
            LiveFilter.All -> emptySet()
            LiveFilter.YouTube -> setOf(Platform.YouTube)
            LiveFilter.Twitch -> setOf(Platform.Twitch)
            LiveFilter.Kick -> setOf(Platform.Kick)
        }
    }

    private var started = false

    fun start() {
        lists().forEach { it.loadIfNeeded() }
        if (platforms.allows(Platform.Twitch)) twitchCategories.loadIfNeeded()
        if (platforms.allows(Platform.Kick)) kickCategories.loadIfNeeded()
        if (!started) {
            started = true
            app.scope.launch { _followed.value = loadFollowedLive(app) }
        }
    }

    fun refresh() {
        app.scope.launch { _followed.value = loadFollowedLive(app) }
        lists().forEach { it.refresh() }
        twitchCategories.reload()
        kickCategories.reload()
    }
}

// ---------------------------------------------------------------------------------------------

class SubscriptionsState(private val app: AppContainer) {
    private val _feedLoading = MutableStateFlow(false)
    val feedLoading: StateFlow<Boolean> = _feedLoading.asStateFlow()

    private val _liveChannels = MutableStateFlow<Set<String>>(emptySet())
    val liveChannels: StateFlow<Set<String>> = _liveChannels.asStateFlow()

    private val _liveNow = MutableStateFlow<List<Media>>(emptyList())
    val liveNow: StateFlow<List<Media>> = _liveNow.asStateFlow()

    fun refreshFeedIfStale() {
        val cache = app.data.feed.value
        val stale = currentTimeMillis() - cache.updatedAt > 20 * 60_000
        if (stale && app.data.subscriptions.value.any { it.platform == Platform.YouTube }) refreshFeed()
        refreshLive()
    }

    fun refreshFeed() {
        val channels = app.data.subscriptions.value.filter { it.platform == Platform.YouTube }
        if (channels.isEmpty() || _feedLoading.value) return
        _feedLoading.value = true
        app.scope.launch {
            try {
                val items = app.youtube.feed(channels)
                ImagePrefetch.request(items)
                if (items.isNotEmpty()) app.data.saveFeed(items)
            } finally {
                _feedLoading.value = false
            }
        }
    }

    fun refreshLive() {
        app.scope.launch {
            val live = loadFollowedLive(app)
            ImagePrefetch.request(live)
            _liveNow.value = live
            _liveChannels.value = live.mapNotNull { it.channel?.key }.toSet()
        }
    }
}

// ---------------------------------------------------------------------------------------------

/** As buscas da Twitch e da Kick por uma consulta (não dependem dos filtros do YouTube). */
class SharedSearch(app: AppContainer, query: String) {
    val twitchLives = Loadable(app.scope, emptyList<Media>()) { app.twitch.searchStreams(query, 24) }
    val twitchChannels = Loadable(app.scope, emptyList<ChannelHit>()) { app.twitch.searchChannels(query, 10) }
    val kickChannels = Loadable(app.scope, emptyList<ChannelHit>()) { app.kick.search(query) }

    fun start() {
        twitchLives.loadIfNeeded()
        twitchChannels.loadIfNeeded()
        kickChannels.loadIfNeeded()
    }
}

class SearchState(private val app: AppContainer, val query: String, val filters: SearchFilters, private val shared: SharedSearch = SharedSearch(app, query)) {
    /** As plataformas ligadas (vazio = todas); dá para ligar mais de uma, e "Inscrições" combina com elas. */
    var platforms by mutableStateOf<Set<Platform>>(emptySet())

    /** Só o que vem dos canais que a pessoa segue. */
    var onlySubs by mutableStateOf(false)

    private val _ytChannels = MutableStateFlow<List<Channel>>(emptyList())
    val ytChannels: StateFlow<List<Channel>> = _ytChannels.asStateFlow()

    private val _ytPlaylists = MutableStateFlow<List<RemotePlaylist>>(emptyList())
    val ytPlaylists: StateFlow<List<RemotePlaylist>> = _ytPlaylists.asStateFlow()

    val youtube = Paged<Media>(app.scope, { it.key }) { token ->
        val page = app.youtube.search(query, filters, token)
        if (token == null) {
            _ytChannels.value = page.channels
            _ytPlaylists.value = page.playlists
        }
        Page(page.videos, page.continuation)
    }

    val twitchLives get() = shared.twitchLives
    val twitchChannels get() = shared.twitchChannels
    val kickChannels get() = shared.kickChannels

    fun start() {
        youtube.loadIfNeeded()
        shared.start()
    }
}

// ---------------------------------------------------------------------------------------------

class ChannelState(private val app: AppContainer, val seed: Channel) {
    val details = Loadable<ChannelDetails?>(app.scope, null) {
        when (seed.platform) {
            Platform.YouTube -> app.extractor.channelDetails(seed.id)
            Platform.Twitch -> app.twitch.channels(listOf(seed.id)).firstOrNull()?.let { ChannelDetails(it.channel) }
            Platform.Kick -> app.kick.channel(seed.id)?.let { ChannelDetails(it.hit.channel) }
        }
    }

    val live = Loadable<Media?>(app.scope, null) {
        when (seed.platform) {
            Platform.YouTube -> app.youtube.channelLive(seed.id)
            Platform.Twitch -> app.twitch.channels(listOf(seed.id)).firstOrNull()?.live
            Platform.Kick -> app.kick.channel(seed.id)?.hit?.live
        }
    }

    val videos = Paged<Media>(app.scope, { it.key }) { token ->
        if (seed.platform != Platform.YouTube) Page(emptyList(), null)
        else app.youtube.channelVideos(seed.id, ChannelTab.Videos, token).let { Page(it.items, it.continuation) }
    }

    val pastLives = Paged<Media>(app.scope, { it.key }) { token ->
        if (seed.platform != Platform.YouTube) Page(emptyList(), null)
        else app.youtube.channelVideos(seed.id, ChannelTab.Lives, token).let { Page(it.items, it.continuation) }
    }

    /** VODs (transmissões passadas) da Twitch e da Kick; só carrega quando a pessoa abre a aba. */
    val vods = Paged<Media>(app.scope, { it.key }) {
        when (seed.platform) {
            Platform.Twitch -> Page(app.twitch.vods(seed.id), null)
            Platform.Kick -> Page(app.kick.vods(seed), null)
            Platform.YouTube -> Page(emptyList(), null)
        }
    }

    /** Playlists do canal (só YouTube). */
    val playlists = Paged<RemotePlaylist>(app.scope, { it.id }) { token ->
        if (seed.platform != Platform.YouTube) Page(emptyList(), null)
        else app.youtube.channelPlaylists(seed.id, token).let { Page(it.items, it.continuation) }
    }

    /** Como os clipes ficam ordenados na aba "Clipes". */
    val clipSort = MutableStateFlow(ClipSort.Popular)

    /** Clipes da Twitch e da Kick. (No YouTube não existe lista de clipes por canal.) */
    val clips = Paged<Media>(app.scope, { it.key }) { token ->
        when (seed.platform) {
            Platform.Twitch -> Page(app.twitch.clips(seed.id, clipSort.value), null)
            Platform.Kick -> app.kick.clips(seed, clipSort.value, token).let { Page(it.items, it.next) }
            Platform.YouTube -> Page(emptyList(), null)
        }
    }

    fun setClipSort(sort: ClipSort) {
        if (clipSort.value == sort) return
        clipSort.value = sort
        clips.refresh()
    }

    fun start() {
        details.loadIfNeeded()
        live.loadIfNeeded()
        videos.loadIfNeeded()
    }
}

// ---------------------------------------------------------------------------------------------

/** Dados extras da página de vídeo: relacionados e comentários do vídeo que está tocando. */
class WatchState(private val app: AppContainer) {
    private val _related = MutableStateFlow<List<Media>>(emptyList())
    val related: StateFlow<List<Media>> = _related.asStateFlow()

    private val _relatedLoading = MutableStateFlow(false)
    val relatedLoading: StateFlow<Boolean> = _relatedLoading.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()

    private val _commentsError = MutableStateFlow<String?>(null)
    val commentsError: StateFlow<String?> = _commentsError.asStateFlow()

    var newestFirst by mutableStateOf(false)
    private var forKey: String? = null

    fun load(media: Media) {
        if (forKey == media.key) return
        forKey = media.key
        _related.value = emptyList()
        _comments.value = emptyList()
        _commentsError.value = null
        _relatedLoading.value = true
        app.scope.launch {
            try {
                val list = when (media.platform) {
                    Platform.YouTube -> app.youtube.related(media.videoId)
                    // Num VOD ou clipe, "a seguir" são os outros do mesmo canal; numa live, outras lives.
                    Platform.Twitch -> when {
                        media.isClip -> media.channel?.let { app.twitch.clips(it.id, ClipSort.Popular, 30) }.orEmpty()
                        media.isVod -> media.channel?.let { app.twitch.vods(it.id, 30) }.orEmpty()
                        else -> app.twitch.topStreams(20, app.data.settings.value.twitchLanguage)
                    }.filter { it.id != media.id }
                    Platform.Kick -> when {
                        media.isClip -> media.channel?.let { app.kick.clips(it).items }.orEmpty()
                        media.isVod -> media.channel?.let { app.kick.vods(it) }.orEmpty()
                        else -> app.kick.liveStreams(app.data.settings.value.kickLanguage, pages = 1)
                    }.filter { it.id != media.id }
                }
                if (forKey == media.key) {
                    _related.value = list.withoutBlocked(app.data.settings.value.blockedChannels)
                    app.session.setUpNext(_related.value)
                }
            } catch (_: Exception) {
            } finally {
                _relatedLoading.value = false
            }
        }
        loadComments(media)
    }

    fun loadComments(media: Media) {
        if (media.platform != Platform.YouTube || media.isLive) return
        _commentsLoading.value = true
        _commentsError.value = null
        app.scope.launch {
            try {
                val list = app.extractor.comments(media, 40, newestFirst)
                if (forKey == media.key) _comments.value = list
            } catch (e: Exception) {
                if (forKey == media.key) _commentsError.value = e.message
            } finally {
                _commentsLoading.value = false
            }
        }
    }
}
