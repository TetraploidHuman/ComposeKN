pluginManagement {
    val localMaven = providers.gradleProperty("composekn.maven.local")
        .orElse(providers.environmentVariable("COMPOSEKN_MAVEN_LOCAL"))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val cknVer = providers.gradleProperty("composekn.version").orElse("0.5.66").get()
    repositories {
        if (localMaven != null) {
            maven { url = uri(localMaven) }
        }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.composekn.")) {
                useVersion(cknVer)
            }
        }
    }
}

plugins {
    id("com.composekn.settings")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "consumer-smoke"
