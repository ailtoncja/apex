package app.apex.player

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/**
 * Um quadro do vídeo como o VLC decodifica (YUV 4:2:0, o "I420"): o brilho (Y) em tamanho cheio e as duas cores (U e V) com metade da largura e
 * da altura. A conversão para RGB acontece na placa de vídeo, ao desenhar ([YuvPainter]).
 *
 * Antes o VLC convertia cada quadro para RGB no processador e o app copiava 8 MB por quadro (1080p): só isso era mais da metade do processador
 * que o Apex gastava com uma live. Em YUV são 3 MB por quadro, sem conversão nenhuma no processador, e com a decodificação pela placa de vídeo
 * o processo todo caiu de ~11,6% para ~5% do PC (medido com uma live 1080p60).
 */
internal class YuvFrame(val y: Image, val u: Image, val v: Image) : AutoCloseable {
    val width: Int get() = y.width
    val height: Int get() = y.height

    override fun close() {
        y.close()
        u.close()
        v.close()
    }

    companion object {
        /** As três partes de um quadro [width] x [height] já copiadas do VLC. */
        fun of(width: Int, height: Int, yBytes: ByteArray, uBytes: ByteArray, vBytes: ByteArray): YuvFrame {
            val cw = chromaSize(width)
            val ch = chromaSize(height)
            return YuvFrame(
                Image.makeRaster(ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE), yBytes, width),
                Image.makeRaster(ImageInfo(cw, ch, ColorType.GRAY_8, ColorAlphaType.OPAQUE), uBytes, cw),
                Image.makeRaster(ImageInfo(cw, ch, ColorType.GRAY_8, ColorAlphaType.OPAQUE), vBytes, cw),
            )
        }

        /** O tamanho das partes de cor (metade, arredondando para cima). */
        fun chromaSize(size: Int) = (size + 1) / 2
    }
}

/**
 * Desenha um [YuvFrame] convertendo para RGB num shader na placa de vídeo. As cores seguem o padrão do vídeo: BT.709 em HD (720p ou mais) e
 * BT.601 abaixo, com a faixa "limitada" (o preto é 16 e o branco 235), que é a das lives e vídeos da Twitch, Kick e YouTube.
 *
 * Reduzido (o vídeo maior que o espaço na tela) o brilho é a média de várias amostras da área que cada ponto da tela cobre, como um mipmap: sem
 * isso o vídeo reduzido sai serrilhado e parece de bitrate baixo.
 */
internal object YuvPainter {
    private const val SKSL = """
        uniform shader yTex;
        uniform shader uTex;
        uniform shader vTex;
        uniform float footprint;
        uniform float4 toRgb;
        uniform float2 lumaRange;
        uniform float chromaScale;

        float luma(float2 p) {
            if (footprint <= 1.25) return yTex.eval(p).r;
            float sum = 0.0;
            if (footprint <= 2.25) {
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        sum += yTex.eval(p + (float2(float(i), float(j)) - 1.5) * (footprint / 4.0)).r;
                    }
                }
                return sum / 16.0;
            }
            // Redução grande (quadros pequenos, o Multi): mais amostras, meio pixel do vídeo entre elas, cobrindo a área inteira.
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    sum += yTex.eval(p + (float2(float(i), float(j)) - 3.5) * (footprint / 8.0)).r;
                }
            }
            return sum / 64.0;
        }

        half4 main(float2 p) {
            float y = (luma(p) - lumaRange.x) * lumaRange.y;
            float2 c = p * 0.5;
            float u = (uTex.eval(c).r - 0.5) * chromaScale;
            float v = (vTex.eval(c).r - 0.5) * chromaScale;
            float r = y + toRgb.x * v;
            float g = y - toRgb.y * u - toRgb.z * v;
            float b = y + toRgb.w * u;
            return half4(half3(saturate(float3(r, g, b))), 1.0);
        }
    """

    private val effect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(SKSL) }

    /** Desenha [frame] no retângulo [x], [y], [width], [height] de [canvas]. */
    fun draw(canvas: Canvas, frame: YuvFrame, x: Float, y: Float, width: Float, height: Float) {
        val sx = width / frame.width
        val sy = height / frame.height
        val hd = frame.height >= 720
        val sampling = SamplingMode.LINEAR
        val yShader = frame.y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null)
        val uShader = frame.u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null)
        val vShader = frame.v.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null)
        val builder = RuntimeShaderBuilder(effect)
        builder.child("yTex", yShader)
        builder.child("uTex", uShader)
        builder.child("vTex", vShader)
        builder.uniform("footprint", 1f / minOf(sx, sy))
        // R = Y + a·V; G = Y − b·U − c·V; B = Y + d·U
        if (hd) builder.uniform("toRgb", 1.5748f, 0.1873f, 0.4681f, 1.8556f) else builder.uniform("toRgb", 1.402f, 0.344136f, 0.714136f, 1.772f)
        builder.uniform("lumaRange", 16f / 255f, 255f / 219f)
        builder.uniform("chromaScale", 255f / 224f)
        val shader = builder.makeShader(Matrix33.makeTranslate(x, y).makeConcat(Matrix33.makeScale(sx, sy)))
        val paint = Paint().apply { this.shader = shader }
        canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint)
        paint.close()
        shader.close()
        builder.close()
        yShader.close()
        uShader.close()
        vShader.close()
    }
}

/**
 * O brilho (Y) de um quadro tem alguma imagem? Espia uma grade de 8 x 8 pontos: se todos estão abaixo de [level] (o preto na faixa limitada é 16),
 * é um quadro preto, como os que a decodificação por hardware entrega quando falha em silêncio.
 */
internal fun hasPictureLuma(y: ByteArray, width: Int, height: Int, level: Int = 40): Boolean {
    if (width <= 0 || height <= 0 || y.size < width * height) return false
    for (gy in 0 until 8) {
        val row = (height * (2 * gy + 1)) / 16
        for (gx in 0 until 8) {
            if ((y[row * width + (width * (2 * gx + 1)) / 16].toInt() and 0xFF) >= level) return true
        }
    }
    return false
}
