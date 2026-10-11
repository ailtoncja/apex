package app.apex.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * O volume vai de 0 a 200%: acima de 100% o som é amplificado (útil para vídeos e lives baixinhos). No Windows o app toca o áudio que o VLC
 * decodifica, com um buffer curto e o volume aplicado na hora (`SoftAudio`); deixado com o VLC, acima de 100% cada mudança levava ~1,2 s
 * para ser ouvida (ganho por software antes do buffer de 2 s do WASAPI). O ajuste [app.apex.model.AppSettings.volumeBoost] limita a 100%.
 */
const val MAX_VOLUME = 200

/** O volume "normal" (sem amplificar). */
const val NORMAL_VOLUME = 100

/** O volume máximo: 200% com o reforço ligado ([app.apex.model.AppSettings.volumeBoost]), 100% sem ele. */
fun maxVolume(boost: Boolean): Int = if (boost) MAX_VOLUME else NORMAL_VOLUME

/** Perto de 100% o controle deslizante "gruda" nele, para ser fácil voltar ao normal. */
private const val SNAP_PERCENT = 4

/** A posição do controle deslizante (0 a 1) vira a porcentagem de volume (0 a [max]); com o reforço, perto de 100% gruda nele. */
fun volumeFromSlider(fraction: Float, max: Int = MAX_VOLUME): Int {
    val v = (fraction.coerceIn(0f, 1f) * max).roundToInt()
    return if (max > NORMAL_VOLUME && abs(v - NORMAL_VOLUME) <= SNAP_PERCENT) NORMAL_VOLUME else v
}

/** A posição do controle deslizante (0 a 1) para uma porcentagem de volume. */
fun sliderFromVolume(percent: Int, max: Int = MAX_VOLUME): Float = percent.coerceIn(0, max) / max.toFloat()

/** Passo do teclado (setas): soma [delta], sempre entre 0 e [max]. */
fun stepVolume(current: Int, delta: Int, max: Int = MAX_VOLUME): Int = (current + delta).coerceIn(0, max)

/** As velocidades do menu do player e dos atalhos `<` e `>`. */
val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** A próxima velocidade da lista acima ([faster]) ou abaixo da [current]; nas pontas fica onde está. */
fun stepSpeed(current: Float, faster: Boolean): Float =
    if (faster) PLAYBACK_SPEEDS.firstOrNull { it > current + 0.001f } ?: PLAYBACK_SPEEDS.last()
    else PLAYBACK_SPEEDS.lastOrNull { it < current - 0.001f } ?: PLAYBACK_SPEEDS.first()
