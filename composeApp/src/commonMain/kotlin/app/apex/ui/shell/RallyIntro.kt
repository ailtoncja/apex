package app.apex.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.apex.theme.ApexColors
import app.apex.theme.Themes
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A piscadela dos temas Subaru: ao trocar para um deles, a silhueta de um Impreza de rali (o sedã clássico de asa grande, entrada de ar no
 * capô e rodas douradas) atravessa a tela acelerando, com rastros de velocidade, e some pela direita. Só acontece na troca (não ao abrir o
 * app já nesse tema) e não atrapalha nada: fica por cima de tudo por um instante e não recebe cliques.
 *
 * O carro é desenhado como vetor ([rallyCarPath]) ou, se a pessoa pôs uma imagem ([RallyArt]), com ela.
 */
@Composable
fun BoxScope.RallyIntro(themeId: String?) {
    var previous by remember { mutableStateOf(themeId) }
    var run by remember { mutableIntStateOf(0) }
    if (themeId != previous) {
        previous = themeId
        if (Themes.byId(themeId).rally) run++
    }
    if (run == 0) return
    val progress = remember { Animatable(0f) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(run) {
        playing = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(RALLY_DURATION_MS, easing = ACCELERATING))
        playing = false
    }
    if (!playing) return
    val body = ApexColors.OnSurface.copy(alpha = 0.94f)
    val gold = ApexColors.Accent
    Canvas(Modifier.fillMaxSize().testTag("rally-intro")) {
        drawRallyScene(progress.value, body, gold, RallyArt.silhouette)
    }
}

internal const val RALLY_DURATION_MS = 1_500

/** Começa devagar e termina em disparada: na primeira metade do tempo o carro anda menos de um quarto do caminho. */
internal val ACCELERATING = CubicBezierEasing(0.6f, 0f, 0.95f, 0.35f)

/** Onde fica a frente do carro (em px) para o [progress] da animação: entra pela esquerda, fora da tela, e sai pela direita. */
internal fun rallyCarX(progress: Float, width: Float, carWidth: Float): Float = -carWidth * 0.15f + (width + carWidth * 1.3f) * progress

/** A largura do carro: cerca de 30% da janela, entre 240 e 560 px. */
internal fun rallyCarWidth(width: Float): Float = (width * 0.3f).coerceIn(240f, 560f)

/** O carro vetorial cabe numa caixa de 100 × 28 unidades (a frente em x = 100); as rodas ficam em y = 21,2. */
private const val CAR_H = 28f
private val WHEELS = listOf(23.7f to 21.2f, 82.8f to 21.2f)

private fun poly(s: Float, vararg xy: Float) = Path().apply {
    moveTo(xy[0] * s, xy[1] * s)
    for (i in 2 until xy.size step 2) lineTo(xy[i] * s, xy[i + 1] * s)
    close()
}

/** A silhueta vetorial (já com os recortes das janelas, das maçanetas e dos arcos das rodas), na escala [s] px por unidade. */
internal fun rallyCarPath(s: Float): Path {
    // A carroceria, de trás (asa) para a frente: para-choque traseiro, porta-malas, vidro traseiro, teto, para-brisa, capô, nariz.
    val shell = poly(
        s,
        5.9f, 23.7f, 3.4f, 20.7f, 2.6f, 10.5f, 6.9f, 8.3f, 13.8f, 7.5f, 27.6f, 1.4f, 47.3f, 1.6f, 50.3f, 2.6f, 63.1f, 11.2f,
        76f, 13f, 91.7f, 14.8f, 95.7f, 15.4f, 98.4f, 17.2f, 99.6f, 20.7f, 100f, 23.7f,
    )
    // A asa traseira (lâmina e os dois pés), a entrada de ar no capô e o retrovisor.
    val wing = poly(s, 0f, 5.7f, 15.8f, 4.5f, 15.8f, 6.3f, 13.2f, 6.4f, 12.6f, 8f, 5.2f, 8.3f, 4.2f, 6.6f, 0f, 7.4f)
    val scoop = poly(s, 69f, 13.3f, 71f, 11.6f, 79f, 11f, 80.5f, 12.9f)
    val mirror = poly(s, 61.5f, 11.3f, 63.5f, 9.4f, 66.5f, 10f, 66f, 11.6f)
    val solid = Path().apply { op(shell, wing, PathOperation.Union); op(this, scoop, PathOperation.Union); op(this, mirror, PathOperation.Union) }
    // Os recortes: vidros das portas (o pilar entre eles fica), maçanetas e os arcos das rodas.
    val holes = Path().apply {
        addPath(poly(s, 46.8f, 3.4f, 49.6f, 3.4f, 61.4f, 10.6f, 46.8f, 10.6f))
        addPath(poly(s, 30.4f, 3.4f, 44.6f, 3.4f, 44.6f, 10.6f, 17.6f, 9f))
        addRect(Rect(36f * s, 13.6f * s, 39.2f * s, 14.5f * s))
        addRect(Rect(55f * s, 13.6f * s, 58.2f * s, 14.5f * s))
        for ((cx, cy) in WHEELS) addOval(Rect(Offset(cx * s, cy * s), 7.6f * s))
    }
    return Path().apply { op(solid, holes, PathOperation.Difference) }
}

