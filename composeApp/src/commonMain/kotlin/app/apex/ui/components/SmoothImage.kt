package app.apex.ui.components

import androidx.compose.ui.graphics.painter.Painter

/**
 * Um painter que desenha uma imagem já decodificada reduzindo-a com suavização de verdade (mipmaps com filtro linear), ou `null` se a
 * imagem não for de um tipo que ele sabe desenhar (aí vale o painter comum).
 *
 * Existe porque o Compose, no desktop, só sabe reduzir com filtro bilinear: ao encolher uma miniatura 3 ou 4 vezes ela sai serrilhada
 * ("pixelada"), e os níveis `Low` e `Medium` do `FilterQuality` fazem o mesmo.
 */
expect fun smoothPainter(image: coil3.Image): Painter?
