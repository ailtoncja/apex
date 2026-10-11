package app.apex.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apex.model.Platform

/** As cores de um tema (a identidade do app: fundos, destaque e textos). */
class Palette(
    val id: String,
    val label: String,
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val accent: Color,
    val accentPressed: Color,
    /** A cor do texto e dos ícones SOBRE o destaque (botão principal): branco no vermelho, escuro no dourado. */
    val onAccent: Color,
    val onSurface: Color,
    val muted: Color,
    val faint: Color,
    val live: Color,
    /** Canais que a pessoa apoia pagando (sub ou membro). */
    val support: Color,
    /** Tema de rali (os Subaru): ao trocar para ele, a silhueta de um carro acelera pela tela (ver `RallyIntro`). */
    val rally: Boolean = false,
)

/** Os temas do app. O primeiro é o padrão. */
object Themes {
    val Dark = Palette(
        id = "dark", label = "Escuro", isLight = false,
        background = Color(0xFF0A0A0F), surface = Color(0xFF13131A), surfaceHigh = Color(0xFF1B1B25), surfaceHighest = Color(0xFF262633),
        outline = Color(0xFF2C2C3B), accent = Color(0xFFFF3B30), accentPressed = Color(0xFFD92E25), onAccent = Color.White,
        onSurface = Color(0xFFF2F2F7), muted = Color(0xFF9494AA), faint = Color(0xFF626277), live = Color(0xFFFF2D55), support = Color(0xFFFFB300),
    )

    val Light = Palette(
        id = "light", label = "Claro", isLight = true,
        background = Color(0xFFFFFFFF), surface = Color(0xFFF7F7FA), surfaceHigh = Color(0xFFEFEFF4), surfaceHighest = Color(0xFFE2E2EA),
        outline = Color(0xFFD6D6E0), accent = Color(0xFFE5342B), accentPressed = Color(0xFFC62A22), onAccent = Color.White,
        onSurface = Color(0xFF15151C), muted = Color(0xFF5C5C70), faint = Color(0xFF9494A8), live = Color(0xFFE0264D), support = Color(0xFFA86F00),
    )

    /** Azul de rali (o carro) com rodas douradas. */
    val SubaruBlue = Palette(
        id = "subaru-azul", label = "Subaru azul e dourado", isLight = false, rally = true,
        background = Color(0xFF060D26), surface = Color(0xFF0A1633), surfaceHigh = Color(0xFF11214A), surfaceHighest = Color(0xFF1A2F66),
        outline = Color(0xFF263F80), accent = Color(0xFFFFC629), accentPressed = Color(0xFFE0A800), onAccent = Color(0xFF10131F),
        onSurface = Color(0xFFEAF0FF), muted = Color(0xFF98AEDD), faint = Color(0xFF5F78B0), live = Color(0xFFFF4560), support = Color(0xFFFFC629),
    )

    /** Preto com o dourado das rodas. */
    val SubaruBlack = Palette(
        id = "subaru-preto", label = "Subaru preto e dourado", isLight = false, rally = true,
        background = Color(0xFF080808), surface = Color(0xFF121212), surfaceHigh = Color(0xFF1B1A16), surfaceHighest = Color(0xFF27251E),
        outline = Color(0xFF3A362B), accent = Color(0xFFEDB21B), accentPressed = Color(0xFFC99410), onAccent = Color(0xFF14110A),
        onSurface = Color(0xFFF4EFE3), muted = Color(0xFFA89F8A), faint = Color(0xFF6F6857), live = Color(0xFFFF3B4E), support = Color(0xFFEDB21B),
    )

    val all = listOf(Dark, Light, SubaruBlue, SubaruBlack)

    /** O tema pelo id guardado nos ajustes; um id desconhecido (de uma versão futura, por exemplo) cai no padrão. */
    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: Dark
}

/**
 * As cores em uso. É estado do Compose: ao trocar [palette], tudo que lê as cores se redesenha na hora. As leituras (`ApexColors.Accent`...)
 * seguem iguais em todo o app.
 */
object ApexColors {
    var palette by mutableStateOf(Themes.Dark)

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val SurfaceHighest get() = palette.surfaceHighest
    val Outline get() = palette.outline
    val Accent get() = palette.accent
    val AccentPressed get() = palette.accentPressed
    val OnAccent get() = palette.onAccent
    val OnSurface get() = palette.onSurface
    val Muted get() = palette.muted
    val Faint get() = palette.faint
    val Live get() = palette.live
    /** Canais que a pessoa apoia pagando (sub ou membro). */
    val Support get() = palette.support
    val Scrim = Color(0xCC000000)

    val YouTube = Color(0xFFFF3B30)
    val Twitch = Color(0xFF9146FF)
    val Kick = Color(0xFF53FC18)
}

/**
 * Cores que vêm de fora (o nome colorido de cada pessoa no chat) podem ser qualquer coisa: branco no tema claro ou azul-escuro no escuro
 * somem. Aproxima a cor do preto ou do branco, o mínimo necessário, até ter contraste suficiente com o [background].
 */
fun readableOn(color: Color, background: Color, minContrast: Float = 3.5f): Color {
    fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
    if (contrast(color, background) >= minContrast) return color
    val target = if (background.luminance() < 0.4f) Color.White else Color.Black
    var out = color
    for (step in 1..8) {
        out = lerp(color, target, step * 0.12f)
        if (contrast(out, background) >= minContrast) break
    }
    return out
}

fun Platform.color(): Color = when (this) {
    Platform.YouTube -> ApexColors.YouTube
    Platform.Twitch -> ApexColors.Twitch
    Platform.Kick -> ApexColors.Kick
}

private fun Palette.toScheme(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentPressed,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceHigh,
        surfaceContainerHigh = surfaceHighest,
        surfaceContainerHighest = surfaceHighest,
        outline = outline,
        outlineVariant = outline,
        error = accent,
    )
}

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ApexTheme(content: @Composable () -> Unit) {
    val palette = ApexColors.palette
    val scheme = remember(palette) { palette.toScheme() }
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
