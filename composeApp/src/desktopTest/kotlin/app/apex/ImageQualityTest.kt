package app.apex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.apex.ui.components.RemoteImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** As miniaturas e capas reduzidas para a tela não podem sair serrilhadas ("pixeladas"). */
class ImageQualityTest {
    /** 1280×720 de listras verticais de 1 pixel (preto e branco), como endereço `data:` (o Coil lê direto): reduzidas com suavização viram cinza; sem suavização, viram listras. */
    private fun stripesUri(): String {
        val img = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 720) for (x in 0 until 1280) img.setRGB(x, y, if (x % 2 == 0) 0x000000 else 0xFFFFFF)
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun imagem_grande_reduzida_fica_suave_e_nao_serrilhada() = runComposeUiTest {
        val uri = stripesUri()
        setContent { Box(Modifier.size(300.dp, 169.dp)) { RemoteImage(uri, Modifier.size(300.dp, 169.dp)) } }
        var mean = 0.0
        var deviation = 999.0
        fun measure() {
            val map = onRoot().captureToImage().toPixelMap()
            val values = ArrayList<Double>()
            for (y in 20 until 150 step 3) for (x in 20 until 280 step 3) {
                val c = map[x, y]
                values += (c.red + c.green + c.blue) / 3.0 * 255
            }
            mean = values.average()
            deviation = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
        // A imagem chega de forma assíncrona: espera até aparecer algo mais claro que o fundo cinza-escuro.
        waitUntil(timeoutMillis = 15_000) { measure(); mean > 80 }
        // O primeiro quadro pode ser o do Coil, ainda sem a suavização (por um instante): dá alguns quadros para a imagem suave assumir.
        // Se ela ficar serrilhada de vez, o desvio continua alto e o teste falha.
        repeat(30) {
            if (deviation >= 35.0) {
                mainClock.advanceTimeByFrame()
                waitForIdle()
                Thread.sleep(20)
                measure()
            }
        }
        // Suave: cinza médio com pouca variação. Serrilhado seria listras de 0 e 255 (desvio perto de 127).
        assertTrue(mean in 100.0..160.0, "a média deveria ser um cinza médio, foi $mean")
        assertTrue(deviation < 35.0, "a imagem reduzida está serrilhada (desvio $deviation; suave seria menos de 35)")
    }
}
