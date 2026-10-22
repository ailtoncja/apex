import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

// A versão do app: uma só fonte. Vai para o instalador (packageVersion) e para o código (BuildInfo.VERSION), que usa para saber se há atualização.
val appVersion = "1.0.10"

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("app/apex/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText("package app.apex\n\n/** Gerado pelo Gradle a partir de appVersion em composeApp/build.gradle.kts. */\nobject BuildInfo {\n    const val VERSION = \"$version\"\n}\n")
    }
}

// O VLC que vai dentro do app (o usuário não precisa instalar): o zip oficial da VideoLAN, conferido pelo SHA-256 publicado por ela.
val vlcVersion = "3.0.24"
val vlcSha256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323"

// Plugins que o Apex nunca usa (interface do VLC, gravar/transmitir, legendas, encoders, DVD/Blu-ray, captura, rede local...): o pacote fica bem menor.
val vlcDroppedPluginDirs = setOf("gui", "visualization", "video_splitter", "lua", "stream_out", "access_output", "services_discovery", "mux", "control")
val vlcDroppedPlugins = setOf(
    "libx265_plugin.dll", "libx264_plugin.dll", "libx26410b_plugin.dll", "libaom_plugin.dll", "libqsv_plugin.dll", "liblibass_plugin.dll", "libzvbi_plugin.dll",
    "libaccess_srt_plugin.dll", "librist_plugin.dll", "libvnc_plugin.dll", "libdcp_plugin.dll", "liblibbluray_plugin.dll", "libbluray-awt-j2se-1.4.1.jar",
    "libbluray-j2se-1.4.1.jar", "libsftp_plugin.dll", "libcdda_plugin.dll", "libdshow_plugin.dll", "libdtv_plugin.dll", "librtp_plugin.dll",
    "libsid_plugin.dll", "libgme_plugin.dll", "libcaca_plugin.dll", "libremoteosd_plugin.dll", "libtaglib_plugin.dll",
)

val prepareBundledVlc = tasks.register("prepareBundledVlc") {
    val outDir = layout.buildDirectory.dir("vlc-bundle")
    val toolsDir = layout.buildDirectory.dir("vlc-tools")
    val cacheFile = File(System.getProperty("user.home"), ".apex/cache/vlc-$vlcVersion-win64.zip")
    val execOps = project.serviceOf<org.gradle.process.ExecOperations>()
    inputs.property("version", vlcVersion)
    inputs.property("sha256", vlcSha256)
    inputs.property("droppedDirs", vlcDroppedPluginDirs)
    inputs.property("dropped", vlcDroppedPlugins)
    outputs.dir(outDir)
    doLast {
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!cacheFile.isFile || sha256(cacheFile) != vlcSha256) {
            cacheFile.parentFile.mkdirs()
            val url = "https://get.videolan.org/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"
            logger.lifecycle("Baixando o VLC $vlcVersion: $url")
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val part = File(cacheFile.path + ".part")
            val response = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
            check(response.statusCode() == 200) { "Download do VLC falhou: HTTP ${response.statusCode()}" }
            val got = sha256(part)
            if (got != vlcSha256) { part.delete(); error("O VLC baixado não confere (SHA-256 $got, esperado $vlcSha256)") }
            part.copyTo(cacheFile, overwrite = true); part.delete()
        }

        val out = outDir.get().asFile
        val vlcDir = out.resolve("windows/vlc")
        val tools = toolsDir.get().asFile
        out.deleteRecursively(); tools.deleteRecursively()
        val prefix = "vlc-$vlcVersion/"
        ZipFile(cacheFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val rel = entry.name.removePrefix(prefix)
                val parts = rel.split('/')
                val keep = rel in setOf("libvlc.dll", "libvlccore.dll", "COPYING.txt") ||
                    (parts[0] == "plugins" && parts.size >= 3 && parts[1] !in vlcDroppedPluginDirs && parts.last() !in vlcDroppedPlugins && parts.last() != "plugins.dat")
                // o gerador do cache de plugins (abaixo) roda com as DLLs ao lado; ele não vai no pacote
                val tool = rel in setOf("vlc-cache-gen.exe", "libvlc.dll", "libvlccore.dll")
                for (dest in listOfNotNull(vlcDir.resolve(rel).takeIf { keep }, tools.resolve(rel).takeIf { tool })) {
                    require(!rel.contains("..")) { "Caminho inválido no zip: ${entry.name}" }
                    dest.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        // plugins.dat: o VLC lê a lista de plugins dele em vez de abrir cada DLL ao iniciar (abre mais rápido).
        val gen = execOps.exec {
            commandLine(tools.resolve("vlc-cache-gen.exe").path, vlcDir.resolve("plugins").path)
            isIgnoreExitValue = true
        }
        if (gen.exitValue != 0 || !vlcDir.resolve("plugins/plugins.dat").isFile) logger.warn("vlc-cache-gen falhou (${gen.exitValue}): o VLC embutido abre um pouco mais devagar")
    }
}

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":server"))
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.logback)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.vlcj)
                implementation(libs.sqlite.jdbc)
                // Confere a assinatura OpenPGP (SHA2-256SUMS.sig) dos lançamentos do yt-dlp antes de instalar.
                implementation(libs.bouncycastle.pgp)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.apex.MainKt"
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            // O heap usado fica em 50 a 150 MB (medido com uma live e o chat): sem teto o Java reservava até 1/4 da RAM e a memória do processo
            // passava de 800 MB. Com o teto e o G1 devolvendo o que sobra ao Windows, o Apex ocupa menos ao lado de um jogo.
            "-Xmx768m",
            "-XX:+UseG1GC",
            "-XX:G1PeriodicGCInterval=30000",
        )

        // Para empacotar com o Java dentro é preciso um JDK completo (com jpackage); o do Android Studio não tem.
        // Aponte para ele com -Papex.jdk=C:\caminho\do\jdk ou com a variável APEX_PACKAGE_JDK.
        (providers.gradleProperty("apex.jdk").orNull ?: System.getenv("APEX_PACKAGE_JDK"))?.let { javaHome = it }

        nativeDistributions {
            // .msi e .exe precisam do WiX Toolset instalado; sem ele, use :composeApp:createDistributable (pasta pronta para zipar).
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Apex"
            packageVersion = appVersion
            description = "Vídeos e lives do YouTube, Twitch e Kick num lugar só"
            vendor = "Apex"
            licenseFile.set(rootProject.file("LICENSE"))
            // O VLC embutido (ver prepareBundledVlc): vai para app\resources\vlc; o app o acha pela propriedade compose.application.resources.dir.
            appResourcesRootDir.set(layout.buildDirectory.dir("vlc-bundle"))
            // Só o que o app usa (descoberto com :composeApp:suggestRuntimeModules): o pacote fica bem menor que com o JDK todo.
            modules("java.base", "java.desktop", "java.instrument", "java.logging", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "java.management", "jdk.zipfs")
            windows {
                iconFile.set(project.file("packaging/apex.ico"))
                menuGroup = "Apex"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b0f3c2e-8a41-4d57-9c1e-2f7a5d8e1b34"
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareBundledVlc) }
