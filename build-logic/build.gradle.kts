plugins {
    `kotlin-dsl`
}

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
    }
}
