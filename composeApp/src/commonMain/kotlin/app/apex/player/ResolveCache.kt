package app.apex.player

import app.apex.model.Media
import app.apex.model.Resolved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * O preparo dos vídeos do YouTube (achar os endereços de vídeo e de áudio leva uns 4 segundos): guarda o que já ficou pronto e o que está
 * sendo preparado, para que abrir um vídeo já preparado seja instantâneo. Quem pede um vídeo que ainda está sendo preparado espera por ele
 * em vez de começar de novo; falhas nunca ficam guardadas (tentar de novo prepara outra vez).
 */
class ResolveCache(
    private val scope: CoroutineScope,
    private val resolve: suspend (Media) -> Resolved,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(val result: Deferred<Resolved>, val startedAt: Long, val ttlMs: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var prefetching = 0

    /** O preparo de [media]: o que já está pronto (ou em andamento) ou um novo. Cancelar quem espera não cancela o preparo. */
    fun get(media: Media): Deferred<Resolved> = synchronized(this) {
        fresh(media)?.result ?: start(media, prefetch = false).result
    }

    /** Prepara [media] em segundo plano, antes de alguém pedir. Poucos ao mesmo tempo: o que passar do limite é ignorado. */
    fun prefetch(media: Media) {
        synchronized(this) {
            if (fresh(media) != null || prefetching >= MAX_PREFETCH) return
            start(media, prefetch = true)
        }
    }

    /** Esquece tudo (por exemplo, quando a conta do YouTube muda: os endereços dependem do login). */
    fun clear() = synchronized(this) { entries.clear() }

    /** Esquece só este vídeo. */
    fun forget(media: Media) = synchronized(this) { entries.remove(media.key); Unit }

    /** Quantos vídeos estão guardados (ou sendo preparados). */
    val size: Int get() = synchronized(this) { entries.size }

    private fun fresh(media: Media): Entry? {
        val e = entries[media.key] ?: return null
        return if (clock() - e.startedAt < e.ttlMs) e else null.also { entries.remove(media.key) }
    }

    private fun start(media: Media, prefetch: Boolean): Entry {
        val key = media.key
        lateinit var entry: Entry
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                resolve(media)
            } catch (t: Throwable) {
                // Falha (ou cancelamento) não fica guardada: da próxima vez prepara de novo.
                synchronized(this@ResolveCache) { if (entries[key] === entry) entries.remove(key) }
                throw t
            } finally {
                if (prefetch) synchronized(this@ResolveCache) { prefetching-- }
            }
        }
        // Os endereços de uma live mudam logo; os de um vídeo gravado valem por horas.
        entry = Entry(job, clock(), if (media.isLive) LIVE_TTL_MS else TTL_MS)
        entries.remove(key)
        entries[key] = entry
        if (prefetch) prefetching++
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        job.start()
        return entry
    }

    companion object {
        const val TTL_MS = 3 * 3_600_000L
        const val LIVE_TTL_MS = 90_000L
        const val MAX_ENTRIES = 40
        const val MAX_PREFETCH = 2
    }
}
