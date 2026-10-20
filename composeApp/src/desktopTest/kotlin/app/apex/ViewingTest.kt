package app.apex

import app.apex.player.ScreenRect
import app.apex.player.Viewing
import app.apex.player.viewingFor
import kotlin.test.Test
import kotlin.test.assertEquals

/** Quando o vídeo do Apex está sendo visto: para poupar o PC num jogo sem atrapalhar quem está assistindo. */
class ViewingTest {
    private val left = ScreenRect(0, 0, 1920, 1080)
    private val leftWork = ScreenRect(0, 0, 1920, 1040)
    private val right = ScreenRect(1920, 0, 3840, 1080)
    private val rightWork = ScreenRect(1920, 0, 3840, 1040)

    private fun v(
        minimized: Boolean = false, ours: Boolean = false, shell: Boolean = false, rect: ScreenRect?, caption: Boolean,
        monitor: ScreenRect?, work: ScreenRect?, own: ScreenRect = left,
    ) = viewingFor(minimized, ours, shell, rect, caption, monitor, work, own)

    @Test
    fun jogo_em_tela_cheia_no_mesmo_monitor_esconde_e_em_outro_limita() {
        assertEquals(Viewing.Hidden, v(rect = left, caption = false, monitor = left, work = leftWork), "jogo por cima do Apex")
        assertEquals(Viewing.Gaming, v(rect = right, caption = false, monitor = right, work = rightWork), "jogo no outro monitor")
    }

    @Test
    fun janelas_comuns_nao_mudam_nada() {
        // O próprio Apex em foco, a área de trabalho, uma janela pequena.
        assertEquals(Viewing.Normal, v(ours = true, rect = left, caption = false, monitor = left, work = leftWork))
        assertEquals(Viewing.Normal, v(shell = true, rect = left, caption = false, monitor = left, work = leftWork))
        assertEquals(Viewing.Normal, v(rect = ScreenRect(100, 100, 900, 700), caption = true, monitor = left, work = leftWork))
        // Um navegador maximizado no outro monitor (com barra de título, um pouco maior que a tela) não é jogo.
        assertEquals(Viewing.Normal, v(rect = ScreenRect(1912, -8, 3848, 1048), caption = true, monitor = right, work = rightWork))
    }

    @Test
    fun maximizado_por_cima_do_apex_ou_apex_minimizado_escondem() {
        assertEquals(Viewing.Hidden, v(rect = ScreenRect(-8, -8, 1928, 1048), caption = true, monitor = left, work = leftWork), "cobre o Apex")
        assertEquals(Viewing.Hidden, v(minimized = true, ours = true, rect = null, caption = false, monitor = null, work = null))
    }
}
