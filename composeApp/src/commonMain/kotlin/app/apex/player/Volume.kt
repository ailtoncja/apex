package app.apex.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** O volume vai de 0 a 200%: acima de 100% o libVLC amplifica o som (útil para vídeos e lives baixinhos). */
const val MAX_VOLUME = 200

/** O volume "normal" (sem amplificar). */
const val NORMAL_VOLUME = 100

/** Perto de 100% o controle deslizante "gruda" nele, para ser fácil voltar ao normal. */
private const val SNAP_PERCENT = 4

/** A posição do controle deslizante (0 a 1) vira a porcentagem de volume (0 a 200). */
fun volumeFromSlider(fraction: Float): Int {
    val v = (fraction.coerceIn(0f, 1f) * MAX_VOLUME).roundToInt()
    return if (abs(v - NORMAL_VOLUME) <= SNAP_PERCENT) NORMAL_VOLUME else v
}

/** A posição do controle deslizante (0 a 1) para uma porcentagem de volume. */
fun sliderFromVolume(percent: Int): Float = percent.coerceIn(0, MAX_VOLUME) / MAX_VOLUME.toFloat()

/** Passo do teclado (setas): soma [delta], sempre entre 0 e [MAX_VOLUME]. */
fun stepVolume(current: Int, delta: Int): Int = (current + delta).coerceIn(0, MAX_VOLUME)
