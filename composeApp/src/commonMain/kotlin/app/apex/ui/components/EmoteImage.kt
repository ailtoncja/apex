package app.apex.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A imagem de um emote do chat, animada quando o emote é (GIF e WebP: os da 7TV e da BetterTTV costumam ser). Todas as cópias do mesmo
 * emote na tela andam juntas, como no navegador. Quando a imagem carrega, a proporção dela vai para [app.apex.chat.EmoteRatios].
 */
@Composable
expect fun EmoteImage(url: String, modifier: Modifier = Modifier)
