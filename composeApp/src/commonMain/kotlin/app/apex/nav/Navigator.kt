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
    data class RemotePlaylist(val id: String, val title: String, val coverUrl: String? = null, val countText: String? = null) : Route
    data object Settings : Route

    /** Várias lives ao mesmo tempo (o Multi). */
    data object Multi : Route
}

class Navigator {
    val stack: SnapshotStateList<Route> = mutableStateListOf(Route.Home)

    val current: Route get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /** As telas de que a pessoa voltou, para o "avançar" (Alt+→ ou o botão do mouse); qualquer navegação nova as esquece. */
    private val forwardStack = mutableListOf<Route>()

    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    fun push(route: Route) {
        if (route == current) return
        if (route is Route.Watch && current is Route.Watch) return
        forwardStack.clear()
        stack += route
        if (stack.size > 60) stack.removeAt(0)
    }

    /** Troca só a tela de cima (mudar filtros da busca sem empilhar). */
    fun replaceTop(route: Route) {
        forwardStack.clear()
        stack[stack.lastIndex] = route
    }

    /** Troca a pilha inteira (itens do menu lateral). */
    fun goRoot(route: Route) {
        forwardStack.clear()
        stack.clear()
        stack += route
    }

    fun back() {
        if (stack.size > 1) {
            val left = stack.removeAt(stack.lastIndex)
            // A página do vídeo depende do que está tocando: não dá para "avançar" até ela depois.
            if (left !is Route.Watch) forwardStack += left
        }
    }

    fun forward() {
        val next = forwardStack.removeLastOrNull() ?: return
        stack += next
    }
}
