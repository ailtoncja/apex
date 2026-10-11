package app.apex.ui.shell

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A silhueta do carro de rali como imagem, quando a pessoa põe uma: `subaru.png` na pasta de dados do app (`%APPDATA%\Apex`) ou nos
 * recursos (`composeApp/src/desktopMain/resources`). Só o alfa da imagem conta (ver [loadRallySilhouette]); o app pinta com as cores do
 * tema. Sem o arquivo vale o desenho vetorial de [rallyCarPath]. A imagem é lida uma vez, ao abrir o app.
 *
 * As medidas abaixo são as da imagem de referência (um Impreza visto de lado, olhando para a esquerda, centrado num quadrado): se a sua
 * imagem for outra, ajuste aqui onde ficam as rodas e quanto da largura o carro ocupa.
 */
object RallyArt {
    var silhouette: ImageBitmap? = null

    /** A imagem olha para a esquerda: espelhada, o carro anda para a direita. */
    var mirrored: Boolean = true

    /** O centro de cada roda na imagem (frações da largura e da altura) e o raio (fração da largura), para os aros dourados. */
    var wheels: List<Triple<Float, Float, Float>> = listOf(Triple(0.208f, 0.578f, 0.069f), Triple(0.749f, 0.578f, 0.069f))

    /** A fração da largura da imagem que o carro ocupa (as margens em volta não contam para o tamanho na tela). */
    var carSpan: Float = 0.915f
}

/** Lê a imagem da silhueta (a da pasta de dados, senão a dos recursos), já como máscara; `null` se não há arquivo. */
expect fun loadRallySilhouette(): ImageBitmap?
