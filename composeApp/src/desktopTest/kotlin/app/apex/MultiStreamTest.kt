package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Media
import app.apex.model.Platform
import app.apex.multi.MultiStream
import app.apex.nav.Route
import app.apex.player.LoadState
import app.apex.source.LinkTarget
import app.apex.source.liveFromName
import app.apex.source.parseLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O Multi: várias lives ao mesmo tempo, de qualquer plataforma, com uma só com som, guardadas entre uma abertura e outra. */
class MultiStreamTest {
    private fun live(platform: Platform, id: String) =
        Media(platform, id, id, Channel(platform, id, id.uppercase(), url = "https://x/$id"), isLive = true, url = "https://x/$id")

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(Files.createTempDirectory("apex-multi").toFile()), scope)
        val players = mutableListOf<FakePlayer2>()
        var beforeStarts = 0
        val multi = MultiStream(scope, data, { FakePlayer2().also { players += it } }, ::fakeTileResolve, beforeStart = { beforeStarts++ })
        fun player(key: String) = multi.tiles.value.first { it.key == key }.player as FakePlayer2
        suspend fun ready() = withTimeout(5_000) { while (multi.tiles.value.any { it.load.value !is LoadState.Ready }) delay(10) }
        /** Parar e soltar os players acontece fora da thread de quem pediu: espera (até 3 s). */
        suspend fun until(cond: () -> Boolean) = withTimeout(3_000) { while (!cond()) delay(10) }
    }

    @Test
    fun adiciona_lives_de_plataformas_misturadas_sem_repetir_e_ate_nove() {
        val h = Harness()
        assertTrue(h.multi.add(live(Platform.Twitch, "a")))
        assertTrue(h.multi.add(live(Platform.Kick, "b")))
        assertTrue(h.multi.add(live(Platform.YouTube, "c")))
        assertFalse(h.multi.add(live(Platform.Twitch, "a")), "a mesma live não entra duas vezes")
        assertEquals(listOf(Platform.Twitch, Platform.Kick, Platform.YouTube), h.multi.medias.map { it.platform })
        for (i in 4..9) assertTrue(h.multi.add(live(Platform.Twitch, "t$i")))
        assertTrue(h.multi.isFull)
        assertFalse(h.multi.add(live(Platform.Twitch, "demais")), "cabem ${MultiStream.MAX}")
        assertEquals(9, h.multi.tiles.value.size)
        assertEquals(setOf("Twitch:a"), h.multi.audio.value, "a primeira fica com o som")
        assertEquals("Twitch:a", h.multi.chat.value, "e com o chat")
    }

    @Test
    fun o_som_comeca_na_primeira_pode_estar_em_varias_e_passa_adiante_quando_a_unica_sai() = runBlocking {
        val h = Harness()
        val a = live(Platform.Twitch, "a"); val b = live(Platform.Kick, "b"); val c = live(Platform.Twitch, "c")
        h.multi.add(a); h.multi.add(b); h.multi.add(c)
        h.multi.start()
        h.ready()
        assertFalse(h.player(a.key).silenced); assertTrue(h.player(b.key).silenced); assertTrue(h.player(c.key).silenced)
        h.multi.toggleAudio(b.key)
        assertEquals(setOf(a.key, b.key), h.multi.audio.value, "ligar o som de uma não tira o das outras")
        assertFalse(h.player(a.key).silenced); assertFalse(h.player(b.key).silenced); assertTrue(h.player(c.key).silenced)
        h.multi.toggleAudio(a.key)
        assertEquals(setOf(b.key), h.multi.audio.value)
        assertTrue(h.player(a.key).silenced)
        h.multi.setChat(c.key)
        assertEquals(c.key, h.multi.chat.value)
        val playerB = h.player(b.key)
        h.multi.remove(b.key)
        h.until { playerB.released }
        assertEquals(setOf(a.key), h.multi.audio.value, "saiu a única com som: a primeira que sobrou herda")
        assertFalse(h.player(a.key).silenced)
        assertEquals(listOf(a.key, c.key), h.multi.medias.map { it.key })
        h.multi.setAudio(emptySet())
        assertTrue(h.player(a.key).silenced && h.player(c.key).silenced, "sem som em nenhuma")
        h.multi.toggleAudio(c.key); h.multi.toggleAudio(a.key)
        assertEquals(setOf(a.key, c.key), h.multi.audio.value)
        h.multi.toggleFocus(c.key); assertEquals(c.key, h.multi.focus.value)
        h.multi.toggleFocus(c.key); assertNull(h.multi.focus.value, "destacar de novo volta à grade")
        h.multi.remove(c.key)
        assertEquals(setOf(a.key), h.multi.audio.value, "saiu uma das que tinham som: as outras continuam")
    }

    @Test
    fun abrir_a_tela_toca_tudo_fechar_para_tudo_e_a_qualidade_cai_com_mais_lives() = runBlocking {
        val h = Harness()
        val a = live(Platform.Twitch, "a"); val b = live(Platform.Kick, "b")
        h.multi.add(a); h.multi.add(b)
        assertTrue(h.players.all { it.played.isEmpty() }, "fora da tela nada toca")
        h.multi.start()
        h.ready()
        assertEquals(1, h.beforeStarts, "avisou o app antes de tocar (o player principal para)")
        for (p in h.players) assertTrue(p.played.single().videoUrl.endsWith("/720.m3u8"), "duas lives: até 720p (${p.played.single().videoUrl})")
        assertTrue(h.players.all { it.played.single().live })
        val c = live(Platform.YouTube, "c")
        h.multi.add(c)
        h.ready()
        assertTrue(h.player(c.key).played.single().videoUrl.endsWith("/480.m3u8"), "com três, a nova abre em 480p")
        h.multi.stop()
        h.until { h.players.all { it.stops == 1 } }
        assertTrue(h.multi.tiles.value.all { it.load.value is LoadState.Idle })
        h.multi.start()
        h.ready()
        assertEquals(2, h.player(a.key).played.size, "voltar toca de novo")
        assertTrue(h.player(a.key).played.last().videoUrl.endsWith("/480.m3u8"), "agora com três, todas em 480p")
    }

    @Test
    fun a_lista_fica_guardada_e_volta_na_proxima_vez() {
        val h = Harness()
        val a = live(Platform.Twitch, "a"); val b = live(Platform.Kick, "b")
        h.multi.add(a); h.multi.add(b)
        assertEquals(listOf(a.key, b.key), h.data.multiStreams.value.map { it.key })
        h.multi.remove(a.key)
        assertEquals(listOf(b.key), h.data.multiStreams.value.map { it.key })
        h.multi.add(a)
        // Outro Multi com os mesmos dados (o app abriu de novo): as lives voltam, com som na primeira.
        val again = MultiStream(h.scope, h.data, { FakePlayer2() }, ::fakeTileResolve)
        again.start()
        assertEquals(listOf(b.key, a.key), again.medias.map { it.key })
        assertEquals(setOf(b.key), again.audio.value)
        again.clear()
        assertTrue(again.medias.isEmpty() && h.data.multiStreams.value.isEmpty(), "limpar esvazia e esquece")
    }

    @Test
    fun a_grade_e_o_limite_de_qualidade_acompanham_quantas_lives_ha() {
        assertEquals(1 to 1, MultiStream.gridFor(1))
        assertEquals(2 to 1, MultiStream.gridFor(2))
        assertEquals(2 to 2, MultiStream.gridFor(3)); assertEquals(2 to 2, MultiStream.gridFor(4))
        assertEquals(3 to 2, MultiStream.gridFor(5)); assertEquals(3 to 2, MultiStream.gridFor(6))
        assertEquals(3 to 3, MultiStream.gridFor(7)); assertEquals(3 to 3, MultiStream.gridFor(9))
        assertEquals(listOf(1080, 720, 480, 480, 360, 360), listOf(1, 2, 3, 4, 5, 9).map { MultiStream.tileQualityCap(it) })
    }

    @Test
    fun links_do_multitwitch_e_do_multikick_e_nomes_de_canal() {
        val t = parseLink("https://www.multitwitch.tv/razah/Rubini/razah") as LinkTarget.Multi
        assertEquals(listOf("Twitch:razah", "Twitch:rubini"), t.medias.map { it.key }, "cada trecho é um canal, sem repetir, em minúsculas")
        assertTrue(t.medias.all { it.isLive })
        val k = parseLink("multikick.com/brabox/andersonneiff") as LinkTarget.Multi
        assertEquals(listOf("Kick:brabox", "Kick:andersonneiff"), k.medias.map { it.key })
        assertNull(parseLink("https://multitwitch.tv/"), "sem canal não é nada")
        assertEquals("razah", liveFromName("@Razah ", Platform.Twitch)?.id)
        assertNull(liveFromName("directory", Platform.Twitch), "páginas da Twitch não são canais")
        assertNull(liveFromName("x", Platform.Kick), "nome curto demais")
        assertNull(liveFromName("canal", Platform.YouTube), "no YouTube só por link")
    }

    @Test
    fun no_app_o_link_do_multitwitch_abre_o_multi_e_o_campo_aceita_link_ou_nome() {
        val (app, _) = newTestApp()
        assertTrue(app.openLink("https://multitwitch.tv/alpha/bravo"))
        assertEquals(Route.Multi, app.nav.current)
        assertEquals(listOf("Twitch:alpha", "Twitch:bravo"), app.multi.medias.map { it.key })
        assertFalse(app.addToMulti(live(Platform.Twitch, "alpha")), "repetida não entra")
        assertTrue(app.ui.toast!!.contains("já está"))
        assertTrue(app.addToMulti("https://kick.com/charlie", Platform.Twitch), "um link vale para qualquer plataforma")
        assertTrue(app.addToMulti("Delta", Platform.Kick), "um nome vale para a plataforma escolhida")
        assertEquals(listOf("Twitch:alpha", "Twitch:bravo", "Kick:charlie", "Kick:delta"), app.multi.medias.map { it.key }, "plataformas misturadas")
        assertFalse(app.addToMulti("nome inválido!!", Platform.Twitch))
        assertFalse(app.addToMulti("", Platform.Twitch))
        for (i in 5..9) app.multi.add(live(Platform.Twitch, "t$i"))
        assertFalse(app.addToMulti(live(Platform.Twitch, "demais")))
        assertTrue(app.ui.toast!!.contains("comporta"))
    }
}
