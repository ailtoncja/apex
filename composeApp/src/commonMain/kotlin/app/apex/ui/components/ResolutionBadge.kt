package app.apex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.apex.theme.ApexColors

/** O selo da resolução pela altura do vídeo: 1080p = HD, 1440p = 2K, 2160p = 4K, 4320p = 8K; abaixo de 1080p, nenhum. */
fun resolutionBadge(height: Int): String? = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 1080 -> "HD"
    else -> null
}

/** O selo ([resolutionBadge]) de um vídeo com [height] de altura; nada abaixo de 1080p. [small] para cantos de ícone. */
@Composable
fun ResolutionBadge(height: Int, modifier: Modifier = Modifier, small: Boolean = false) {
    val label = resolutionBadge(height) ?: return
    Text(
        label,
        modifier.background(ApexColors.Accent, RoundedCornerShape(if (small) 3.dp else 4.dp))
            .padding(horizontal = if (small) 3.dp else 5.dp, vertical = if (small) 0.dp else 1.dp),
        color = Color.White, fontWeight = FontWeight.Black, fontSize = if (small) 8.sp else 10.sp, lineHeight = if (small) 10.sp else 13.sp,
    )
}
