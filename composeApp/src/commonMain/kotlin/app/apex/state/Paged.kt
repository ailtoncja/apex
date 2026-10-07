package app.apex.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Page<T>(val items: List<T>, val next: String?)

/** Lista carregada por páginas (rolagem infinita), com estado de carregamento e erro. */
class Paged<T>(
    private val scope: CoroutineScope,
    private val keyOf: (T) -> Any = { it as Any },
    private val fetch: suspend (token: String?) -> Page<T>,
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var token: String? = null
    private var exhausted = false
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        token = null
        exhausted = false
        _items.value = emptyList()
        _loaded.value = false
        load()
    }

    /** Há mais páginas para carregar (e a primeira já veio). */
    val hasMore: Boolean get() = _loaded.value && !exhausted

    fun loadIfNeeded() {
        if (!_loaded.value && !_loading.value) load()
    }

    fun loadMore() {
        if (_loading.value || exhausted || !_loaded.value) return
        load()
    }

    private fun load() {
        _loading.value = true
        _error.value = null
        job = scope.launch {
            try {
                val page = fetch(token)
                app.apex.util.ImagePrefetch.request(page.items)
                _items.update { (it + page.items).distinctBy(keyOf) }
                token = page.next
                exhausted = page.next == null
                _loaded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Falha ao carregar."
                _loaded.value = true
            } finally {
                _loading.value = false
            }
        }
    }
}

/** Um valor carregado uma vez (com recarga manual). */
class Loadable<T>(
    private val scope: CoroutineScope,
    private val initial: T,
    private val fetch: suspend () -> T,
) {
    private val _value = MutableStateFlow(initial)
    val value: StateFlow<T> = _value.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var job: Job? = null

    fun loadIfNeeded() {
        if (!_loaded.value && !_loading.value) reload()
    }

    fun reload() {
        job?.cancel()
        _loading.value = true
        _error.value = null
        job = scope.launch {
            try {
                _value.value = fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Falha ao carregar."
            } finally {
                _loaded.value = true
                _loading.value = false
            }
        }
    }
}
