plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "app.apex.server.MainKt"
    // Em container: usa até 70% da memória disponível (o Argon2 das senhas gasta ~19 MB por login simultâneo).
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=70", "-Dfile.encoding=UTF-8", "-Djava.net.preferIPv4Stack=true")
}

// Conjunto "dev": o banco embutido do modo desenvolvimento. Enxerga o código principal, mas o principal não depende dele.
sourceSets {
    create("dev") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}
// As dependências do principal e as do "dev" são resolvidas juntas (uma versão só de cada biblioteca: o zonky traz o slf4j 1.7, e o
// logback precisa do 2.x; resolvidas em separado, as duas iriam para o classpath e o log sumiria).
configurations["devImplementation"].extendsFrom(configurations["implementation"])
configurations["devRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    // O java-jwt traz o Jackson 2.22.0, com falhas conhecidas de negação de serviço ao ler JSON (um token forjado chega ao parser antes
    // da verificação da assinatura). As versões 2.22.3 corrigem.
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:2.22.3") { because("GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89") }
        implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3") { because("várias falhas corrigidas até a 2.22.3") }
    }
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.bouncycastle)
    implementation(libs.logback)

    // Banco embutido, só para desenvolvimento (`:server:run`, usado pelo Servidor.cmd) e testes. Fica no conjunto "dev", fora do
    // `installDist`: a imagem Docker de produção não leva o PostgreSQL embutido (lá vale a DATABASE_URL).
    "devImplementation"(libs.embedded.postgres)
    "devRuntimeOnly"(libs.embedded.postgres.windows)

    testImplementation(sourceSets["dev"].output)
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.embedded.postgres.windows)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

// O `run` (desenvolvimento) enxerga o conjunto "dev"; o `installDist`/Docker usa só o principal.
tasks.named<JavaExec>("run") {
    classpath = sourceSets["dev"].runtimeClasspath
}

// Quem usa este projeto como dependência (só os testes de ponta a ponta do app, em composeApp/desktopTest) também recebe o banco
// embutido. Isso vale só para o `runtimeElements` (o que outros projetos do build enxergam); o `installDist` da imagem Docker monta o
// pacote a partir do `runtimeClasspath`, que continua sem ele.
val devJar by tasks.registering(Jar::class) {
    archiveClassifier = "dev"
    from(sourceSets["dev"].output)
}
configurations.named("runtimeElements") {
    extendsFrom(configurations["devImplementation"], configurations["devRuntimeOnly"])
    outgoing.artifact(devJar)
}
