package app.apex.server.dev

import app.apex.server.DbConnection
import app.apex.server.DevDatabase
import app.apex.server.DevDatabaseProvider
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File

/** PostgreSQL embutido (zonky) para o modo desenvolvimento e os testes. Não vai para a imagem de produção. */
class EmbeddedPostgresProvider : DevDatabaseProvider {
    override fun start(dataDir: File): DevDatabase {
        dataDir.mkdirs()
        val pg = EmbeddedPostgres.builder()
            .setDataDirectory(dataDir)
            .setCleanDataDirectory(false)
            .start()
        return DevDatabase(DbConnection(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"), pg)
    }
}
