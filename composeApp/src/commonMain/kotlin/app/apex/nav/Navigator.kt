package app.apex.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.apex.model.Channel
import app.apex.model.LiveCategory
import app.apex.model.Platform
import app.apex.source.SearchFilters

enum class LibraryTab(val label: String) {
    History("Histórico"), WatchLater("Assistir depois"), Liked("Curtidos"), Clips("Clipes"), Playlists("Playlists")
}

enum class LiveFilter(val label: String) { All("Tudo"), YouTube("YouTube"), Twitch("Twitch"), Kick("Kick") }

sealed interface Route {
    data object Home : Route
    data class Search(val query: String, val filters: SearchFilters = SearchFilters()) : Route
    data object Watch : Route
    data class ChannelPage(val channel: Channel, val tab: Int = 0) : Route
    data class Live(val filter: LiveFilter = LiveFilter.All) : Route
    data class Category(val platform: Platform, val category: LiveCategory) : Route
    data object Subscriptions : Route
    data class Library(val tab: LibraryTab = LibraryTab.History) : Route
    data class PlaylistPage(val id: String) : Route
    data class RemotePlaylist(val id: String, val title: String) : Route
    data object Settings : Route
}

class Navigator {
    val stack: SnapshotStateList<Route> = mutableStateListOf(Route.Home)

    val current: Route get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: Route) {
        if (route == current) return
        if (route is Route.Watch && current is Route.Watch) return
        stack += route
        if (stack.size > 60) stack.removeAt(0)
    }

    /** Troca só a tela de cima (mudar filtros da busca sem empilhar). */
    fun replaceTop(route: Route) {
        stack[stack.lastIndex] = route
    }

    /** Troca a pilha inteira (itens do menu lateral). */
    fun goRoot(route: Route) {
        stack.clear()
        stack += route
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
