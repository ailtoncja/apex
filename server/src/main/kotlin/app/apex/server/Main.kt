package app.apex.server

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    val log = LoggerFactory.getLogger("apex")
    val config = try {
        ServerConfig.load()
    } catch (e: ConfigException) {
        System.err.println("Configuração inválida: ${e.message}")
        exitProcess(1)
    }

    val db = try {
        Database.open(config)
    } catch (e: ConfigException) {
        System.err.println("Configuração inválida: ${e.message}")
        exitProcess(1)
    }
    val mailer = mailerFor(config)

    if (config.isProduction && config.contactEmail == null) {
        log.warn("CONTACT_EMAIL não está definido: a Política de Privacidade e os Termos ficam sem e-mail de contato. Defina antes de abrir ao público.")
    }
    if (config.isProduction && mailer is LogMailer) {
        log.warn(
            "**** ATENÇÃO: nenhum provedor de e-mail configurado (RESEND_API_KEY ou MAIL_WEBHOOK_URL + MAIL_WEBHOOK_SECRET). " +
                "Os e-mails de confirmar conta e redefinir senha NÃO serão enviados, e os links não vão para o log. ****",
        )
    }
    if (!config.isProduction) {
        log.warn("Modo desenvolvimento: PostgreSQL embutido em {} e e-mails só no log. Defina DATABASE_URL para usar um banco de verdade.", config.devDataDir)
    }
    val host = config.bindHost
    log.info("Apex Server em http://{}:{}", host, config.port)

    startServer(config, db, mailer, host, config.port).start(wait = true)
}

/** Monta o servidor (ainda parado). Com `port = 0`, o sistema escolhe uma porta livre. */
fun startServer(
    config: ServerConfig, db: Database, mailer: Mailer, host: String, port: Int, syncOverlapSeconds: Long = 5,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(Netty, port = port, host = host) { apexModule(config, db, mailer, syncOverlapSeconds) }
