import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

// A versão do app: uma só fonte. Vai para o instalador (packageVersion) e para o código (BuildInfo.VERSION), que usa para saber se há atualização.
val appVersion = "1.0.6"

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
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.apex.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

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
