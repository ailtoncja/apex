package app.apex.source

import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Resolved
import app.apex.util.Timing

/**
 * O yt-dlp com a conta do YouTube, tentando de novo quando o login mudou no meio do caminho. O login guardado no disco costuma estar vencido
 * quando o app abre (o navegador troca os cookies de tempos em tempos; o app relê os dele ao abrir e a cada 2 minutos). O primeiro vídeo aberto
 * logo depois sai com o login velho, e o YouTube responde ao yt-dlp sem os formatos ("Nenhum formato de vídeo disponível"). Pela via rápida o
 * vídeo até começa, mas só o primeiro minuto: depois dele, ou num pulo para mais adiante, trava e dá erro.
 *
 * Aqui, se o yt-dlp falhar, o login é renovado pelo navegador ([refresh]) e, se ficou diferente do que o yt-dlp usou, o pedido sai de novo, uma
 * vez. Login igual: o erro é de verdade (vídeo privado, sem internet…) e vai adiante.
 */
class FreshLoginExtractor(
    private val inner: Extractor,
    private val credential: () -> String?,
    private val refresh: suspend () -> Unit,
) : Extractor by inner {
    override suspend fun resolve(media: Media): Resolved {
        val used = credential()
        return try {
            inner.resolve(media)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (media.platform != Platform.YouTube || used == null) throw e
            runCatching { refresh() }
            if (credential() == used) throw e
            Timing.mark("yt-dlp falhou com o login antigo (${e.message?.take(80)}); de novo com o login renovado")
            inner.resolve(media)
        }
    }
}
