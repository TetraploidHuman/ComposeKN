# 消费 ComposeKN

从 **clone + includeBuild 样板** 迁到 **catalog 改三行 + 宿主插件**。

当前矩阵（锁死，见 `gradle.properties` / `vendor/compose-core.local/VERSIONS`）：

| 分量 | 版本 | 说明 |
|------|------|------|
| ComposeKN | **0.5.54** | `com.composekn:compose-kn-*` |
| Kotlin | **2.4.0** | 与宿主一致 |
| Compose **UI 源码** | **1.12.1** | 唯一 UI 基线 |
| Maven `compose_deps` | 1.11.1 | 仅 runtime 等未被 substitution 顶掉的坐标；**不要**当 UI 版本 |

> 禁止再「源码 1.12 + 口头/文档写 Maven UI 1.11」。对消费者只宣传 **UI = 1.12.1（substitution / 自建包）**。

---

## A. 最快路径（推荐）：catalog 三行 + 宿主插件

### 1. 版本目录

把本仓库的 [`gradle/composekn.versions.toml`](gradle/composekn.versions.toml) 拷进你的工程，或在 `settings.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    versionCatalogs {
        create("composekn") {
            from(files("gradle/composekn.versions.toml"))
        }
    }
    repositories {
        mavenCentral()
        // 本地发布：./gradlew :compose-kn-bom:publish
        maven { url = uri("<path-to-ComposeKN>/build/maven-repo") }
        // 或你的私服
    }
}
```

### 2. settings：顶掉官方 UI / resources（必须）

官方 `components-resources` **没有** linuxX64/mingwX64。UI 要用 ComposeKN 打过补丁的 1.12.1：

```kotlin
// 开发期：composite（与本仓库相同）
includeBuild("<ComposeKN>/vendor/skiko/skiko") {
    dependencySubstitution {
        substitute(module("org.jetbrains.skiko:skiko")).using(project(":"))
    }
}
includeBuild("<ComposeKN>/vendor/compose-core") {
    dependencySubstitution {
        substitute(module("org.jetbrains.compose.ui:ui-util")).using(project(":ui-util"))
        substitute(module("org.jetbrains.compose.ui:ui-geometry")).using(project(":ui-geometry"))
        substitute(module("org.jetbrains.compose.ui:ui-unit")).using(project(":ui-unit"))
        substitute(module("org.jetbrains.compose.ui:ui-graphics")).using(project(":ui-graphics"))
        substitute(module("org.jetbrains.compose.ui:ui-text")).using(project(":ui-text"))
        substitute(module("org.jetbrains.compose.ui:ui")).using(project(":ui"))
        substitute(module("org.jetbrains.compose.foundation:foundation")).using(project(":foundation"))
        substitute(module("org.jetbrains.compose.material3:material3")).using(project(":material3"))
    }
}

// resources：官方 components-resources → ComposeKN
dependencyResolutionManagement {
    // 若已 publish compose-kn-resources：
    // components { all { ... } } 或
}
// 推荐显式：
includeBuild("<ComposeKN>") // 仅当以 composite 消费整仓时
// 或 Maven：
// substitute(module("org.jetbrains.compose.components:components-resources"))
//   .using(module("com.composekn:compose-kn-resources:0.5.54"))
```

插件解析（`pluginManagement`）需能解析 `com.composekn.host`：

```kotlin
pluginManagement {
    includeBuild("<ComposeKN>/build-logic") // 开发期
    // 或 repositories + 已发布的 com.composekn:…gradle.plugin
}
```

### 3. 模块 build.gradle.kts（三行级）

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.composekn.host")       // ← linker + 依赖宿主 + entry 自动 registerBackend
    id("com.composekn.resources")  // ← 可选：Res.font / string / drawable
}

kotlin {
    linuxX64 { binaries.executable { entryPoint = "main.main" } }
    // 和/或 mingwX64 { binaries.executable { entryPoint = "main.main" } }
}

