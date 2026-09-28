# LumiCode × ComposeKN

把隔壁 [LumiCodeNext](https://github.com/TetraploidHuman/LumiCode) 的 `commonMain` UI
接到 ComposeKN 的 Kotlin/Native 宿主上，验证 Linux（Wayland）与 Windows（mingwX64）可用性。

## 布局要求

```
~/ComposeKN/          # 本仓库
~/LumiCodeNext/       # sibling（main 或 experiment/composekn-native，功能已同步）
```

`build.gradle.kts` 通过相对路径引用：

`../../../LumiCodeNext/composeApp/src/commonMain/kotlin`

平台 actual（`platformLabel` / `InstallArchiveFonts` / `LocalPrefs`）在本 sample 的
`linuxX64Main` / `mingwX64Main`；文件键值实现见 `src/knShared`。

## 构建

```bash
# Linux .kexe
nix-shell ./shell.nix --run \
  './gradlew :samples:lumicode:linkReleaseExecutableLinuxX64Stable --no-daemon'

./scripts/run-linux-native.sh \
  samples/lumicode/build/bin/linuxX64/releaseExecutable/lumicode.kexe

# Windows（需先 fetch MinGW Skia 预编译包）
./scripts/fetch-skia-mingw.sh
# 再按 package-windows-release.sh / CI 同款方式设 -Pskiko.mingw.* 后：
./gradlew :samples:lumicode:linkReleaseExecutableMingwX64
```

## 状态

- ✅ linuxX64：`linkReleaseExecutableLinuxX64Stable` → `lumicode.kexe` (~41MB)
- ✅ mingwX64：`./samples/lumicode/build-windows.sh` → `lumicode.exe` (~39MB，含嵌入 ICU)
- ✅ `LocalPrefs`：设置写入 `~/.config/lumicode/settings`（Linux）/
  `%APPDATA%/lumicode/settings`（Windows）
