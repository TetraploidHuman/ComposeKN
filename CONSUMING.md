# 消费 ComposeKN

目标：**catalog + `com.composekn.settings` + `com.composekn.host`**，尽量不再 clone / includeBuild。

当前矩阵（`gradle.properties`）：

| 分量 | 版本 | 说明 |
|------|------|------|
| ComposeKN | **0.5.66** | `com.composekn:compose-kn-*` / `com.composekn:skiko` |
| Kotlin | **2.4.0** | 与宿主一致 |
| Compose **UI** | **1.12.1** | 发布坐标 `com.composekn.compose:*:1.12.1-ckn.0.5.66` |
| Maven `compose_deps` | 1.11.1 | 仅 runtime 等未顶掉坐标；**不是** UI 版本 |

### 仓库怎么拿（按优先级）

1. **Release Maven zip（推荐，不依赖 Packages Billing）**

```bash
./scripts/fetch-composekn-maven.sh ~/composekn-m2
# 冒烟：./scripts/smoke-composekn-consumer.sh ~/composekn-m2
```

2. **GitHub Packages**（需 Billing / Packages spending limit）

```
https://maven.pkg.github.com/TetraploidHuman/ComposeKN
```

3. **本机 publish**：`./scripts/publish-composekn-packages.sh` → `build/maven-repo`。

---

## A. 推荐：Release zip + settings + host

### 1. `gradle.properties`

```properties
composekn.version=0.5.66
composekn.compose.ui.version=1.12.1-ckn.0.5.66
composekn.maven.local=/home/YOU/composekn-m2
composekn.settings.skipGithubPackages=true
```

### 2. `settings.gradle.kts`

```kotlin
pluginManagement {
    val local = providers.gradleProperty("composekn.maven.local").get()
    val ver = providers.gradleProperty("composekn.version").get()
    repositories {
        maven { url = uri(local) } // 插件 + 坐标都从 zip 解压目录来
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.composekn.")) {
                useVersion(ver)
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
        // settings 已加 composekn.maven.local；若要用 Packages 则去掉 skipGithubPackages
    }
    versionCatalogs {
        create("composekn") {
            from("com.composekn:composekn-catalog:0.5.66")
        }
    }
}
```

`com.composekn.settings` 会：

1. 若设了 `composekn.maven.local` → 加入该本地仓库（pluginManagement + dependencyResolution）  
2. 默认加 GitHub Packages；**纯 zip 时设 `composekn.settings.skipGithubPackages=true`**  
3. 官方 Compose / Skiko / resources → ComposeKN 坐标；**linuxX64/mingwX64 configuration 顶到平台 artifact**（避开合成根 available-at）  
4. includeBuild 时：`-Pcomposekn.settings.skipSubstitution=true`

### 3. 模块

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.composekn.host")
    id("com.composekn.resources") // 可选
}

composeKnHost {
    useProjectDependencies.set(false) // 消费 Maven，非本仓库 project()
}

kotlin {
    linuxX64 { binaries.executable { entryPoint = "main.main" } }
    mingwX64 { binaries.executable { entryPoint = "main.main" } }
}
```

`main` **不必**再写 `registerComposeKn*Backend()` / `init*MainThread()`（host 已覆盖）。

### 4. 仅用 Packages 时的凭证

```
gpr.user=YOUR_GH_USERNAME
gpr.token=ghp_…   # packages:read
# 并去掉 composekn.settings.skipGithubPackages / 可不设 maven.local
```

---

## B. 开发期：clone + includeBuild

与根 `settings.gradle.kts` 相同：顶掉 skiko + compose-core 源码。

---

## 坐标一览

| 坐标 | 内容 |
|------|------|
| `com.composekn:compose-kn-bom` | 约束 linux / windows / resources |
| `com.composekn:compose-kn-linux` | Wayland 宿主（合成根） |
| `com.composekn:compose-kn-windows` | Win32 宿主 |
| `com.composekn:compose-kn-resources` | Res.font / string / drawable |
| `com.composekn:skiko` | KN Skiko（合成根；解析时按 target 顶平台） |
| `com.composekn:composekn-catalog` | version catalog |
| `com.composekn.settings` 等 | host / resources / settings 插件 |
| `com.composekn.compose:ui` 等 | UI **1.12.1**（合成根 + 平台） |

---

## 发布（维护者）

```bash
GITHUB_TOKEN=… GITHUB_ACTOR=… \
  ./scripts/publish-composekn-packages.sh --github --skiko --compose-ui --plugins --release

# 消费冒烟
./scripts/smoke-composekn-consumer.sh
```

CI：tag `v*` → publish workflow（含 merge 断言 + consumer-smoke）。
