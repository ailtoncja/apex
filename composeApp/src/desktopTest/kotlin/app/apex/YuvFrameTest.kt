package app.apex

import app.apex.player.YuvFrame
import app.apex.player.YuvPainter
import app.apex.player.hasPictureLuma
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O vídeo em YUV convertido para RGB na placa de vídeo: as cores têm de sair certas (o shader roda igual no Skia por processador). */
class YuvFrameTest {
    /** Um quadro [w] x [h] todo de uma cor YUV. */
    private fun solid(w: Int, h: Int, y: Int, u: Int, v: Int): YuvFrame {
        val cw = YuvFrame.chromaSize(w)
        val ch = YuvFrame.chromaSize(h)
        return YuvFrame.of(w, h, ByteArray(w * h) { y.toByte() }, ByteArray(cw * ch) { u.toByte() }, ByteArray(cw * ch) { v.toByte() })
    }

    /** Desenha [frame] reduzido para [outW] x [outH] e devolve o RGB do centro. */
    private fun centerRgb(frame: YuvFrame, outW: Int, outH: Int): Triple<Int, Int, Int> {
        val surface = Surface.makeRasterN32Premul(outW, outH)
        YuvPainter.draw(surface.canvas, frame, 0f, 0f, outW.toFloat(), outH.toFloat())
        val bitmap = Bitmap().apply { allocN32Pixels(outW, outH) }
        surface.makeImageSnapshot().readPixels(bitmap)
        val argb = bitmap.getColor(outW / 2, outH / 2)
        surface.close()
        frame.close()
        return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    private fun assertRgb(expected: Triple<Int, Int, Int>, actual: Triple<Int, Int, Int>, what: String) {
        val ok = abs(expected.first - actual.first) <= 6 && abs(expected.second - actual.second) <= 6 && abs(expected.third - actual.third) <= 6
        assertTrue(ok, "$what: esperava $expected, saiu $actual")
    }

    @Test
    fun cores_do_video_hd_bt709() {
        // 1280x720 (HD, BT.709), reduzido como na janela
        assertRgb(Triple(255, 0, 0), centerRgb(solid(1280, 720, 63, 102, 240), 640, 360), "vermelho")
        assertRgb(Triple(0, 255, 0), centerRgb(solid(1280, 720, 173, 42, 26), 640, 360), "verde")
        assertRgb(Triple(0, 0, 255), centerRgb(solid(1280, 720, 32, 240, 118), 640, 360), "azul")
        assertRgb(Triple(255, 255, 255), centerRgb(solid(1280, 720, 235, 128, 128), 640, 360), "branco")
        assertRgb(Triple(0, 0, 0), centerRgb(solid(1280, 720, 16, 128, 128), 640, 360), "preto")
    }

    @Test
    fun cores_do_video_sd_bt601() {
        // 640x360 (abaixo de HD, BT.601), ampliado
        assertRgb(Triple(255, 0, 0), centerRgb(solid(640, 360, 81, 90, 240), 1280, 720), "vermelho")
        assertRgb(Triple(128, 128, 128), centerRgb(solid(640, 360, 126, 128, 128), 1280, 720), "cinza")
    }

    @Test
    fun reduzido_nao_serrilha_listras_finas() {
        // Listras de 1 pixel (preto/branco alternando): reduzidas à metade ou a um quarto (a janela, os quadros do Multi), têm de virar cinza
        // uniforme. Sem o filtro, cada ponto da tela pega uma listra só e sai preto ou branco: um padrão grosso que não existe no vídeo.
        val w = 1920
        val h = 1080
        val y = ByteArray(w * h) { i -> if ((i % w) % 2 == 0) 16.toByte() else 235.toByte() }
        val cw = YuvFrame.chromaSize(w)
        val ch = YuvFrame.chromaSize(h)
        for (outW in listOf(960, 480)) {
            val outH = outW * 9 / 16
            val frame = YuvFrame.of(w, h, y, ByteArray(cw * ch) { 128.toByte() }, ByteArray(cw * ch) { 128.toByte() })
            val surface = Surface.makeRasterN32Premul(outW, outH)
            YuvPainter.draw(surface.canvas, frame, 0f, 0f, outW.toFloat(), outH.toFloat())
            val bitmap = Bitmap().apply { allocN32Pixels(outW, outH) }
            surface.makeImageSnapshot().readPixels(bitmap)
            val row = (outW / 4 until outW / 4 + 40).map { bitmap.getColor(it, outH / 2) and 0xFF }
            surface.close()
            frame.close()
            assertTrue(row.max() - row.min() <= 24, "reduzido para $outW: a linha variou de ${row.min()} a ${row.max()}")
        }
    }

    @Test
    fun quadro_preto_e_quadro_com_imagem() {
        assertFalse(hasPictureLuma(ByteArray(64 * 36) { 16 }, 64, 36))
        assertTrue(hasPictureLuma(ByteArray(64 * 36) { i -> if (i == 64 * 2 + 4) 200.toByte() else 16 }, 64, 36))
        assertFalse(hasPictureLuma(ByteArray(10), 64, 36))
    }
}
