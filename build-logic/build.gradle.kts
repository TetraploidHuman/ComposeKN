plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.composekn"
version = providers.gradleProperty("composekn.version").orElse("0.5.65").get()

dependencies {
    implementation(gradleApi())
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
}

gradlePlugin {
    plugins {
        create("konanLinkWorkaround") {
            id = "com.composekn.konan-link-workaround"
            implementationClass = "com.composekn.gradle.KonanLinkWorkaroundPlugin"
        }
        create("linuxNativeLinker") {
            id = "com.composekn.linux-native-linker"
            implementationClass = "com.composekn.gradle.LinuxNativeLinkerPlugin"
        }
        create("windowsNativeLinker") {
            id = "com.composekn.windows-native-linker"
            implementationClass = "com.composekn.gradle.WindowsNativeLinkerPlugin"
        }
        create("composeKnResources") {
            id = "com.composekn.resources"
            implementationClass = "com.composekn.gradle.ComposeKnResourcesPlugin"
        }
        create("composeKnHost") {
            id = "com.composekn.host"
            implementationClass = "com.composekn.gradle.ComposeKnHostPlugin"
        }
        create("composeKnPublish") {
            id = "com.composekn.publish"
            implementationClass = "com.composekn.gradle.ComposeKnPublishPlugin"
        }
        create("composeKnSettings") {
            id = "com.composekn.settings"
            implementationClass = "com.composekn.gradle.ComposeKnSettingsPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "ComposeKnLocal"
            url = uri(rootProject.layout.projectDirectory.dir("../build/maven-repo"))
        }
        val ghToken = System.getenv("GITHUB_TOKEN")
        val wantGh = providers.gradleProperty("composekn.publish.github").orNull == "true"
        if (wantGh && !ghToken.isNullOrBlank()) {
            val owner = System.getenv("GITHUB_REPOSITORY_OWNER") ?: "TetraploidHuman"
            val repo = System.getenv("GITHUB_REPOSITORY")?.substringAfter('/') ?: "ComposeKN"
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/$owner/$repo")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: "github"
                    password = ghToken
                }
            }
        }
    }
}
