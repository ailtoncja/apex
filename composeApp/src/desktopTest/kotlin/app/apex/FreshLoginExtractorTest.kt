package app.apex

import app.apex.model.Media
import app.apex.model.Platform
import app.apex.model.Resolved
import app.apex.source.ExtractionException
import app.apex.source.FreshLoginExtractor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * O yt-dlp com o login guardado vencido (o primeiro vídeo logo depois de abrir o app) responde "Nenhum formato de vídeo disponível": o vídeo
 * tocava só o primeiro minuto e travava num pulo para mais adiante. Com o login renovado pelo navegador, ele tenta de novo uma vez.
 */
class FreshLoginExtractorTest {
    private val media = Media(Platform.YouTube, "vid1", "Vídeo", url = "https://www.youtube.com/watch?v=vid1")

    /** Falha enquanto o login for o velho. */
    private class Picky(val login: () -> String?) : app.apex.source.Extractor by FakeExtractor2() {
        val used = mutableListOf<String?>()
        override suspend fun resolve(media: Media): Resolved {
            used += login()
            if (login() == "velho") throw ExtractionException("Nenhum formato de vídeo disponível.")
            return Resolved(media, null, null, null, null, emptyList(), emptyList(), emptyList(), null)
        }
    }

    @Test
    fun login_renovado_tenta_de_novo_uma_vez() = runBlocking {
        var login: String? = "velho"
        val inner = Picky { login }
        val ex = FreshLoginExtractor(inner, { login }, { login = "novo" })
        assertEquals("vid1", ex.resolve(media).media.id)
        assertEquals(listOf<String?>("velho", "novo"), inner.used)
    }

    @Test
    fun o_app_ja_tinha_renovado_enquanto_o_yt_dlp_rodava_tambem_tenta_de_novo() = runBlocking {
        var login: String? = "velho"
        val inner = object : app.apex.source.Extractor by FakeExtractor2() {
            var calls = 0
            override suspend fun resolve(media: Media): Resolved {
                calls++
                val usado = login
                login = "novo" // a renovação de 2 em 2 minutos chegou no meio do caminho
                if (usado == "velho") throw ExtractionException("Nenhum formato de vídeo disponível.")
                return Resolved(media, null, null, null, null, emptyList(), emptyList(), emptyList(), null)
            }
        }
        val ex = FreshLoginExtractor(inner, { login }, { /* nada mudou agora: já tinha mudado */ })
        ex.resolve(media)
        assertEquals(2, inner.calls)
    }

    @Test
    fun login_igual_o_erro_e_de_verdade_e_vai_adiante() = runBlocking {
        val inner = Picky { "velho" }
        val ex = FreshLoginExtractor(inner, { "velho" }, {})
        assertFailsWith<ExtractionException> { ex.resolve(media) }
        assertEquals(1, inner.used.size, "não repete à toa")

        val semConta = FreshLoginExtractor(Picky { "velho" }, { null }, { error("sem conta não renova") })
        assertFailsWith<ExtractionException> { semConta.resolve(media) }
        Unit
    }
}
