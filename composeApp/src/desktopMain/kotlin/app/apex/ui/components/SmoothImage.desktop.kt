package app.apex.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

// Reduzindo: mipmap com filtro linear (suave). Ampliando: Catmull-Rom (nítido, sem "blocos"). O mesmo que o vídeo usa.
private val DOWNSCALE: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
private val UPSCALE: SamplingMode = SamplingMode.CATMULL_ROM

actual fun smoothPainter(image: coil3.Image): Painter? {
    val bitmap = (image as? coil3.BitmapImage)?.bitmap ?: return null
    return SmoothPainter(Image.makeFromBitmap(bitmap))
}

private class SmoothPainter(private val image: Image) : Painter() {
    override val intrinsicSize: Size = Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        val sampling = if (size.width < image.width * 0.98f) DOWNSCALE else UPSCALE
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(size.width, size.height),
                // strict = false: com `true` o Skia ignora os mipmaps e a imagem reduzida sai serrilhada. A imagem inteira é a origem,
                // então não há borda de onde a amostragem possa "vazar".
                sampling, null, false,
            )
        }
    }
}
