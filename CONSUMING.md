# 消费 ComposeKN

目标：**catalog + `com.composekn.settings` + `com.composekn.host`**，尽量不再 clone / includeBuild。

当前矩阵（`gradle.properties` / `vendor/compose-core.local/VERSIONS`）：

| 分量 | 版本 | 说明 |
|------|------|------|
| ComposeKN | **0.5.55** | `com.composekn:compose-kn-*` / `com.composekn:skiko` |
| Kotlin | **2.4.0** | 与宿主一致 |
| Compose **UI** | **1.12.1** | 发布坐标 `com.composekn.compose:*:1.12.1-ckn.0.5.55` |
| Maven `compose_deps` | 1.11.1 | 仅 runtime 等未顶掉坐标；**不是** UI 版本 |

### 仓库怎么拿（按优先级）

1. **Release Maven zip（推荐，不依赖 Packages / Actions Billing）**

```bash
# 在任意目录；私有仓需 GITHUB_TOKEN
./scripts/fetch-composekn-maven.sh ~/composekn-m2
# 或：curl 拉 releases/download 后 unzip（公开仓）
```

```kotlin
maven { url = uri("${System.getProperty("user.home")}/composekn-m2") }
```

资产名：`composekn-maven-<ver>.zip`（挂在 tag `v<ver>`，由  
`./scripts/publish-composekn-packages.sh --release` 上传）。

2. **GitHub Packages**（需账户 Billing / Packages spending limit 正常）

```
https://maven.pkg.github.com/TetraploidHuman/ComposeKN
```

3. **本机 publish**：`./scripts/publish-composekn-packages.sh` → `build/maven-repo`。

---

## A. 推荐：settings 插件 + host（无 includeBuild）

### 1. `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/TetraploidHuman/ComposeKN")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
            }
        }
    }
    // 开发期也可用：includeBuild("<ComposeKN>/build-logic")
}

plugins {
    id("com.composekn.settings") version "0.5.55"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // settings 插件已加 GitHub Packages；本地调试可再加：
        // maven { url = uri("<ComposeKN>/build/maven-repo") }
    }
    versionCatalogs {
        create("composekn") {
            from("com.composekn:composekn-catalog:0.5.55") // 或拷贝 gradle/composekn.versions.toml
        }
    }
}
```

`com.composekn.settings` 会：

1. 加入 GitHub Packages 仓库  
2. 把 `org.jetbrains.compose.ui|foundation|material3|…` → `com.composekn.compose:*:1.12.1-ckn.<ver>`  
3. `org.jetbrains.skiko:skiko` → `com.composekn:skiko:<ver>`  
4. `components-resources` → `compose-kn-resources`

仍用 includeBuild 时：`-Pcomposekn.settings.skipSubstitution=true`。

### 2. 模块

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.composekn.host")
    id("com.composekn.resources") // 可选
}

kotlin {
    linuxX64 { binaries.executable { entryPoint = "main.main" } }
    mingwX64 { binaries.executable { entryPoint = "main.main" } }
}
```

`main` **不必**再写：

- `registerComposeKn*Backend()` — host entry wrap / register 已做  
- `initLinuxMainThread()` / `initWindowsMainThread()` — 已收进 `registerComposeKn*Backend()`（幂等）

### 3. 凭证

`~/.gradle/gradle.properties` 或环境变量：

```
gpr.user=YOUR_GH_USERNAME
gpr.token=ghp_…   # packages:read
```

---

## B. 开发期：clone + includeBuild（本仓库自己）

与根 `settings.gradle.kts` 相同：顶掉 skiko + compose-core 源码。适合改宿主 / UI 补丁。

---

## 坐标一览

| 坐标 | 内容 |
|------|------|
| `com.composekn:compose-kn-bom` | 约束 linux / windows / resources |
| `com.composekn:compose-kn-linux` | Wayland 宿主 |
| `com.composekn:compose-kn-windows` | Win32 宿主 |
| `com.composekn:compose-kn-resources` | Res.font / string / drawable + 官方包名兼容层 |
| `com.composekn:skiko` | KN 用 Skiko（linuxX64 / mingwX64） |
| `com.composekn.compose:ui` 等 | 打过 ComposeKN 补丁的 UI **1.12.1** |

**不是** Maven Central 上的 `org.jetbrains.compose.ui:ui:1.11.1`。

---

## 发布（维护者）

```bash
# 本地
./scripts/publish-composekn-packages.sh
./scripts/publish-composekn-packages.sh --compose-ui --skiko

# GitHub Packages（需 GITHUB_TOKEN）
GITHUB_TOKEN=… GITHUB_ACTOR=… ./scripts/publish-composekn-packages.sh --github --compose-ui --skiko
```

CI：推送 tag `v*` → [`.github/workflows/publish-github-packages.yml`](.github/workflows/publish-github-packages.yml)。

---

## 从 Compose Desktop（AWT / jpackage）必改清单

| Desktop / JVM | ComposeKN |
|---------------|-----------|
| `ComposeWindow` / AWT | `application { Window { } }` / `DialogWindow` |
| Swing / AWT 互操作 | 删除 |
| AWT `FileDialog` | `androidx.compose.ui.window.FileDialog` |
| AWT Clipboard | `LocalClipboard` / `ClipEntry` |
| `jpackage` / `jlink` | Windows zip / Linux `.kexe`+`lib/`+`composeResources/` |
| `compose.desktop` 插件 | `com.composekn.host` + KMP native |
| 官方 `components-resources` | `compose-kn-resources` / settings 顶掉 |
| 手写 `init*MainThread` + `registerBackend` | **已由 host / register 覆盖** |

---

## 资源布局

```
src/commonMain/composeResources/
  font/*.ttf|otf
  drawable/*
  values/strings.xml
```
