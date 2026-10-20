package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Importar as inscrições de uma conta é uma sincronização: entra quem falta e sai quem a pessoa deixou de seguir na plataforma. */
class SubscriptionSyncTest {
    private fun data() = UserData(FileStore(Files.createTempDirectory("apex-sync").toFile()), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun quem_deixou_de_seguir_na_plataforma_sai_e_as_outras_plataformas_ficam() {
        val d = data()
        val a = Channel(Platform.YouTube, "UCa", "A"); val b = Channel(Platform.YouTube, "UCb", "B"); val c = Channel(Platform.YouTube, "UCc", "C")
        val t = Channel(Platform.Twitch, "gaules", "Gaules")
        d.addSubscriptions(listOf(a, b, t))
        val (added, removed) = d.syncSubscriptions(Platform.YouTube, listOf(b, c), removeMissing = true)
        assertEquals(1, added, "só o C é novo")
        assertEquals(1, removed, "o A saiu")
        assertEquals(setOf("YouTube:UCb", "YouTube:UCc", "Twitch:gaules"), d.subscriptions.value.map { it.key }.toSet())
    }

    @Test
    fun sem_a_lista_completa_so_entra_quem_falta() {
        val d = data()
        val a = Channel(Platform.Twitch, "a1", "A"); val b = Channel(Platform.Twitch, "b1", "B")
        d.addSubscriptions(listOf(a))
        val (added, removed) = d.syncSubscriptions(Platform.Twitch, listOf(b), removeMissing = false)
        assertEquals(1, added); assertEquals(0, removed)
        assertTrue(d.subscriptions.value.any { it.id == "a1" }, "a Twitch só entrega 200: quem está fora da lista não sai")
    }

    @Test
    fun o_que_mudou_so_no_apex_nao_e_desfeito_pela_lista_da_plataforma() {
        val d = data()
        val a = Channel(Platform.Twitch, "a1", "A"); val b = Channel(Platform.Twitch, "b1", "B"); val c = Channel(Platform.Twitch, "c1", "C")
        d.addSubscriptions(listOf(a, c)) // c: seguido só no app; b: deixado de seguir só no app
        val (added, removed) = d.syncSubscriptions(Platform.Twitch, listOf(a, b), removeMissing = true, keep = setOf(b.key, c.key))
        assertEquals(0, added, "o B não volta")
        assertEquals(0, removed, "o C não sai")
        assertEquals(setOf("Twitch:a1", "Twitch:c1"), d.subscriptions.value.map { it.key }.toSet())
    }

    @Test
    fun o_resumo_diz_quantos_entraram_e_quantos_sairam() {
        assertEquals("3 canais importados", AppContainer.ImportResult(3).summary)
        assertEquals("3 canais importados, 2 que você deixou de seguir saíram", AppContainer.ImportResult(3, removed = 2).summary)
        assertEquals("0 canais importados (aviso)", AppContainer.ImportResult(0, "aviso").summary)
    }
}
