package app.apex

import app.apex.util.FreezeWatch
import java.awt.EventQueue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** O vigia de travamento: com a interface parada por mais de 4 s, grava o que cada thread fazia (e quanto durou). */
class FreezeWatchTest {
    @Test
    fun interface_parada_vira_um_relatorio_com_a_causa() {
        val file = Files.createTempFile("travamentos", ".txt").toFile()
        file.delete()
        FreezeWatch.start(file)
        Thread.sleep(1_200) // deixa o vigia dar uma volta com a interface livre: nada é gravado
        assertTrue(!file.exists() || file.readText().isEmpty(), "sem travamento, nada no arquivo")

        EventQueue.invokeAndWait { travaDeProposito() }
        val end = System.currentTimeMillis() + 3_000
        while (!(file.exists() && file.readText().contains("voltou depois de"))) {
            check(System.currentTimeMillis() < end) { "o relatório não foi gravado" }
            Thread.sleep(50)
        }
        val report = file.readText()
        assertTrue("a interface não responde" in report)
        assertTrue("AWT-EventQueue" in report, "a thread da interface aparece")
        assertTrue("travaDeProposito" in report, "com o ponto exato em que ela estava parada")
    }

    private fun travaDeProposito() = Thread.sleep(5_500)
}
