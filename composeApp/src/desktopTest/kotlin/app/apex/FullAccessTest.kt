package app.apex

import app.apex.player.FullAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A ponte continua o arquivo da abertura rápida pelo endereço completo do yt-dlp: tem de ser o MESMO arquivo. No vídeo real "Building the Rarest
 * PC" o yt-dlp oferece dois áudios itag 140 com o mesmo tamanho, o normal e o de volume nivelado (`xtags=drc=1`), e o primeiro da lista era o
 * nivelado: depois do primeiro minuto, ou de um pulo, o VLC recebia bytes de outro arquivo, o pulo no áudio falhava e o som sumia.
 */
class FullAccessTest {
    private fun url(host: String, itag: Int = 140, clen: Long = 211_854_566, lmt: Long? = 1_700_000_000_000_001, xtags: String? = null) =
        "https://$host.googlevideo.com/videoplayback?expire=1&itag=$itag" + (lmt?.let { "&lmt=$it" } ?: "") + (xtags?.let { "&xtags=$it" } ?: "") +
            "&clen=$clen&mime=audio%2Fmp4"

    @Test
    fun a_versao_de_volume_nivelado_e_as_dublagens_nao_sao_o_mesmo_arquivo() {
        val fast = url("rapida")
        val drc = url("drc", lmt = 1_700_000_000_000_777, xtags = "drc%3D1")
        val dub = url("dub", lmt = 1_700_000_000_000_888, xtags = "acont%3Ddubbed%3Alang%3Des")
        val normal = url("normal")
        assertEquals(normal, FullAccess(listOf(drc, dub, normal), null).match(fast), "o normal, mesmo vindo depois")
        assertNull(FullAccess(listOf(drc, dub), null).match(fast), "sem o normal, não há par (melhor do que continuar por outro arquivo)")
    }

    @Test
    fun uma_dublagem_aberta_pela_via_rapida_continua_pela_mesma_dublagem() {
        val fast = url("rapida", lmt = 5, xtags = "acont%3Ddubbed%3Alang%3Des")
        val other = url("pt", lmt = 6, xtags = "acont%3Ddubbed%3Alang%3Dpt")
        val same = url("es", lmt = 5, xtags = "acont%3Ddubbed%3Alang%3Des")
        assertEquals(same, FullAccess(listOf(other, same), null).match(fast))
    }

    @Test
    fun a_mesma_data_de_arquivo_desempata_e_sem_data_vale_a_versao() {
        val fast = url("rapida", lmt = 10)
        val older = url("velho", lmt = 9)
        val sameDate = url("certo", lmt = 10)
        assertEquals(sameDate, FullAccess(listOf(older, sameDate), null).match(fast))
        assertEquals(older, FullAccess(listOf(older), null).match(fast), "sem outro candidato, formato, tamanho e versão iguais bastam")
        assertEquals(url("semdata", lmt = null), FullAccess(listOf(url("semdata", lmt = null)), null).match(url("rapida", lmt = null)))
        assertNull(FullAccess(listOf(url("outro", clen = 1)), null).match(fast), "outro tamanho")
        assertNull(FullAccess(listOf(url("outro", itag = 251)), null).match(fast), "outro formato")
    }
}
