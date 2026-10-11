package app.apex

import app.apex.player.SoftAudio
import kotlin.test.Test
import kotlin.test.assertEquals

/** A saída de som do app: a curva de volume é a do VLC (cúbica) e o ganho satura em 16 bits sem estourar. */
class SoftAudioTest {
    @Test
    fun a_curva_de_volume_e_a_mesma_do_vlc_e_mudo_zera() {
        assertEquals(1f, SoftAudio.gainFor(100, false))
        assertEquals(8f, SoftAudio.gainFor(200, false))
        assertEquals(0.125f, SoftAudio.gainFor(50, false))
        assertEquals(0f, SoftAudio.gainFor(0, false))
        assertEquals(0f, SoftAudio.gainFor(150, true), "mudo zera em qualquer volume")
        assertEquals(8f, SoftAudio.gainFor(999, false), "nunca passa de 200%")
    }

    @Test
    fun o_ganho_multiplica_e_satura_sem_virar_do_avesso() {
        val block = shortArrayOf(1000, -1000, 20000, -20000, 32767, -32768)
        fun decoded(bytes: ByteArray) = (bytes.indices step 2).map { (bytes[it].toInt() and 0xFF) or (bytes[it + 1].toInt() shl 8) }.map { it.toShort().toInt() }
        assertEquals(block.map { it.toInt() }, decoded(SoftAudio.withGain(block, 1f)), "ganho 1 não mexe")
        assertEquals(listOf(2000, -2000, 32767, -32768, 32767, -32768), decoded(SoftAudio.withGain(block, 2f)), "satura nos limites")
        assertEquals(listOf(125, -125, 2500, -2500, 4096, -4096), decoded(SoftAudio.withGain(block, 0.125f)))
        assertEquals(List(6) { 0 }, decoded(SoftAudio.withGain(block, 0f)), "mudo")
    }
}
