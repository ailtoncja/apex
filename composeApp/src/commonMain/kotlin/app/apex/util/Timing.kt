package app.apex.util

/**
 * Marcas de tempo para medir a abertura de um vídeo (resolver o endereço, iniciar o player, primeiro quadro). Só grava quando alguém liga
 * [sink] (o app liga com `APEX_TIMING=1`); fora disso cada marca custa uma checagem.
 */
object Timing {
    var sink: ((String) -> Unit)? = null

    val enabled: Boolean get() = sink != null
    private var t0 = 0L

    /** Zera o relógio: a próxima marca mostra o tempo desde aqui. */
    fun start(label: String) {
        val s = sink ?: return
        t0 = System.nanoTime()
        s("0 ms  $label")
    }

    fun mark(label: String) {
        val s = sink ?: return
        s("${(System.nanoTime() - t0) / 1_000_000} ms  $label")
    }
}
