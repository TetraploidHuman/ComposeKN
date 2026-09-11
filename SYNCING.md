# Vendoring 与上游同步

本项目把两个上游以**源码 vendoring** 的方式引入，通过 Gradle composite build（`settings.gradle.kts`
里的 `includeBuild` + `dependencySubstitution`）替换 Maven 坐标：

| vendored 目录 | 上游 | 替换的坐标 |
|---|---|---|
| `vendor/compose-core/` | [JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core) | 14 个模块坐标（12 个取上游源码 + ui-backhandler、lifecycle-viewmodel-compose 整模块冻结） |
| `vendor/skiko/` | JetBrains/skiko | `org.jetbrains.skiko:skiko`（含本地 Wayland 窗口层，见 `vendor/skiko/skiko/src/linuxMain/`） |

其余 compose 构件（`runtime`、`runtime-retain`、`collection-internal`、`annotation-internal` 等）
仍从 Maven 拉取（版本见 `vendor/skiko/dependencies.toml`，当前 `compose = 1.11.1`）。

## compose-core 本地改动如何保存

全部本地改动外置在 **`vendor/compose-core.local/`**（详见其中的 README）：

- `VERSIONS` — 上游基线：tag `v1.12.0-beta01+dev4339`（⚠️ 源码基线**不是** v1.11.1）
- `templates/` — 17 个 build 文件（上游 Groovy `build.gradle` → 本地 KMP+linuxX64 Kotlin DSL）
- `overlay/` — 50 个本地新增/冻结文件（39 个 `linuxX64Main` actual、1 个 skikoMain actual、
  整模块冻结的 `ui-backhandler` 与 `lifecycle-viewmodel-compose`）
- `patches/` — 7 个对上游源码的最小 patch（`Dispatchers.Main`→`SkikoDispatchers.Main` 等）

## 同步命令

```bash
scripts/vendor-compose-core.sh              # 按 VERSIONS 基线幂等重建（验证本地改动完整性）
scripts/vendor-compose-core.sh <tag>        # 同步到指定上游 tag
scripts/vendor-compose-core.sh <tag> --refetch   # 强制真 fetch（忽略本地 ref/FETCH_HEAD）
scripts/vendor-compose-core.sh <tag> --check     # 重建后编译 :compose-kn-linux:compileKotlinLinuxX64
```

需要代理时：`GIT_PROXY=http://host:port scripts/vendor-compose-core.sh`。
git 源码缓存在 `.cache/compose-core-git/`（sparse blobless），弱网下脚本自带重试。

> **判断"真·更新的 tag"**：上游 tag 名里的 `+devN` **不是**单调 commit 序号，多个 tag 可能
> peel 到同一 commit。升级前务必 `git rev-parse <tag>^{commit}` 与当前基线 commit 比对，
> 确认 peel 出来的 commit 确实不同，否则脚本可能"成功"但源码没变（假阳性）。
> 用 `--refetch` 可避免命中本地陈旧 ref / 陈旧 FETCH_HEAD。

## 升级到新版 Compose 的步骤

1. **选 tag**：上游 tag 形如 `v1.12.0-beta01`、`v1.12.0-beta01+dev4339`（`+devN` 是开发线）。
2. **跑脚本**：`scripts/vendor-compose-core.sh <tag>`。若 patch 应用失败（reject 文件出现），
   说明上游改了那 7 个文件 —— 逐个重做 patch（通常只是行号漂移，用 `git apply --3way` 或手工）。
3. **补新的 expect**：上游新代码可能引入新的 `expect` 声明，编译会报
   `Expected declaration 'X' is not initialized for target linuxX64`。在
   `vendor/compose-core.local/overlay/<module>/src/linuxX64Main/...` 加对应 actual，重跑脚本。
4. **版本矩阵**：Maven 侧 `runtime` 等依赖版本要用 `COMPOSE_VERSION=<v>` 一并替换
   （脚本会同时改 templates 与 `vendor/skiko/dependencies.toml`）；该版本必须在 Maven 上真实存在。
5. **skiko 耦合**：`compose-kn-linux` 与 vendored skiko 的接口（`SkikoRenderDelegate`、
   `SkikoDispatchers.Main` 等）是强耦合。compose 大版本升级通常需要同步升级 skiko，
   此时 `vendor/skiko/` 的同步是另一条线（其本地 Wayland 代码在 `linuxMain/`，上游更新时手工合并）。
6. **internal API 耦合**：`compose-kn-linux` 使用了 `CanvasLayersComposeScene`、
   `DefaultArchitectureComponentsOwner`、`FrameRecomposer` 等 `@InternalComposeUiApi`，
   上游重构这些类时需同步修改 `compose-kn-linux/`。
7. **验收**：`scripts/vendor-compose-core.sh <tag> --check` 编译通过后，链接 + 跑 wayland-demo：
   - **（NixOS 首次链接前）** 若 konanc 报 glibc 符号未定义（`stat64@2.33` /
     `__isoc23_strtol@2.38` 等），是 konanc 2.4.0 工具链捆绑的 **glibc 2.19 sysroot** 太旧
     （宿主是 glibc 2.42）。跑一次 `scripts/fix-konan-glibc.sh` 把该 sysroot 整套 glibc
     换成 NixOS 2.42（幂等，按版本自检；`~/.konan` 重新下载会丢失此修改，需重跑）。
   - **强制真重链**：同步换了源码后，demo 的 `compileKotlinLinuxX64` 可能因 composite build
     的 up-to-date 判定未失效而显示 UP-TO-DATE，且 `linkReleaseExecutableLinuxX64Stable`
     的 up-to-date 只认旧 `.kexe` 存在。要确保链到新源码，先
     `rm samples/wayland-demo/build/bin/linuxX64/releaseExecutable/wayland-demo.kexe`，
     再 `./scripts/link-wayland-demo.sh`。
   - **运行**：`./scripts/run-linux-native.sh`（见 RUNNING.md，需 Wayland 会话）。

## 已知冻结点

- `ui-backhandler`：来自发布 sources jar（无 `kotlin/` 层级），jbMain 有 navigationevent 1.1.1
  兼容 patch（`LocalCompatNavigationEventDispatcherOwner`）。升级时可考虑改回上游仓库布局，
  需同时改 `templates/modules/ui-backhandler/build.gradle.kts` 的 `srcDir`。
- `lifecycle-viewmodel-compose`：仅 2 个源文件，整模块冻结。
- `vendor/skiko/`：未纳入自动同步（Wayland 层为纯本地代码）。
