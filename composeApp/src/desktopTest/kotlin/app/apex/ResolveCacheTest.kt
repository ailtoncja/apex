package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Quality
import app.apex.model.Resolved
import app.apex.player.LoadState
import app.apex.player.PlaybackSession
import app.apex.player.ResolveCache
import app.apex.source.KickSource
import app.apex.source.TwitchSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Preparar o vídeo do YouTube antes de abrir (e guardar o que já ficou pronto) para abrir na hora. */
class ResolveCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun video(id: String, live: Boolean = false) =
        Media(Platform.YouTube, id, "Vídeo $id", Channel(Platform.YouTube, "UC$id", "Canal"), isLive = live, url = "https://www.youtube.com/watch?v=$id")

    private fun resolved(m: Media) = Resolved(
        media = m, description = null, likes = null, subscribers = null, uploadDate = null, chapters = emptyList(), subtitles = emptyList(),
        qualities = listOf(Quality("720p", 720, "https://cdn/${m.id}.mp4")), userAgent = null, isLive = m.isLive,
    )

    @Test
    fun segundo_pedido_usa_o_que_ja_estava_pronto() = runBlocking {
        val calls = AtomicInteger()
        val cache = ResolveCache(scope, { calls.incrementAndGet(); resolved(it) })
        val a = cache.get(video("a")).await()
        val b = cache.get(video("a")).await()
        assertEquals(a, b)
        assertEquals(1, calls.get(), "só preparou uma vez")
        cache.get(video("b")).await()
        assertEquals(2, calls.get(), "outro vídeo é outro preparo")
    }

    @Test
    fun abrir_enquanto_prepara_espera_o_mesmo_preparo_e_nao_comeca_outro() = runBlocking {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val cache = ResolveCache(scope, { m -> calls.incrementAndGet(); release.await(); resolved(m) })
        cache.prefetch(video("a")) // o mouse parou no vídeo
        delay(50)
        val opened = async { cache.get(video("a")).await() } // a pessoa clicou antes de terminar
        delay(50)
        assertTrue(!opened.isCompleted)
        release.complete(Unit)
        assertEquals("a", withTimeout(2_000) { opened.await() }.media.id)
        assertEquals(1, calls.get(), "o clique aproveitou o preparo que já estava andando")
    }

    @Test
    fun falha_nao_fica_guardada_e_tentar_de_novo_prepara_outra_vez() = runBlocking {
        val calls = AtomicInteger()
        val cache = ResolveCache(scope, { m -> if (calls.incrementAndGet() == 1) error("sem rede") else resolved(m) })
        assertFailsWith<IllegalStateException> { cache.get(video("a")).await() }
        assertEquals("a", cache.get(video("a")).await().media.id)
        assertEquals(2, calls.get())
    }

    @Test
    fun cancelar_quem_espera_nao_cancela_o_preparo() = runBlocking {
        val calls = AtomicInteger()
        val cache = ResolveCache(scope, { m -> calls.incrementAndGet(); delay(200); resolved(m) })
        val waiter = async { cache.get(video("a")).await() }
        delay(30)
        waiter.cancelAndJoin() // a pessoa abriu outro vídeo
        assertEquals("a", withTimeout(2_000) { cache.get(video("a")).await() }.media.id)
        assertEquals(1, calls.get(), "o preparo continuou e serve na próxima vez")
    }

    @Test
    fun preparo_vence_com_o_tempo_e_o_de_live_vence_antes() = runBlocking {
        val now = AtomicLong(1_000)
        val calls = AtomicInteger()
        val cache = ResolveCache(scope, { calls.incrementAndGet(); resolved(it) }, { now.get() })
        cache.get(video("v")).await(); cache.get(video("l", live = true)).await()
        now.addAndGet(60_000)
        cache.get(video("v")).await(); cache.get(video("l", live = true)).await()
        assertEquals(2, calls.get(), "um minuto depois ainda vale")
        now.addAndGet(60_000) // 2 min: a live (90 s) venceu, o vídeo gravado não
        cache.get(video("v")).await(); cache.get(video("l", live = true)).await()
        assertEquals(3, calls.get(), "só a live foi preparada de novo")
        now.addAndGet(ResolveCache.TTL_MS)
        cache.get(video("v")).await()
        assertEquals(4, calls.get())
    }

    @Test
    fun poucas_pre_cargas_ao_mesmo_tempo_e_o_clique_nunca_e_barrado() = runBlocking {
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val cache = ResolveCache(scope, { m -> started.incrementAndGet(); release.await(); resolved(m) })
        for (id in listOf("a", "b", "c", "d")) cache.prefetch(video(id)) // mouse passando por vários vídeos
        delay(100)
        assertEquals(ResolveCache.MAX_PREFETCH, started.get(), "as pré-cargas passando do limite são ignoradas")
        val click = async { cache.get(video("d")).await() } // mas clicar sempre prepara
        delay(100)
        assertEquals(ResolveCache.MAX_PREFETCH + 1, started.get())
        release.complete(Unit)
        assertEquals("d", withTimeout(2_000) { click.await() }.media.id)
        // terminou: dá para pré-carregar de novo
        delay(100)
        cache.prefetch(video("e"))
        delay(100)
        assertEquals(ResolveCache.MAX_PREFETCH + 2, started.get())
    }

    @Test
    fun limpar_esquece_tudo_e_o_cache_tem_tamanho_maximo() = runBlocking {
        val calls = AtomicInteger()
        val cache = ResolveCache(scope, { calls.incrementAndGet(); resolved(it) })
        repeat(ResolveCache.MAX_ENTRIES + 10) { cache.get(video("v$it")).await() }
        assertEquals(ResolveCache.MAX_ENTRIES, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        cache.get(video("v0")).await()
        assertEquals(ResolveCache.MAX_ENTRIES + 11, calls.get())
    }

    // ------------------------------------------------------------------ dentro do player

    private class CountingExtractor(val calls: AtomicInteger) : app.apex.source.Extractor {
        override val state = kotlinx.coroutines.flow.MutableStateFlow<app.apex.source.ExtractorState>(app.apex.source.ExtractorState.Ready)
        override suspend fun ensureReady() = app.apex.source.ExtractorState.Ready
        override suspend fun resolve(media: Media): Resolved {
            calls.incrementAndGet()
            delay(150)
            return Resolved(
                media, null, null, null, null, emptyList(), emptyList(),
                listOf(Quality("720p", 720, "https://cdn/${media.id}.mp4")), null,
            )
        }
        override suspend fun comments(media: Media, limit: Int, newest: Boolean) = emptyList<app.apex.model.Comment>()
        override suspend fun channelDetails(channelId: String): app.apex.model.ChannelDetails = error("não usado")
        override suspend fun updateEngine() = ""
    }

    @Test
    fun video_preparado_antes_abre_sem_preparar_de_novo_e_o_proximo_da_fila_tambem() = runBlocking {
        val calls = AtomicInteger()
        val data = UserData(FileStore(java.nio.file.Files.createTempDirectory("apex-rc").toFile()), scope)
        val session = PlaybackSession(scope, FakePlayer2(), CountingExtractor(calls), TwitchSource(), KickSource(), data)
        val a = video("a")
        session.prefetch(a) // o mouse parou em cima
        delay(400) // a pessoa leva um tempo até clicar
        session.open(a)
        withTimeout(2_000) { while (session.load.value !is LoadState.Ready) delay(10) }
        assertEquals(1, calls.get(), "abriu com o que já estava preparado")
        // abrir de novo (por exemplo trocar de página e voltar) também é instantâneo
        session.open(a)
        withTimeout(2_000) { while (session.load.value !is LoadState.Ready) delay(10) }
        assertEquals(1, calls.get())
        // vídeos da Twitch/Kick não passam pelo cache de preparo
        session.prefetch(Media(Platform.Twitch, "gaules", "x", isLive = true, url = "https://www.twitch.tv/gaules"))
        delay(100)
        assertEquals(1, calls.get())
    }
}
