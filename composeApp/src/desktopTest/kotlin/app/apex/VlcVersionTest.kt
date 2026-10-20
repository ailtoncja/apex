package app.apex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O aviso de VLC desatualizado: só abaixo do 3.0.21, e nunca por uma versão que não dá para ler. */
class VlcVersionTest {
    @Test
    fun le_os_numeros_da_versao_do_libvlc() {
        assertEquals(listOf(3, 0, 20), vlcVersionNumbers("3.0.20 Vetinari"))
        assertEquals(listOf(4, 0, 0), vlcVersionNumbers("4.0.0-dev Otto Chriek"))
        assertEquals(listOf(3, 0, 21, 2), vlcVersionNumbers("3.0.21.2"))
        assertNull(vlcVersionNumbers("Vetinari"))
        assertNull(vlcVersionNumbers(""))
    }

    @Test
    fun antes_do_3_0_21_e_desatualizado() {
        listOf("3.0.20 Vetinari", "3.0.18 Vetinari", "3.0.9.2 Vetinari", "2.2.8 Weatherwax", "3.0 Vetinari", "1").forEach { assertTrue(isVlcOutdated(it), it) }
        listOf("3.0.21 Vetinari", "3.0.21.1", "3.0.22", "3.1.0", "4.0.0-dev Otto Chriek", "10.0.0").forEach { assertFalse(isVlcOutdated(it), it) }
        // sem número, não avisa à toa
        assertFalse(isVlcOutdated("desconhecida"))
        assertFalse(isVlcOutdated(""))
    }
}
