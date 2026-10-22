package app.apex.player

import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import java.io.File

/**
 * O VLC que vai dentro do Apex (em `app\resources\vlc`, posto lá pelo Gradle em prepareBundledVlc): quem não tem o VLC instalado assiste do
 * mesmo jeito, e quem tem usa sempre a versão testada com o app. Sem ele (testes, uma pasta mexida), vale o VLC instalado no PC.
 */
object BundledVlc {
    /** A pasta do VLC embutido, ou `null` se o app foi aberto sem ela. `APEX_SYSTEM_VLC=1` (só para ensaio) usa o VLC instalado no PC. */
    val dir: File? by lazy {
        if (System.getenv("APEX_SYSTEM_VLC") == "1") return@lazy null
        System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
            ?.takeIf { File(it, "libvlc.dll").isFile && File(it, "libvlccore.dll").isFile && File(it, "plugins").isDirectory }
    }
}

/**
 * Faz o vlcj procurar o libVLC primeiro na pasta embutida (prioridade acima de todas as do vlcj, que vão de -4 a 1).
 * Registrado em META-INF/services; o vlcj também aponta o VLC_PLUGIN_PATH para a pasta `plugins` dela.
 */
class BundledVlcDiscovery : DiscoveryDirectoryProvider {
    override fun priority() = 10
    override fun supported() = BundledVlc.dir != null
    override fun directories(): Array<String> = listOfNotNull(BundledVlc.dir?.absolutePath).toTypedArray()
}
