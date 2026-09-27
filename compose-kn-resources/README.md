# ComposeKN Resources

Kotlin/Native（**linuxX64** / **mingwX64**）上的资源子集，用法对齐官方
`compose.resources` 的 **`Res.font.*` + `Font(Res.font.xxx)`**。

> 官方 `components-resources` 目前**没有** linuxX64/mingwX64 变体；本模块是 ComposeKN
> 的到位替代，不是「换 Maven 坐标」。

## 能力

| API | 作用 |
| --- | --- |
| `Font(Res.font.xxx)` | 从生成的 `FontResource` 加载 → Skia `makeFromData` |
| `Font(identity, bytes)` | 直接喂 `ByteArray` |
| `FontFromFile(path)` | 从路径读文件（native 无 JVM `Font(File)`） |
| `fontFamilyOf(...)` | 组 `FontFamily` |

## 布局（与官方一致）

```
src/commonMain/composeResources/font/
  noto_sans_regular.otf
  jbmono_regular.ttf
  …
```

或 Gradle：

```kotlin
plugins { id("com.composekn.resources") }

composeKnResources {
    packageName.set("com.example.resources")
    extraFontDirs.add("/path/to/other/font/dir")
}
```

生成 `Res.font.<文件名去扩展名>`；链接后把字体 copy 到 exe 旁：

```
<exeDir>/composeResources/font/*.ttf|otf
```

运行时查找顺序：`COMPOSEKN_RESOURCES` → exe 旁 `composeResources/` → `fonts/` →
构建期 `developmentPath`（未 copy 也能在开发机跑）。

## 依赖

```kotlin
implementation(project(":compose-kn-resources"))
```

## 与 LumiCode

`samples/lumicode` 的 `InstallArchiveFonts` 已接同一套 `Res.font`（字体来自
LumiCodeNext `composeResources/font`），Desktop 的 `org.jetbrains.compose.resources.Font`
在 KN 上换成 `com.composekn.resources.Font` 即可共用资源文件。
