// 禁用所有工程（含 includeBuild）的 *KotlinMetadata，避开 KN 2.4 @OptionalExpectation。
// 用法：./gradlew --init-script scripts/disable-kotlin-metadata.init.gradle.kts …
gradle.beforeProject { p ->
    p.tasks.configureEach { t ->
        if (t.name.contains("KotlinMetadata")) {
            t.enabled = false
        }
    }
}