dependencies {
    // host 插件已拉 compose-kn-linux / windows
    implementation(composekn.libraries.composekn.resources) // 若用资源
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
}
```

`main` 里**不必**再写 `registerComposeKn*Backend()`（host 会 wrap entryPoint）。

### 4. BOM（可选）

```kotlin
dependencies {
    api(platform("com.composekn:compose-kn-bom:0.5.54"))
    api("com.composekn:compose-kn-linux")
    api("com.composekn:compose-kn-resources")
}
```

发布到本地仓库：

```bash
./gradlew :compose-kn-bom:publish :compose-kn-linux:publish
# :compose-kn-windows:publish :compose-kn-resources:publish
# → build/maven-repo
```

---

## B. 旧路径：clone + includeBuild（仍可用）

```kotlin
includeBuild("../ComposeKN/vendor/skiko/skiko") { … }
includeBuild("../ComposeKN/vendor/compose-core") { … }
include(":compose-kn-linux") // 若用 includeBuild 整仓
```

手动：

```kotlin
id("com.composekn.linux-native-linker")
implementation(project(":compose-kn-linux"))
// main: registerComposeKnLinuxBackend()
```

请尽快迁到 **A**。

---

## 坐标一览

| 坐标 | 内容 |
|------|------|
| `com.composekn:compose-kn-bom` | 平台 BOM（约束 linux/windows/resources） |
| `com.composekn:compose-kn-linux` | Wayland 宿主 |
| `com.composekn:compose-kn-windows` | Win32 宿主 |
| `com.composekn:compose-kn-resources` | `Res.font` / `string` / `drawable` + 官方包名兼容层 |
| （UI） | **不**走 Maven Central 的 1.11 UI；用 substitution → compose-core **1.12.1** |

`compose-kn-resources` 提供 `org.jetbrains.compose.resources` 包下的 typealias / `Font(...)`，便于从官方 import 迁过来。

---

## 从 Compose Desktop（AWT / jpackage）必改清单

这些在 KN 上**没有**同等物或必须换写法：

| Desktop / JVM | ComposeKN |
|---------------|-----------|
| `androidx.compose.ui.awt.ComposeWindow` / `ComposeDialog` | `application { Window { } }` / `DialogWindow`（原生后端） |
| `SwingUtilities` / 任意 AWT/Swing 互操作 | 删除或 `#if` 掉；KN 无 AWT |
| `java.io.File` + AWT `FileDialog` | `androidx.compose.ui.window.FileDialog`（portal / comdlg32） |
| `Clipboard` AWT | `LocalClipboard` / `ClipEntry`（ComposeKN 已接富文本） |
| `java.awt.Desktop` / `Taskbar` | 窗口 API：`setTaskbarProgress` 等；外链用平台 API 自写 |
| `jpackage` / `jlink` 打包 | **不适用**。Windows：`scripts/package-windows-release.sh` 出 zip（单 exe + 可选 `composeResources/`）。Linux：分发 `.kexe` + `lib/`（libcrypt）+ `composeResources/` |
| `System.setProperty("skiko.*")` JVM | 环境变量：`COMPOSEKN_RENDER_API` / `COMPOSEKN_RESOURCES` 等 |
| `compose.desktop` Gradle 插件 | 去掉；改 `com.composekn.host` + KMP native targets |
| `org.jetbrains.compose.resources` 官方组件（无 native） | `com.composekn.resources` 或 substitution → `compose-kn-resources` |
| `Window` 里 `undecorated` + Swing 拖窗 | `WindowDraggableArea` → `beginMove` |
| 全局 `main` + `application` 前不登记后端 | host 插件自动 register；或手写 `registerComposeKn*Backend()` |

---

## 资源布局

```
src/commonMain/composeResources/
  font/*.ttf|otf          → Res.font.*
  drawable/*              → Res.drawable.*
  values/strings.xml      → Res.string.*
```

运行时查找：`COMPOSEKN_RESOURCES` → exe 旁 `composeResources/` → `developmentPath`。

---

## 自检

```bash
# 本仓库发布到 build/maven-repo
nix-shell ./shell.nix --run './gradlew :compose-kn-bom:publish :compose-kn-resources:publishKotlinMultiplatformPublicationToComposeKnLocalRepository :compose-kn-linux:publishKotlinMultiplatformPublicationToComposeKnLocalRepository'
```

（具体 publication 名称以 `./gradlew :compose-kn-linux:tasks --group=publishing` 为准。）
