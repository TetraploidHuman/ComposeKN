pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "compose-core"

include(
    ":ui-util",
    ":ui-geometry",
    ":ui-unit",
    ":ui-graphics",
    ":ui-text",
    ":ui-backhandler",
    ":lifecycle-viewmodel-compose",
    ":ui",
    ":animation-core",
    ":foundation-layout",
    ":animation",
    ":foundation",
    ":material-ripple",
    ":material3",
)