private fun DrawScope.drawRallyScene(progress: Float, body: Color, gold: Color, image: ImageBitmap?) {
    val carW = rallyCarWidth(size.width)
    val front = rallyCarX(progress, size.width, carW)
    val left = front - carW
    val ground = size.height * 0.64f
    val carH = CAR_H * carW / 100f
    // Rastros: quanto mais rápido, mais compridos; desbotam para trás.
    val speed = (progress * progress).coerceIn(0f, 1f)
    val trail = carW * (0.2f + 1.6f * speed)
    for ((i, f) in listOf(0.21f, 0.37f, 0.53f, 0.7f, 0.86f).withIndex()) {
        val yy = ground - carH + f * carH
        val len = trail * (0.55f + 0.45f * ((i * 7) % 5) / 4f)
        val start = left + carW * 0.04f
        drawLine(
            Brush.horizontalGradient(0f to gold.copy(alpha = 0f), 1f to gold.copy(alpha = 0.55f * speed + 0.1f), startX = start - len, endX = start),
            Offset(start - len, yy), Offset(start, yy), strokeWidth = (1.1f + 0.6f * speed) * carW / 100f, cap = StrokeCap.Round,
        )
    }
    // A sombra no chão, puxada para trás com a velocidade.
    drawOval(body.copy(alpha = 0.18f), Offset(left + (0.04f - 0.1f * speed) * carW, ground - carH * 0.05f), Size((0.96f + 0.1f * speed) * carW, carH * 0.115f))
    if (image != null) drawImageCar(image, left, carW, ground, body, gold, front) else drawVectorCar(left, ground - carH, carW / 100f, body, gold, front)
}

private fun DrawScope.drawVectorCar(left: Float, top: Float, s: Float, body: Color, gold: Color, front: Float) {
    translate(left, top) {
        drawPath(rallyCarPath(s), body)
        for ((cx, cy) in WHEELS) drawWheel(Offset(cx * s, cy * s), 6.8f * s, body, gold, front)
    }
}

/** A imagem ([RallyArt]) pintada com a cor da silhueta, espelhada se preciso, com o carro ocupando [carW] e as rodas no chão. */
private fun DrawScope.drawImageCar(image: ImageBitmap, left: Float, carW: Float, ground: Float, body: Color, gold: Color, front: Float) {
    val imgW = carW / RallyArt.carSpan
    val imgH = imgW * image.height / image.width
    val wheelFracY = RallyArt.wheels.firstOrNull()?.second ?: 0.58f
    val wheelR = (RallyArt.wheels.firstOrNull()?.third ?: 0.07f) * imgW
    val imgLeft = left - (imgW - carW) / 2f
    val imgTop = ground - wheelFracY * imgH - wheelR
    val center = Offset(imgLeft + imgW / 2f, imgTop + imgH / 2f)
    scale(if (RallyArt.mirrored) -1f else 1f, 1f, center) {
        drawImage(
            image, dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()), dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
            colorFilter = ColorFilter.tint(body, BlendMode.SrcIn),
        )
    }
    for ((fx, fy, fr) in RallyArt.wheels) {
        val x = imgLeft + (if (RallyArt.mirrored) 1f - fx else fx) * imgW
        drawWheel(Offset(x, imgTop + fy * imgH), fr * imgW * 0.9f, body, gold, front)
    }
}

/** Uma roda: pneu na cor da silhueta, aro dourado com cinco raios, girando conforme o carro anda. */
private fun DrawScope.drawWheel(c: Offset, r: Float, body: Color, gold: Color, front: Float) {
    val angle = Math.toDegrees((front / r).toDouble()).toFloat()
    drawCircle(body, r, c)
    drawCircle(gold, r * 0.68f, c)
    rotate(angle, c) {
        for (k in 0 until 5) {
            val a = Math.toRadians((k * 72).toDouble())
            drawLine(body, c, Offset(c.x + (r * 0.59f) * cos(a).toFloat(), c.y + (r * 0.59f) * sin(a).toFloat()), strokeWidth = r * 0.19f, cap = StrokeCap.Round)
        }
    }
    drawCircle(body, r * 0.16f, c)
}
