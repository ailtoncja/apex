package app.apex

import app.apex.data.FileStore
import app.apex.data.UserData
import app.apex.model.Channel
import app.apex.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** O que o usuário guarda precisa chegar ao disco e voltar quando o app abre de novo. */
class PersistTest {
    @Test
    fun inscricoes_e_historico_sobrevivem_a_um_reinicio() = runBlocking {
        val dir = Files.createTempDirectory("apex-persist").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val data = UserData(FileStore(dir), scope)
        // Sem esperar nada: uma mudança logo na abertura também tem de ir para o disco (antes ela se perdia).
        data.addSubscriptions((1..300).map { Channel(Platform.YouTube, "UC$it", "Canal $it", "https://exemplo.com/a$it.jpg") })
        delay(1_500)
        val file = File(dir, "subscriptions.json")
        assertTrue(file.isFile && file.length() > 1_000, "subscriptions.json não foi gravado (tamanho ${file.length()})")

        // "Reinicia o app": um UserData novo na mesma pasta traz tudo de volta.
        val again = UserData(FileStore(dir), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertTrue(again.subscriptions.value.size == 300, "voltaram ${again.subscriptions.value.size} inscrições")
    }

    @Test
    fun flush_grava_na_hora_e_voltar_ao_valor_inicial_tambem_grava() = runBlocking {
        val dir = Files.createTempDirectory("apex-persist2").toFile()
        val data = UserData(FileStore(dir), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val canal = Channel(Platform.Twitch, "gaules", "Gaules")
        data.addSubscriptions(listOf(canal))
        data.flush()
        assertTrue(File(dir, "subscriptions.json").readText().contains("gaules"))

        // Seguiu e deixou de seguir: o disco tem de refletir a lista vazia, não a antiga.
        data.toggleSubscription(canal)
        data.flush()
        assertTrue(!File(dir, "subscriptions.json").readText().contains("gaules"))
        assertTrue(UserData(FileStore(dir), CoroutineScope(SupervisorJob() + Dispatchers.Default)).subscriptions.value.isEmpty())
    }
}
