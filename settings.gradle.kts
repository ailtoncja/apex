rootProject.name = "apex"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// No build do servidor (Docker) o app de desktop não é montado: build mais rápido e com menos memória.
if (System.getenv("APEX_SERVER_ONLY") != "true") include(":composeApp")
include(":shared")
include(":server")
