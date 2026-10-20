package app.apex

import app.apex.ui.components.safeImageUrl
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * Trava do Coil: só carrega endereços que passam em [safeImageUrl] (`https`, `http` e `data:image`). Um endereço de miniatura ou avatar vindo
 * de fora não pode fazer o app ler um arquivo do PC (`C:\…`, `file:`), de dentro de um pacote (`jar:`) ou de uma pasta de rede (`\\servidor\…`,
 * em que o Windows se identifica para o servidor). Vale mesmo para quem chamar o Coil sem passar pelo `RemoteImage`.
 */
internal object OnlyWebImages : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val allowed = when (data) {
            is String -> safeImageUrl(data) == data.trim()
            is coil3.Uri -> data.toString().let { safeImageUrl(it) == it.trim() }
            is java.io.File, is okio.Path, is java.net.URI, is java.net.URL, is java.nio.file.Path -> false
            else -> true
        }
        if (!allowed) return ErrorResult(null, chain.request, IllegalArgumentException("endereço de imagem recusado"))
        return chain.proceed()
    }
}
