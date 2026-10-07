package app.apex.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.apex.model.Platform

object ApexColors {
    val Background = Color(0xFF0A0A0F)
    val Surface = Color(0xFF13131A)
    val SurfaceHigh = Color(0xFF1B1B25)
    val SurfaceHighest = Color(0xFF262633)
    val Outline = Color(0xFF2C2C3B)
    val Accent = Color(0xFFFF3B30)
    val AccentPressed = Color(0xFFD92E25)
    val OnSurface = Color(0xFFF2F2F7)
    val Muted = Color(0xFF9494AA)
    val Faint = Color(0xFF626277)
    val Live = Color(0xFFFF2D55)
    /** Canais que a pessoa apoia pagando (sub ou membro). */
    val Support = Color(0xFFFFB300)
    val Scrim = Color(0xCC000000)

    val YouTube = Color(0xFFFF3B30)
    val Twitch = Color(0xFF9146FF)
    val Kick = Color(0xFF53FC18)
}

fun Platform.color(): Color = when (this) {
    Platform.YouTube -> ApexColors.YouTube
    Platform.Twitch -> ApexColors.Twitch
    Platform.Kick -> ApexColors.Kick
}

private val scheme = darkColorScheme(
    primary = ApexColors.Accent,
    onPrimary = Color.White,
    primaryContainer = ApexColors.AccentPressed,
    background = ApexColors.Background,
    onBackground = ApexColors.OnSurface,
    surface = ApexColors.Surface,
    onSurface = ApexColors.OnSurface,
    surfaceVariant = ApexColors.SurfaceHigh,
    onSurfaceVariant = ApexColors.Muted,
    surfaceContainer = ApexColors.SurfaceHigh,
    surfaceContainerHigh = ApexColors.SurfaceHighest,
    surfaceContainerHighest = ApexColors.SurfaceHighest,
    outline = ApexColors.Outline,
    outlineVariant = ApexColors.Outline,
    error = ApexColors.Accent,
)

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
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
