plugins {
    `version-catalog`
    `maven-publish`
    id("com.composekn.publish")
}

group = "com.composekn"
version = providers.gradleProperty("composekn.version").orElse("0.5.65").get()

catalog {
    versionCatalog {
        from(files("../gradle/composekn.versions.toml"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["versionCatalog"])
            artifactId = "composekn-catalog"
        }
    }
}
