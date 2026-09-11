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
    }
}
