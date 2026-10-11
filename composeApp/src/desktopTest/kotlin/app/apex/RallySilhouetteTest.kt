package app.apex

import app.apex.ui.shell.loadRallySilhouette
import app.apex.ui.shell.silhouetteMask
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A imagem da silhueta que a pessoa pode pôr no lugar do desenho vetorial: vira máscara, e sem arquivo não há imagem. */
class RallySilhouetteTest {
    private fun alpha(img: BufferedImage, x: Int) = img.getRGB(x, 0) ushr 24
    private fun rgb(img: BufferedImage, x: Int) = img.getRGB(x, 0) and 0xFFFFFF

    @Test
    fun sem_arquivo_nao_ha_imagem_e_vale_o_desenho_vetorial() {
        assertNull(loadRallySilhouette())
    }

    @Test
    fun branco_sobre_preto_vira_mascara_pelo_brilho() {
        val src = BufferedImage(4, 1, BufferedImage.TYPE_INT_RGB)
        src.setRGB(0, 0, 0xFFFFFF); src.setRGB(1, 0, 0x000000); src.setRGB(2, 0, 0x808080); src.setRGB(3, 0, 0xFFFFFF)
        val m = silhouetteMask(src)
        assertEquals(listOf(255, 0, 128, 255), (0..3).map { alpha(m, it) }, "branco é carro, preto é nada, cinza é meio-termo")
        assertEquals(0xFFFFFF, rgb(m, 1), "a cor sai branca mesmo onde não há carro: quem pinta é o app")
    }

    @Test
    fun imagem_com_transparencia_usa_o_alfa_que_ja_tem_mesmo_com_o_carro_preto() {
        val src = BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB)
        src.setRGB(0, 0, 0xFF000000.toInt()) // carro preto, opaco
        src.setRGB(1, 0, 0x00000000) // fundo transparente
        src.setRGB(2, 0, 0x80FFFFFF.toInt()) // borda meio transparente
        val m = silhouetteMask(src)
        assertEquals(listOf(255, 0, 128), (0..2).map { alpha(m, it) })
        assertEquals(0xFFFFFF, rgb(m, 0))
    }
}
