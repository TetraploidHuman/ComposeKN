# ComposeKN Resources

Kotlin/Native（**linuxX64** / **mingwX64**）资源子集，对齐官方 `compose.resources`：

- `Res.font.*` / `Font(Res.font.xxx)`
- `Res.string.*` / `stringResource(...)`
- `Res.drawable.*` / `loadDrawableBytes(...)`

并提供 **`org.jetbrains.compose.resources`** 包名兼容层（typealias），便于官方 import 迁过来。

> 官方 `components-resources` **没有** linuxX64/mingwX64。请用本模块，或在 settings 里：
> `substitute(module("org.jetbrains.compose.components:components-resources"))
>    .using(module("com.composekn:compose-kn-resources:<ver>"))`

## 布局

```
src/commonMain/composeResources/
  font/*.ttf|otf
  drawable/*
  values/strings.xml      # <string name="app_name">…</string>
```

## Gradle

```kotlin
plugins { id("com.composekn.resources") }

composeKnResources {
    packageName.set("com.example.resources")
    extraFontDirs.add("/path/to/other/font/dir")
}
```

链接后 copy 到 `<exeDir>/composeResources/{font,drawable,values}/`。

## 坐标

`com.composekn:compose-kn-resources`（见 [CONSUMING.md](../CONSUMING.md)）
