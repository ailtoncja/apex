package app.apex

import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.state.LiveWatcher
import app.apex.state.liveAlertText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O vigia de lives: quem entra ao vivo aparece e é avisado; falha de rede não derruba nem repete aviso. */
class LiveWatcherTest {
    private val alfa = Channel(Platform.Twitch, "alfa", "Alfa")
    private val beta = Channel(Platform.Twitch, "beta", "Beta")

    private fun live(c: Channel, viewers: Long = 10) = Media(c.platform, c.id, "ao vivo ${c.id}", c, viewCount = viewers, isLive = true, url = "https://x/${c.id}")

    /** Espera o fluxo do vigia (ele é derivado em outra corrotina) chegar ao esperado. */
    private suspend fun LiveWatcher.waitLive(expected: List<String>) {
        repeat(60) {
            if (live.value.map { m -> m.id } == expected) return
            delay(50)
        }
        assertEquals(expected, live.value.map { it.id })
    }

    private fun newWatcher(online: () -> Set<String>, failing: () -> Set<String> = { emptySet() }): Pair<AppContainer, LiveWatcher> {
        val (container, _) = newTestApp()
        container.data.addSubscriptions(listOf(alfa, beta))
        val watcher = LiveWatcher(container) { _, channels ->
            buildMap {
                for (c in channels) {
                    if (c.id in failing()) continue // a consulta deste canal falhou: ele não entra no resultado
                    put(c.key, if (c.id in online()) live(c) else null)
                }
            }
        }
        return container to watcher
    }

    @Test
    fun a_primeira_conferencia_mostra_mas_nao_avisa_e_quem_entra_depois_e_avisado() = runBlocking {
        var online = setOf("alfa")
        val (container, watcher) = newWatcher({ online })
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa"))
        assertNull(container.ui.toast, "quem já estava ao vivo quando o app abriu não gera aviso")

        online = setOf("alfa", "beta")
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa", "beta"))
        assertEquals("Beta está ao vivo agora", container.ui.toast)
        assertTrue("beta" in watcher.liveKeys.value.map { it.substringAfter(':') } || watcher.liveKeys.value.any { it.endsWith("beta") })
    }

    @Test
    fun quem_sai_do_ar_some_e_ao_voltar_e_avisado_de_novo() = runBlocking {
        var online = setOf("alfa")
        val (container, watcher) = newWatcher({ online })
        watcher.check(Platform.Twitch)
        online = emptySet()
        watcher.check(Platform.Twitch)
        watcher.waitLive(emptyList())
        container.ui.toast = null
        online = setOf("alfa")
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa"))
        assertEquals("Alfa está ao vivo agora", container.ui.toast)
    }

    @Test
    fun consulta_que_falha_nao_tira_a_live_do_ar_nem_repete_o_aviso() = runBlocking {
        var failing = emptySet<String>()
        val (container, watcher) = newWatcher({ setOf("alfa") }, { failing })
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa"))
        container.ui.toast = null

        failing = setOf("alfa") // a rede falhou nesta rodada
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa"))
        assertNull(container.ui.toast)

        failing = emptySet() // voltou: continua ao vivo, e não é "novidade"
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa"))
        assertNull(container.ui.toast, "a falha de rede não pode fazer o aviso repetir")
    }

    @Test
    fun canal_que_a_pessoa_deixou_de_seguir_sai_da_lista_na_hora() = runBlocking {
        val (container, watcher) = newWatcher({ setOf("alfa", "beta") })
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa", "beta"))
        container.data.toggleSubscription(beta)
        watcher.waitLive(listOf("alfa"))
    }

    @Test
    fun aviso_pode_ser_desligado_e_nao_avisa_do_canal_que_ja_esta_assistindo() = runBlocking {
        var online = emptySet<String>()
        val (container, watcher) = newWatcher({ online })
        watcher.check(Platform.Twitch)
        container.data.updateSettings { it.copy(liveAlerts = false) }
        online = setOf("alfa")
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa"))
        assertNull(container.ui.toast, "com o aviso desligado nada aparece, mas a live entra na lista")

        container.data.updateSettings { it.copy(liveAlerts = true) }
        container.session.open(live(beta))
        online = setOf("alfa", "beta")
        watcher.check(Platform.Twitch)
        watcher.waitLive(listOf("alfa", "beta"))
        assertNull(container.ui.toast, "não avisa de quem a pessoa já está vendo")
    }

    @Test
    fun texto_do_aviso() {
        assertEquals("", liveAlertText(emptyList()))
        assertEquals("Gaules está ao vivo agora", liveAlertText(listOf("Gaules")))
        assertEquals("A e B estão ao vivo agora", liveAlertText(listOf("A", "B")))
        assertEquals("A, B e mais 3 estão ao vivo agora", liveAlertText(listOf("A", "B", "C", "D", "E")))
    }
}
