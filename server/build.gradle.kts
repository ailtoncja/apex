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

dependencies {
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
    // Banco embutido, só para desenvolvimento e testes (em produção vale a DATABASE_URL).
    implementation(libs.embedded.postgres)
    runtimeOnly(libs.embedded.postgres.windows)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}
