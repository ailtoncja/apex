package app.apex.ui.shell

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.apex.data.FileStore
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

actual fun loadRallySilhouette(): ImageBitmap? {
    val stream: InputStream = File(FileStore.defaultDir(), SILHOUETTE_FILE).takeIf { it.isFile }?.inputStream()
        ?: RallyArt::class.java.classLoader?.getResourceAsStream(SILHOUETTE_FILE)
        ?: return null
    val src = stream.use { runCatching { ImageIO.read(it) }.getOrNull() } ?: return null
    return silhouetteMask(src).toComposeImageBitmap()
}

internal const val SILHOUETTE_FILE = "subaru.png"

/**
 * A imagem como máscara: o que importa é onde há carro. Numa imagem sem transparência (branco sobre preto, como a referência) o brilho de
 * cada pixel vira o alfa: branco é carro, preto é nada, cinza é meio-termo. Numa imagem com transparência vale o alfa que ela já tem (o carro
 * pode até ser preto). A cor sai sempre branca: quem pinta é o app, com a cor do tema.
 */
internal fun silhouetteMask(src: BufferedImage): BufferedImage {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h).also { src.getRGB(0, 0, w, h, it, 0, w) }
    val transparent = src.colorModel.hasAlpha() && pixels.any { (it ushr 24) < 255 }
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val masked = IntArray(w * h) { i ->
        val p = pixels[i]
        val alpha = if (transparent) p ushr 24 else ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        (alpha shl 24) or 0xFFFFFF
    }
    out.setRGB(0, 0, w, h, masked, 0, w)
    return out
}
