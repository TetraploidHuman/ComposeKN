plugins {
    `java-platform`
    id("com.composekn.publish")
}

group = "com.composekn"
version = providers.gradleProperty("composekn.version").getOrElse("0.5.57")

dependencies {
    constraints {
        api("com.composekn:compose-kn-linux:$version")
        api("com.composekn:compose-kn-windows:$version")
        api("com.composekn:compose-kn-resources:$version")
    }
}

// 说明性元数据：UI 栈版本不在 BOM 里发布（走源码 substitution / 自建仓库），
// 避免再出现「宿主 0.5.x + Maven compose UI 1.11」混用。
