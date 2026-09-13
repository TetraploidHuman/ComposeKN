# 工作交接文档：Compose 上游同步管线（compose-core）

> 写于 2026-09-08。给下一个有完整文件系统权限的 AI / 开发者。
> 用户用中文交流，回复请用中文。

## 0. 一句话背景

项目 `ComposeKN` 把 Compose Multiplatform 的 14 个模块以**源码 vendoring** 进
`vendor/compose-core/`（composite build + dependencySubstitution 替换 Maven 坐标），
并在其上做了 linuxX64/Kotlin-Native 适配。目标：做一条**一键同步上游新版本**的管线，
保证本地改动（39 个 linuxX64 actual 等）在升级时不丢失。

## 1. 已完成（本次会话）

### 1.1 量化本地改动（原计划第 1 步）✅

- **确定上游精确基线：`v1.12.0-beta01+dev4339`**（annotated tag，tag 对象
  `6d473fc44b`，实际 commit `fca104ce5d4d3bb1e88a04414ffa382894800797`）。
  方法：对 3 个候选 tag 做全量 blob-hash 三向 diff，diff 最小的胜出。
  ⚠️ 源码基线**不是** v1.11.1 —— `1.11.1` 只是 Maven 依赖（runtime 等）的版本号。
  > **当前基线已推进**：2026-09 由 P1 同步到 `v1.12.0-rc01+dev4619`
  > （commit `265534f9`，比 dev4339 新约 280 commits），编译 + 链接 + 运行全部通过，
  > 已定为新基线（见 `vendor/compose-core.local/VERSIONS` 与 §2-P1）。dev4339 仍可随时回退。
- 本地改动全集（相对该基线，13 个上游模块目录内）：
  - **7 个上游文件被修改**（见 `vendor/compose-core.local/patches/`，全部已导出为 patch）
  - **40 个新增 src 文件**：39 个 `linuxX64Main` actual + 1 个 `ui-text` skikoMain actual
  - **ui-backhandler 整模块来自发布 sources jar**（无 `kotlin/` 目录层级，jbMain 有
    navigationevent 1.1.1 兼容 patch），**lifecycle-viewmodel-compose 整模块仅 2 个文件**
  - **17 个 build 文件**全部是本地写的（上游 Groovy `build.gradle` → 本地 KMP
    Kotlin DSL，5 层 sourceSets 链 commonMain→skikoMain→nonJvmMain→nativeMain→linuxX64Main）
  - 1009 个上游文件"removed"均为从未 vendored 的内容（samples/benchmark/
    integration-tests/bcv/OWNERS/api 子集等），**没有**有意的 src 删除

### 1.2 本地改动外置（原计划第 2 步）✅

新建 **`vendor/compose-core.local/`**（详见其 README.md）：

```
vendor/compose-core.local/
├── VERSIONS        # 基线 tag/sha + 版本矩阵（compose_deps=1.11.1, kotlin=2.4.0 ...）
├── templates/      # 17 个 build 文件（根 3 + 14 模块）
├── overlay/        # 50 个文件：40 新增 src + ui-backhandler 10 + lifecycle 2 + META-INF 2
└── patches/        # 7 个 unified diff（patch -p1，相对 vendor/compose-core 根）
```

### 1.3 同步脚本重写（原计划第 3 步）✅

**`scripts/vendor-compose-core.sh`** 重写为自包含幂等管线：

```
scripts/vendor-compose-core.sh [tag] [--check]
  1. git sparse blobless clone/fetch（缓存在 .cache/compose-core-git/，弱网重试 10 次）
  2. 清空 vendor/compose-core/，rsync 12 个上游模块的 src/+api/+res/
     （ui-backhandler 不取上游，整模块冻结在 overlay）
  3. 覆盖 17 个 build 模板
  4. 拷贝 overlay
  5. patch -p1 应用 7 个 patch（有 reject 即失败退出）
  6. 可选 COMPOSE_VERSION= / KOTLIN_VERSION= 批量替换版本号（含 vendor/skiko/dependencies.toml）
  7. 写 .sync-info
  8. 自检（actual 数 >=39、patch 0001 标记存在）
  9. --check 时跑 ./gradlew :compose-kn-linux:compileKotlinLinuxX64
```

### 1.4 验证（原计划第 4 步）— 完成 ✅

- ✅ **Round-trip 字节级验证通过**：同步脚本按基线 tag 重建 `vendor/compose-core/`，
  与重建前的目录 `diff -r` **完全一致**（唯一差异是 3 个空目录：
  `ui/src/linuxX64Main/.../androidx/compose/ui/{hapticfeedback,navigationevent,window}`，
  无内容，对编译无影响）。7 个 patch 全部干净应用。
- ✅ **编译 + 运行验证通过**：初次 vendoring 时本 AI 在沙箱里（只能写项目目录和 /tmp，
  gradle wrapper 需要写 `~/.gradle/wrapper/dists/` 而报 `权限不够`）未能跑编译；
  后续在完整权限环境（NixOS 26.05 + glibc 2.42）由 **P0** 跑通
  `compileKotlinLinuxX64` + demo 链接 + 运行（详见 §2-P0 验证结果）。

### 1.5 文档 ✅

- `SYNCING.md`（项目根）— vendoring 总览 + 升级步骤 + 检查清单 + 已知冻结点
- `vendor/compose-core.local/README.md` — 外置目录详解
- 两个文档均已按最终实现校准（12 上游模块 + 2 冻结模块）

## 2. 验收工作（P0 ✅ / P1 ✅ 均已完成，仅剩 P2 远期）

### P0：编译验证 + 运行验证 — 完成 ✅（必须完整权限环境）

```bash
cd ComposeKN
./gradlew :compose-kn-linux:compileKotlinLinuxX64
# 通过后再跑 demo（NixOS 下见 RUNNING.md，需要 Wayland 会话）：
./scripts/run-linux-native.sh
```

若编译失败（理论上不应，因为源码字节一致）：先 `git status`/`diff -r` 排查是否
vendor 目录被动过；`.sync-info` 记录了本次同步的 tag 和 sha。

#### P0 验证结果（2026-07，完整权限环境，NixOS 26.05 + glibc 2.42）

- ✅ **编译验证通过**：`./gradlew :compose-kn-linux:compileKotlinLinuxX64`
  → **BUILD SUCCESSFUL**。全部 14 个 vendored compose 模块 + `compose-kn-linux` 编译 0 错误
  （产物为目录式 klib：`<module>/build/classes/kotlin/linuxX64/main/klib/<module>/default/`）。
  证明「上游同步管线 → 可编译」成立。
- ✅ **demo 链接 + 运行通过**：`:samples:wayland-demo:linkReleaseExecutableLinuxX64Stable`
  → **BUILD SUCCESSFUL**，产出 `wayland-demo.kexe`（~40MB）；运行输出
  `composekn: EGL ready 960x640` / `Skia GrDirectContext created`，50s 渲染循环 0 崩溃
  （0 个 segfault/abort/error 关键字）。**P0「编译 + 运行」全部达成。**

  链接曾卡在一个预存工具链问题上，已定位并解决（详见「环境坑位」glibc 行）：
  konanc 2.4.0 的 linux_x64 工具链捆绑 **glibc 2.19**，缺 `stat64@2.33`/
  `__isoc23_strtol@2.38`，而 skiko 原生代码按宿主 glibc 2.42 头文件编译 → 链接期 `ld.lld`
  未定义。修复 = 把该工具链 sysroot 整套 glibc 换成 NixOS glibc 2.42。

为跑通上述流程，本次对构建环境做了 5 处修复（详见下方「环境坑位」新增行）：
konan mavenLocal 预置、JVM 内存、windows 模块宿主 guard、外层 `--no-daemon`（libstdc++）、
**glibc sysroot 换成 2.42**（最后一步，打通链接与运行）。

### P1（真正验收"能同步新版本"）— 完成 ✅

**结论：同步管线能真实拉取并编译一个更新的 tag。** 已把基线从 `dev4339`
推进到 `v1.12.0-rc01+dev4619`（commit `265534f9`，比 dev4339 新约 280 commits），
并端到端验证通过。

```bash
# 真实同步一个更新的 tag（--refetch 强制真 fetch，避免命中本地陈旧 ref/FETCH_HEAD）
scripts/vendor-compose-core.sh v1.12.0-rc01+dev4619 --refetch --check
# 随后在新基线上重链 + 运行 demo（见 §2-P0 的链接/运行命令）
```

- ✅ **同步**：`--refetch` 真拉取 rc01 源 → 7 个 patch 0 reject、39 个 linuxX64Main actual
  自检通过，`.sync-info` 正确记录 `upstream_sha=265534f9`。
- ✅ **编译**：`:compose-kn-linux:compileKotlinLinuxX64` → **BUILD SUCCESSFUL**
  （仅 deprecation 警告，0 错误）。
- ✅ **链接 + 运行**：删旧 `.kexe` 强制真重链 → `wayland-demo.kexe`（~40MB）；
  运行 `EGL ready 960x640` / `Skia GrDirectContext created`，50s 渲染 0 崩溃。
  **rc01 端到端全部通过，已定为新基线。**

**⚠️ 过程中抓到并修复的一个假阳性（重要经验）：**
第一次试 `v1.12.0-beta02+dev4388` 时脚本报"编译通过"，但那**不是真同步**——
`beta02+dev4388` 与基线 `beta01+dev4339` **peel 到同一个 commit**（`fca104ce5`），
且上游 tag 名里的 `+dev` 号**不是单调 commit 序号**（多个 tag 可指向同一 commit）。
根因是脚本 bug：`fetch ... || true` 吞掉了一次弱网 fetch 失败，随后读到**陈旧的
`FETCH_HEAD`**（仍是基线）并 `update-ref refs/tags/<tag> <基线>` 造了个假本地 ref，
下次 `rev-parse` 命中它就跳过 fetch。修复：脚本新增 `--refetch` 强制真 fetch、
检查 fetch 退出码并重试、不再回退到陈旧 FETCH_HEAD；已删除假 ref。
**判断"真·更新的 tag"必须 `rev-parse <tag>^{commit}` 与基线 commit 比对，不能只看 tag 号。**

**未来同步其它 tag 时可能遇到的问题及处理**（详见 SYNCING.md 检查清单）：
1. patch reject → 上游改了那 7 个文件，逐个重做（`git apply --3way` 或手工），
   然后把新 patch 更新回 `vendor/compose-core.local/patches/`
2. 新增 expect → 在 `vendor/compose-core.local/overlay/<module>/src/linuxX64Main/` 补 actual
3. 依赖版本 → `COMPOSE_VERSION=<v>` 重跑（该版本必须已发布到 Maven）
4. 成功后更新 `vendor/compose-core.local/VERSIONS` 的基线 tag
5. 同步后 demo 的 `compileKotlinLinuxX64` 可能因 composite build 的 up-to-date 判定
   未失效而显示 UP-TO-DATE —— 需要真重链时先删 `wayland-demo.kexe` 再跑
   `scripts/link-wayland-demo.sh`（Stable link task 的 up-to-date 只认旧产物存在）

### P2（远期）

- `vendor/skiko/` 尚未自动化（Wayland 层是纯本地代码，上游更新需手工合并；
  流程已写进 SYNCING.md）
- ui-backhandler 可考虑改回上游仓库布局（需同步改 templates 里的 srcDir）

## 3. 环境坑位（重要，避免重复踩）

| 坑 | 说明 |
|---|---|
| **沙箱写限制** | 本 AI 会话只能写 `ComposeKN/` 项目内和 `/tmp`；`~/.gradle`、`~/.konan`、home 根只读。gradle 编译、任何写 home 的操作必须用户或有完整权限的执行者来做 |
| git 不在 PATH | NixOS。本会话用的：`/nix/store/bcnisk3ydfgv26v2gw3zlky24g00yww2-git-2.54.0/bin/git`（用户自己的 shell 里应该有正常 git） |
| 网络代理 | GitHub 直连超时，必须走 `http://172.20.128.142:7897`（curl `-x`、git `GIT_PROXY`/`http(s)_proxy`；项目 gradle.properties 里也配了） |
| 大文件 TLS 不稳 | codeload tarball 和 git 大 blob 传输常 `unexpected eof`。脚本用 blobless sparse clone + checkout 重试兜底；**不要用 codeload** |
| annotated tag | 基线 tag 是 annotated tag：`rev-parse <tag>` 得到 tag 对象而非 commit；`fetch origin <tag>` 只更新 FETCH_HEAD 不建本地 ref；`rev-parse` 失败时不带 `--verify` 会把参数原样输出到 stdout。脚本已正确处理，调试时注意 |
| gradle wrapper | 需要 `~/.gradle/wrapper/dists/` 可写且 gradle-9.5.0 分发完整。沙箱内无法修复（0 字节的 `.part` 残留可忽略，wrapper 会重下） |
| konan | `~/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.0` 已缓存，离线可编译（在完整权限环境下） |
| **konan 重新下载（TLS）** | `~/.konan/.../provisioned.ok` 为 0 字节 → KGP 判定未就绪而重下 `kotlin-native-prebuilt:2.4.0`（~207MB），但该 artifact **只在 JetBrains**（cache-redirector→aws，大文件 TLS 不稳）。修复：curl 走代理 `-L -C --retry` 预置到 `~/.m2` mavenLocal（`.pom`/`.sha1`/`.sha256` 一并），settings 首个 repo 即本地，gradle 不再远取 |
| **klib 远取失败（TLS）** | `graphics-shapes-linuxx64`、`kotlinx-datetime-linuxx64` 的 klib 只在 `dl.google.com`/`repo.maven.apache.org`（TLS 不稳）。同样 curl 预置 mavenLocal（`.klib`+`.pom`+`.module`+父 `.module`/`.pom`） |
| **JVM GC 抖动** | Kotlin daemon 默认 512MiB，14 模块编译触发 `build daemon stopped (GC thrashing)`。修复：`gradle.properties` 加 `org.gradle.jvmargs=-Xmx4g ...` 与 `kotlin.daemon.jvmargs=-Xmx8g ...` |
| **windows 模块阻塞** | `compose-kn-windows/build.gradle.kts` 用 `windowsX64()`（应为 `mingwX64()`），Linux 下 full-configuration 报错，阻塞内层链接（外层 `--configure-on-demand` 到不了内层）。临时修复：`settings.gradle.kts` 加宿主 guard（非 Windows 不 include 该模块及 windows-demo）；真正修复是 Windows 机上改 `mingwX64()` |
| **konanc libstdc++** | 外层走 Gradle daemon 时 konanc(JNI 复用 daemon) 缺 `LD_LIBRARY_PATH` → `UnsatisfiedLinkError ... libstdc++.so.6`。修复：外层 `--no-daemon`（`KonanLinkWorkaroundPlugin` 把外层 env 的 LD_LIBRARY_PATH 透传给内层 konanc），libstdc++ 报错即消失 |
| **glibc 链接（已解决）** | demo 最终 `ld.lld` 报 `stat64@GLIBC_2.33`/`fstat64@GLIBC_2.33`/`__isoc23_strtol@GLIBC_2.38` 未定义。**根因**：`konan.properties` 的 `toolchainDependency.linux_x64 = ...-glibc-2.19-...`，即 konanc 链接用其工具链 sysroot 的 **glibc 2.19**（缺这些新符号）；而 skiko 原生代码按宿主 glibc 2.42 头文件编译。**修复**：把 `~/.konan/dependencies/x86_64-...-glibc-2.19-.../x86_64-unknown-linux-gnu/sysroot/lib/` 里**整套 glibc**（`libc/libm/libpthread/libdl/librt/libutil/libresolv/libnsl/libanl/libBrokenLocale/ld-*/libthread_db/libnss_*` 共 17 个 `*-2.19.so` 真实文件）内容替换为 NixOS glibc 2.42（`/nix/store/57iz...glibc-2.42-61/lib/`）。glibc 向后兼容故新旧符号都满足；**必须整套换**（glibc 2.34+ 的 libpthread/librt/libdl 已是仅 NEEDED libc 的薄 stub，只换 libc 会在 `--no-allow-shlib-undefined` 下触发 2.19 libpthread/librt 引用的 `__*@GLIBC_PRIVATE` 跨版本不匹配）。解释器无需动（宿主构建用标准 `/lib64/ld-linux-x86-64.so.2`，运行时解析到 NixOS 2.42，`ldd` 0 not-found）。**注意**：这是对 `~/.konan` 缓存的原地修改（非声明式），若工具链被重新下载会失效需重做；理想做法是封装进 shell.nix / 用 bind-mount 使其可复现 |
| 上游仓库 | `JetBrains/compose-multiplatform-core`（AOSP 式布局，模块在 `compose/ui/ui` 等路径）；默认分支 `jb-main`（1.13 开发线）；tag 形如 `v1.12.0-beta01+dev4339` |

## 4. 文件索引

| 路径 | 说明 |
|---|---|
| `scripts/vendor-compose-core.sh` | 一键同步（本次重写） |
| `vendor/compose-core.local/` | 本地改动外置（本次新建：VERSIONS/templates/overlay/patches/README） |
| `vendor/compose-core/` | vendored 源码（12 上游模块 + 2 冻结模块），`.sync-info` 记录同步状态 |
| `SYNCING.md` | 同步/升级文档（本次新建） |
| `vendor/skiko/skiko/src/linuxMain/` | 自定义 Wayland 窗口层（未动，未纳入自动同步） |
| `compose-kn-linux/` | 应用层，依赖 compose 的 internal API（升级时需关注） |
| `RUNNING.md` | NixOS 运行文档（已有，未动） |
| `.cache/compose-core-git/` | git sparse 缓存（可删，脚本会重建） |

## 5. 快速复现验证（完整权限环境）

```bash
# round-trip（应与现状字节一致，除 3 个空目录）
rsync -a --exclude=build/ --exclude=.gradle/ vendor/compose-core/ /tmp/before/
./scripts/vendor-compose-core.sh
diff -r /tmp/before vendor/compose-core --exclude=.sync-info

# 编译
./gradlew :compose-kn-linux:compileKotlinLinuxX64
```

## 6. 功能推进：IME 中文输入法（Wayland text-input-v3 + fcitx5）✅ 已实现并逐层验证

> 用户指令「修好了就先放着，先推动功能性的」→ 本阶段做的是 **Wayland 中文输入法**：
> fcitx5（拼音）→ 候选窗 → commit_string → 插入 Compose TextField。

### 6.1 实现（跨 4 层，全部完成）

| 层 | 文件 | 做了什么 |
|---|---|---|
| **C 协议层** | `vendor/skiko/skiko/src/linuxMain/cpp/wayland/wayland_bridge.h` | 新增 `ComposeKNImeKind` 枚举 + 6 个 C API：`composekn_window_pop_ime_event`（带字符串的事件出队）+ `composekn_text_input_set_enabled/set_cursor_rectangle/set_surrounding_text/set_content_type` |
| **C 实现** | `vendor/skiko/skiko/src/linuxMain/cpp/wayland/wayland_window.cc` | bind `zwp_text_input_manager_v3`（v2）→ `get_text_input(seat)` → 监听 `enter/leave/preedit_string/commit_string/delete_surrounding_text/done/action/...`，入 `ime_events` 队列；seat 有键盘时 `ensure_text_input`；destroy 时先毁 text_input 再毁 manager；实现 6 个 C API |
| **cinterop + Kotlin bridge** | `vendor/skiko/skiko/src/linuxMain/kotlin/org/jetbrains/skiko/WaylandNative.kt` | `external fun` 声明 + `sealed class WaylandImeEvent`（Enter/Leave/Preedit/Commit/Delete/Done）+ `COpaquePointer.drainImeEvents()`（memScoped 出队→Kotlin 事件） |
| **WaylandWindow** | `vendor/skiko/skiko/src/linuxMain/kotlin/org/jetbrains/skiko/WaylandWindow.kt` | `var onImeEvent`；`poll()` 同时 drain IME 事件；`textInputSetEnabled/CursorRectangle/SurroundingText` |
| **Compose 集成** | `compose-kn-linux/.../LinuxTextInputService.kt` | `startInputMethod`→启用 text input + 上报 surrounding text/cursor rect；`handleImeEvent`→`Commit` 走 `onEditCommand(listOf(CommitTextCommand(text,1)))`（与 Compose 官方桌面 IME 同一模式）；`handleImeEvent` 有 `composekn: ime …` 日志 |
| **接线** | `compose-kn-linux/.../LinuxComposeApplication.kt` | 构造 `LinuxTextInputService`；`window.onImeEvent = { service.handleImeEvent(it) }`；Key 分支有 `composekn: key …` 日志（诊断用） |

**协议要点**：`zwp_text_input_manager_v3`(v2) → `get_text_input(seat)` → `zwp_text_input_v3`(v2)。事件顺序（已对照生成头文件核对）：enter, leave, preedit_string(text,cb:int,ce:int), commit_string(text), delete_surrounding_text(before:uint,after:uint), done, action, language, preedit_hint。请求：enable/disable/set_content_type/set_cursor_rectangle/set_surrounding_text/commit/destroy。

**v1 决策**：commit-based（不镜像 preedit 进字段）；`delete_surrounding_text` no-op；不抑制 wl_keyboard（依赖 fcitx5 组合期抓键，英文字母走 wl_keyboard 直插）。

### 6.2 逐层验证结果

| 验证点 | 结果 | 证据 |
|---|---|---|
| C 编译（native bridges） | ✅ | `:skiko:compileNativeBridgesLinuxX64` 通过（"header files modified" 触发重编） |
| Kotlin 编译（skiko + compose-kn-linux） | ✅ | `:compose-kn-linux:compileKotlinLinuxX64` BUILD SUCCESSFUL（5m6s），0 error |
| 链接 .kexe | ✅ | `linkReleaseExecutableLinuxX64Stable` BUILD SUCCESSFUL；.kexe 40,492,424 bytes |
| 运行不崩 | ✅ | `timeout 25 run-linux-native.sh` → exit 124，3 个成功行，0 错误行 |
| 合成器提供 text-input 全局 | ✅ | 自写 wayland 全局 dump：40 个全局含 `zwp_text_input_manager_v3` |
| **C→Kotlin→handler 全链路** | ✅ | 运行 demo 出现 `composekn: ime enter (text input enabled)`——这是合成器发来的真实 `zwp_text_input_v3.enter`，完整走通 C 队列→cinterop→WaylandWindow→LinuxTextInputService |
| Compose 集成正确性 | ✅ | `CommitTextCommand.applyTo` 把文本插入光标并把光标移到末尾（已读源码核对），与 `DesktopPlatformInput.desktop.kt:128` 官方桌面 IME 同一模式 |
| fcitx5 环境 | ✅ | pinyin 引擎已装（`libpinyin.so`）+ "Wayland Input method frontend" 已启用 + fcitx5 连到 wayland-0 + profile `DefaultIM=pinyin` |

### 6.3 自动化 commit 测试（uinput 虚拟键盘）— 受阻于合成器路由，非实现 bug

用 `/tmp/vkbd.c`（uinput 虚拟键盘，已编译在 `/tmp/vkbd`）注入拼音，端到端试 commit。**结论：自动化路径拿不到 commit，根因是 GNOME Shell 的 IME 路由行为，不是我的实现问题。**

- ✅ vkbd 设备被内核识别（`/proc/bus/input/devices` 有 `composekn-vkbd`，出现 `/dev/input/event9`）。
- ✅ vkbd 发送 keyCode 正确（'nihao ' → 49,23,35,30,24,57），按键**到达 demo**（`composekn: key …` 12 条）。
- ⚠️ GNOME Shell 对 uinput 设备有 **+8 keyCode 偏移**（发 30→收 38），已用「发 intended-8」补偿，补偿后 demo 收到正确 keyCode（49,23,35,30,24,57）。
- ❌ 键到达客户端却**未被 fcitx5 拦截**（无 commit）：GNOME Shell 把 uinput 键**直接交给客户端**（wl_keyboard 原始键），而非路由给 fcitx5。即使用 `fcitx5-remote -o pinyin` 强制激活拼音也无 commit。
- 推断：GNOME Shell 对**物理键盘**才会走 IME 路由（键被 fcitx5 消费，不到客户端）；uinput 设备被当作直接输入。所以**自动化 uinput 无法复现真实 fcitx5 交互**。

### 6.4 金标准验证 = 交互测试（用户操作）

自动化拿不到 commit，最终验证用**真实键盘**（本机 GNOME Shell + fcitx5 拼音）：

```bash
cd /home/miaox99/ComposeKN
# 1) 起 demo
./scripts/run-linux-native.sh &
# 2) 点一下文本框（或等自动聚焦）→ 控制台应出现：
#      composekn: ime enter (text input enabled)
# 3) 确认 fcitx5 拼音激活（托盘切到拼音，或 fcitx5-remote -o pinyin）
# 4) 敲拼音 "nihao" → fcitx5 弹候选窗 → 空格/数字选中
# 5) 期望：
#      composekn: ime preedit …（组合中，可选）
#      composekn: ime commit '你好'（或候选词）
#    且文本框里出现中文
```

**若交互测试出现"双重插入"（拼音字母 + 中文都进了字段）**：说明该合成器组合期没抓键、原始键也到了客户端。届时在 `LinuxTextInputService` 加「IME 组合期抑制 wl_keyboard 字符插入」（需一个 composing 状态位 + 在 `handleEvent` 的 Key 分支按该状态丢弃字符键）。当前 v1 未加，先靠 fcitx5 抓键。

### 6.5 已知限制 / 下一步

- 自动化 commit 测试受阻于 GNOME Shell 对 uinput 的路由（见 6.3）；如需 CI 化验证，可考虑 headless compositor（如 weston + fcitx5）或 xvfb，但成本高，暂不做。
- cursor rect 用 `focusedRectInRoot()*contentScale` 近似（忽略 chrome 偏移）；若候选窗位置偏差再精修。
- 诊断日志（`composekn: ime …` / `composekn: key …`）当前常开，利于交互测试排障；正式化时可加开关或降为 debug。

## 7. 功能推进：交互功能展示 demo（wayland-demo 升级）✅

> 用户指令「测试等会再弄，继续推进其他功能」。底层输入（pointer/keyboard/IME/clipboard/scroll/窗口管理）经代码走查确认**已全部接通**（C + Kotlin bridge + Compose 三层齐备），故本阶段把极简 demo（1 文本框 + 1 按钮）升级为**交互功能展示**，一次性覆盖各输入/交互能力，也给后续测试提供测试面。

### 7.1 demo 内容（`samples/wayland-demo/.../main.kt`）

滚动式（`verticalScroll`）分 6 节：
1. **文本输入**（OutlinedTextField）— IME 拼音 / 键盘 / 剪贴板 Ctrl+C/V/X。
2. **按钮**（点击计数）— 鼠标点击。
3. **Hover**（`MutableInteractionSource` + `collectIsHoveredAsState` + `Modifier.hoverable`）— 鼠标悬停变色。
4. **Drag**（`detectDragGestures` + `Modifier.offset{IntOffset}`）— 按住拖动方块。
5. **Switch / Checkbox**（material3）— 开关与复选。
6. **Scroll**（60 项列表）— 验证滚轮/滚动。

### 7.2 验证

- 编译 / 链接：`linkReleaseExecutableLinuxX64Stable` **BUILD SUCCESSFUL**（6m45s，.kexe 40,800,512 bytes）。
- 运行：`timeout 25 run-linux-native.sh` → exit 124，4 个成功行（EGL ready / Skia init / GrDirectContext / ime enter），**0 错误行** → 新 UI（hover/drag/scroll/switch 等）组成无异常、不崩。

### 7.3 说明

- 本次为纯 Compose 侧改动（仅 demo 模块），native（C/skiko/compose-kn-linux）未动，故走快链。
- 底层输入能力（pointer/scroll/keyboard/IME/clipboard/窗口管理）此前已实现并接线，本 demo 是它们的**运行时展示面**；交互正确性仍待用户实测（见 §6.4）。

## 8. 功能推进：Touch 触摸输入（Wayland wl_touch）✅ 已实现并验证编译/链接/运行

> 用户在本阶段选择「Touch 触摸输入」作为下一个功能（补全输入三件套：键盘 + 指针 + 触摸）。新增 `wl_touch` 监听，把触摸事件映射为 Compose 指针输入（`PointerType.Touch`）。

### 8.1 改动（4 处，跨 C / skiko / compose-kn-linux 三层）

| 层 | 文件 | 改动 |
|---|---|---|
| C header | `vendor/skiko/.../cpp/wayland/wayland_bridge.h` | `ComposeKNEventType` 加 `TOUCH_DOWN=9 / TOUCH_MOTION=10 / TOUCH_UP=11`（复用 `ComposeKNEvent.button` 携带 touch id，`x/y` 为 surface 本地未缩放坐标）。 |
| C impl | `vendor/skiko/.../cpp/wayland/wayland_window.cc` | 结构体加 `wl_touch* touch` + `std::map<uint32_t,(x,y)> touch_points`（按 id 跟踪点位）；新增 `touch_down/up/motion/frame/cancel/shape/orientation` 7 个回调 + `touch_listener`；`seat_capabilities` 里按 `WL_SEAT_CAPABILITY_TOUCH` 绑定/解绑；`destroy` 里 `wl_touch_release`。 |
| Kotlin bridge | `vendor/skiko/.../org/jetbrains/skiko/WaylandNative.kt` | `WaylandEventType` 加 `TouchDown(9)/TouchMotion(10)/TouchUp(11)`（与 C 枚举值对齐，经 `nativeValue` 匹配）。 |
| Compose 映射 | `compose-kn-linux/.../WaylandInputMapper.kt` | `when` 加 touch 分支：`TouchDown→Press`、`TouchMotion→Move`、`TouchUp→Release`，均 `sendPointerEvent(..., type = PointerType.Touch)`。 |

### 8.2 协议坑（重要）

本环境 wayland 1.25.0 的 `wl_touch_listener` 字段顺序为 **`down, up, motion, frame, cancel, shape, orientation`**（三个副本一致：system/nix store×2），**没有 `enter`/`leave`** 事件，且：
- `down` 携带 `surface` 参数（8 参），`motion` **不**携带 `serial`（6 参）。
- 一个触摸点完全由 `down`(带初始位置) … `up` 描述，无需 enter/leave。

> 若按常见「含 enter/leave」的协议写监听器，字段顺序/签名都会错位（类型不匹配直接编译失败）。已按真实协议对齐，C 编译 32s 通过。

### 8.3 验证

- C 桥编译：`:skiko:compileNativeBridgesLinuxX64` **BUILD SUCCESSFUL**（32s）。
- 完整重链：`linkReleaseExecutableLinuxX64Stable` **BUILD SUCCESSFUL**（11m4s，.kexe 40,808,960 bytes，比无 touch 版 +8.4KB）。
- 运行：`timeout 25 run-linux-native.sh` → exit 124，4 成功行（EGL ready / Skia init / GrDirectContext / ime enter），**0 错误行** → touch 初始化（seat 无触摸能力时监听器不注册，不崩；有则正常监听）。

### 8.4 限制（待后续）

- **完整交互验证需触摸屏**：本 GNOME Shell 会话无 touch 座标，事件路径未触发；代码按协议实现 + 编译/链接/运行干净，交互正确性待用户在触摸设备上实测（可拖 §7 的 Drag 方块 / 点按钮验证）。
- 当前为**单点触摸**映射（每触摸点独立 Press/Move/Release）；**多点手势（捏合缩放/双指）**未做（需 Compose `sendPointerEvent` 的多 pointer 重载 + 手势识别），留作后续。

## 9. 自动化输入测试（2025-01-10 完成）

### 测试基础设施
- **uinput 虚拟鼠标** (`/tmp/vmouse`)：绝对坐标 (0-1919, 0-1079) + 按钮 + 滚轮，支持命令序列 `move:x:y click:0 scroll:n`
- **uinput 虚拟触摸** (`/tmp/vtouch`)：B 协议 (ABS_MT_POSITION_X/Y + ABS_MT_TRACKING_ID + BTN_TOUCH + INPUT_PROP_DIRECT)，支持 `down:x:y move:x:y up`
- **uinput 虚拟键盘** (`/tmp/vkbd`)：已存在，支持文本输入

### 测试结果
| 输入类型 | 状态 | 验证方式 | 详情 |
|---------|------|---------|------|
| **鼠标** | ✓ 通过 | uinput + 事件日志 | 37 事件 (Enter/Move/Press/Release/Scroll)，surface-local 坐标正确 (屏幕 600/960/1320 → 本地 120/480/840，窗口原点 = 屏幕 (480,276)) |
| **键盘** | ✓ 通过 | uinput + 事件日志 | keyCode + keysym 到达 Compose (keyCode=30→'a', keyCode=48→'D', keyCode=57→'\`'，keysym 映射有 +8 偏移问题但管线正常) |
| **触摸** | ✗ 未通过 | uinput + 事件日志 | 触摸事件没到达 C 层 (touch_down 未被调用)。根因：Mutter (GNOME Shell) 没把 uinput 虚拟触摸设备识别为真正的触摸屏，wl_seat 不广播 TOUCH 能力。**这是合成器/环境限制，不是代码 bug**。真实触摸屏应能工作。 |

### 技术发现
- **Kotlin/Native 运行时日志限制**：Wayland 回调里的 `fprintf(stderr)` 和 `write(2,...)` 输出不被捕获（可能运行时重定向了 fd2），但事件本身正常传递
- **窗口定位**：demo 窗口 960x640，居中时内容区域原点在屏幕 (480, 276)
- **uinput 触摸设备识别**：需要 `INPUT_PROP_DIRECT` 属性 + B 协议 (ABS_MT_*)，但 Mutter 仍不路由（可能需要真实硬件或不同的 uinput 配置）

### 后续建议
- 触摸功能需在**真实触摸屏**上验证（代码已正确实现 wl_touch 协议）
- 或尝试 wlroots 合成器 (sway/wlroots) 测试 uinput 触摸（wlroots 对 uinput 支持更好）

## 10. CSD 窗口和窗口管理按钮（2025-01-10 完成）

### 实现内容
- **CSD（Client-Side Decorations）**：客户端自己绘制窗口装饰（标题栏、边框）
- **窗口管理按钮**：最小化、最大化/还原、关闭
- **窗口拖拽**：拖拽标题栏移动窗口
- **Resize handles**：已实现并通过测试

### 技术实现

#### C 层改动
1. **wayland_window.cc**：
   - 添加 `composekn_window_begin_resize(window, edges)` 函数（调用 `xdg_toplevel_resize`）
   - 修改 `request_server_side_decoration` 请求 `CLIENT_SIDE` 模式而非 `SERVER_SIDE`

2. **wayland_bridge.h**：
   - 声明 `composekn_window_begin_resize`

#### Kotlin 层改动
1. **WaylandWindow.kt**：
   - 添加 `beginResize(edges: UInt)` 方法（暂时注释，等待 cinterop 重新生成绑定）

2. **LinuxWindowChrome.kt**：
   - 已有完整的 CSD 标题栏实现：
     - 标题文字（可拖拽移动窗口）
     - 最小化按钮（−）
     - 最大化/还原按钮（□/❐）
     - 关闭按钮（×）
   - Resize handles 代码已准备（暂时注释）

### 窗口管理 API
```kotlin
window.minimize()              // 最小化窗口
window.toggleMaximized()       // 切换最大化/还原
window.isMaximized             // 查询是否最大化
window.requestClose()          // 请求关闭
window.beginMove()             // 开始拖拽移动
// window.beginResize(edges)   // 开始拖拽调整大小（待启用）
```

### 验证
- ✓ 构建成功（6m37s，39MB .kexe）
- ✓ Demo 运行正常（EGL ready、Skia init、IME enter）
- ✓ 无错误日志

### 已知限制
- **Resize handles 已启用并全部通过测试（2025-01-10 晚）**：

### Resize 测试结果（uinput 鼠标逐边拖拽，全部通过）
| 方向 | edge 常量 | 结果 |
|------|-----------|------|
| TOP | 1 | ✓ 960x640 → 960x688 |
| BOTTOM | 2 | ✓ 960x640 → 960x736 |
| LEFT | 4 | ✓ 960x640 → 1042x640 |
| RIGHT | 8 | ✓ 960x640 → 1062x640 |
| TOP_LEFT | 5 | ✓ → 1042x690 |
| TOP_RIGHT | 9 | ✓ → 1042x690 |
| BOTTOM_LEFT | 6 | ✓ → 1042x726 |
| BOTTOM_RIGHT | 10 | ✓ → 1042x726 |

### 调试中发现的坑（重要）
1. **Gradle 增量编译不检测 LinuxWindowChrome.kt 改动** → Kotlin 改动需 `:compose-kn-linux:compileKotlinLinuxX64 --rerun-tasks` 或删 kexe 文件强制重链；否则二进制还是旧的（曾因此误判 resize 无效）
2. **Compose Box 子元素后写者在上层**：ResizeHandle 写在 content() 之前会被内容盖住收不到事件 → handled 已移到最外层最后绘制
3. **垂直 handle 必须按 y 分段选择边缘**，水平 handle 按 x（原代码全按 x，导致左边缘四角方向错乱）
4. **顶边 handle 要放在标题栏之上**（外层 Box align TopCenter），否则按到标题栏触发 beginMove
5. **等效修正**：Bottom handle 的 edgeTop 原误写为 BOTTOM_LEFT，已改为 BOTTOM(2)
6. **窗口原点探测法**：中心按一下取 local 坐标反推 origin（本次 origin=(480,236)，窗口随 resize 会漂移，别用缓存位置）

### 已知残留
- C 层 debug 日志（/tmp/composekn_debug.log）暂保留：seat capabilities / touch_down / toplevel_configure / begin_resize / pointer_button

- **CSD 视觉样式**：当前使用简单的深色标题栏（#2D2D30），可以进一步美化

### 后续建议
- 修复 cinterop 工具崩溃问题，启用 resize handles
- 添加窗口边框（可选，用于视觉区分）
- 添加窗口阴影（需要合成器支持）
- 支持双击标题栏最大化/还原


## 11. Windows 平台对齐与 mingwX64 构建管线（本次）

### 结论与硬边界
- **skiko 现已支持 mingwX64 native 目标**（Linux 宿主交叉编译），全部 Kotlin 代码编译通过，包括本文件 §10 的窗口管理功能同步版。
- **最终 .exe 链接必须 Windows 宿主**：JetBrains Windows skia 静态库 (.lib) 是 MSVC ABI（clang-cl），而 mingw Kotlin/Native 用 Itanium ABI，符号不匹配。在 Windows 机上跑 `:samples:windows-demo:linkReleaseExecutableMingwX64` 即可出 exe（Windows 宿主走 clang-cl/lld-link + MSVC CRT，skiko 原生路径本来就设计为 `OS.Windows.isCompatibleWithHost`）。
- 本机补丁清单（全部保留在工作区）：
  1. `settings.gradle.kts`：compose-kn-windows / windows-demo 在 Linux 上也 include（原先仅 Windows 宿主）。
  2. `compose-kn-windows` 与 `samples/windows-demo` 的 target 名 `windowsX64()`（不存在的名字）→ `mingwX64()`；source set 名同步 `mingwX64Main by getting { kotlin.srcDir("src/windowsX64Main/kotlin") }`。
  3. `samples/windows-demo/build.gradle.kts` 增加 `mingwX64 { binaries { executable { entryPoint = "main.main" } } }`（此前没有可执行产物定义，link 任务为 NO-OP）。
  4. `build-logic/.../WindowsNativeLinkerPlugin.kt`：目标名 mingwX64，新增 msys2 sysroot `-L` 注入（.konan/dependencies/msys2-mingw-w64-x86_64-2）。
  5. skiko `SkikoDependency.kt`：`OS.Windows.validEnvs` 增加 NATIVE（原来只有 JVM，导致 Windows native 时 Skia 库列表为空）。
  6. `NativeTasksConfiguration.kt`：新增 OS.Windows linkerFlags 分支（.lib 直存 + -luser32 等；win32 C 桥由 compileNativeBridges 编 `src/windowsMain/cpp/win32` 生成 .a，经 -include-binary 嵌入）。
  7. `SymbolExtractor.kt`：Windows 分支改用 GNU nm 解析 COFF .lib（Linux 宿主无 dumpbin；Linux 处 += OS.Windows 在 nmCommand args map）。
  8. `SkikoProjectContext.kt`：Windows artifact 不含 piex/dng_sdk，从 staticlibs 列表过滤。
  9. vendor/compose-core：`build.gradle.kts` subprojects 增加 `mingwX64()`；各模块 build.gradle.kts 增加 `mingwX64Main by getting` 并复用 linuxX64Main 源码目录（WaylandClipboard shim 在 skiko windowsMain 兜底）。

### 新增（代码）
- **skiko windowsMain**（Kotlin/Native Windows 真实实现，镜像 Linux Wayland 桥模式）：
  - `cpp/win32/win32_bridge.h` + `win32_window.cc`：borderless Win32 窗口（WM_NCCALCSIZE 去系统标题栏）、**WM_NCHITTEST 边缘/四角 resize**（8px×DPI，HTLEFT/HTRIGHT/HTTOP/HTBOTTOM/HTTOPLEFT/TOPRIGHT/BOTTOMLEFT/BOTTOMRIGHT）、PeekMessage pump、鼠标/键盘/WM_CHAR/滚轮/size/move/close/focus 事件队列（flat pop）、**StretchDIBits present**（BGRA top-down）、ShowWindow/GetWindowPlacement/WNDPROC/WM_CLOSE、剪贴板 UTF-8↔UTF-16、`begin_move`（ReleaseCapture + WM_NCLBUTTONDOWN/HTCAPTION）。
  - Kotlin：`Win32Native.kt`（@SymbolName externals + Win32Window 封装：minimize/maximize/restore/requestClose/beginMove/clipboard/pump/popEvent）、`SkiaLayer.windows.kt`（软件渲染 actual：Picture 记录 + raster）、`redrawer/WindowsSoftwareRedrawer.kt` + `context/WindowsSoftwareContextHandler.kt`（Surface.makeRaster → readPixels → usePinned present，BGRA/N32）、`Dispatchers.windows.kt`（MainUIDispatcher 队列式 + initWindowsMainThread/flush + SkikoDispatchers）、`SkikoNativeLinkage.windows.kt`、`SystemTheme.windows.kt=UNKNOWN`、`WaylandClipboard.windows.kt`（shim 兜底 compose-core 共享源码）。
- **compose-kn-windows 应用层（对齐 §10 全部功能）**：重写 `WindowsComposeWindow.run()`（Win32Window + attachTo + pump/flush/translate 循环渲染每帧），translate：W32→WindowsEvent 完整映射（MouseMove/Button/Wheel/Key/Char/Size/Move/Close/Focus）；`minimize()`=win32.minimize、`toggleMaximized()`=ShowWindow SW_MAXIMIZE/SW_RESTORE、`requestClose()`=WM_CLOSE、`beginMove()`=HTCAPTION —— 原 4 个 TODO 桩全部落地。
- **WM_NCCALCSIZE borderless**：内容全客户区自绘（同 Linux CSD 的体验），resize 交给 WM_NCHITTEST 的 HT* 由系统完成（对比 Wayland 需要 begin_resize 的 interactive-enum 模式，Windows 系统免费提供光标变化）。
- 已知 Architectural限制: Windows 上我们的 WindowsWindowChrome 仍会画一层标题栏(与 borderless 的 WM_NCCALCSIZE 配套)，三按钮走 ShowWindow/WM_CLOSE。

### 状态
| 平台 | 编译 | 可执行 | 测试 |
|------|------|--------|------|
| Linux (wayland) | ✓ | ✓ 运行且已测 8 方向 resize | ✓ |
| Windows (mingw cross) | ✓ 全部 Kotlin | ✗ 需 Windows 宿主链 | 待 Windows 机器 |


### GitHub CI 结果（2025-01-10 深夜）
- 仓库已建：github.com/TetraploidHuman/ComposeKN（private）。`.github/workflows/windows.yml` 在 windows-latest runner 上构建。
- CI 修掉的坑（务必记住）：
  1. **仓库里的 gradle.properties 绝不能带本机代理设置**（172.20.128.142:7897 已移到 ~/.gradle/gradle.properties）。CI 上代理不可达导致插件解析静默失败。
  2. build-logic/settings.gradle.kts 需要 pluginManagement { repositories { gradlePluginPortal/mavenCentral/google } }（included build 不继承主工程插件仓库）。
  3. Windows 宿主上 skiko compileNativeBridges 用 clang-cl.exe —— 编译 flags 必须用 `buildType.winCompilerFlags`（/std:c++20 /GR-），GNU 的 -std=c++2a 会被 clang-cl 吞掉掉到 C++14（症状：500 个 `std::optional/string_view/byte not found in namespace std`）。
  4. linkNativeBridges on Windows host 必须用 `llvm-lib.exe /out:`（lib.exe 变体），GNU 风格 `ar rcs` 不存在。
- **CI 终点 = 已知 ABI 边界**：Kotlin/Native 的 mingwX64 目标无论宿主都走 konan 自带 msys2 工具链（GNU/Itanium ABI），而 JetBrains skia Windows 骸库全部是 MSVC ABI（`?drawRect@SkCanvas@@QEAAX...`）。K/N 没有 windows-msvc 目标。此墙非配置可逾。
- 可行的出路（如需 Windows 原生 exe）：
  a) 自建 windows-gnu 版 Skia（skia 构建系统原生不出 mingw 目标，工程量大，不推荐）；
  b) Windows 用 Compose Desktop JVM 版（官方支持，skiko Windows natives 本就按 AWT 设计；本项目"脱离 JVM 的 native"路线在 Windows 上走不通，除非重写渲染后端）；
  c) 接受现状：Windows 的全部窗口管理/输入/渲染代码已写完并在交叉编译中验证到 ABI 边界之前，供未来接触 mingw-skia 或 MSVC-K/N 后继续。


## 12. mingw-Skia（GNU ABI）构建成功 —— Windows 原生路线的关键突破

### 结论
**Itanium-ABI（MinGW-w64/GNU）的 Skia 静态库已从源码构建成功**，ABI 与 Kotlin/Native 的
mingwX64 后端一致，符号验证：

| 来源 | SkCanvas::drawRect 符号 |
|------|------------------------|
| JetBrains 发布版（MSVC ABI，不可用） | `?drawRect@SkCanvas@@QEAAXAEBUSkRect@@AEBVSkPaint@@@Z` |
| **本项目构建（GNU ABI，可用）** | `_ZN8SkCanvas8drawRectERK6SkRectRK7SkPaint` |

### 产物（22 个静态库，out/mingw/）
libskia.a (33MB) libicu.a (13MB) libharfbuzz.a (12MB) libskparagraph.a (12MB) libskshaper.a (8.8MB)
libskunicode_core.a (33MB) libskunicode_icu.a (9.6MB) libskottie.a (8.6MB) libsksg.a (8.5MB)
libsvg.a (1.4MB) libfreetype2.a (8.6MB) libwebp.a (1.4MB) libpng.a libjpeg.a libjpeg12.a libjpeg16.a
libzlib.a libexpat.a libskcms.a libjsonreader.a libskresources.a libwebp_sse41.a

### 可复现流程（已固化进仓库）
- `vendor/skiko/skia-mingw/build-skia-mingw.sh` — 一键：克隆 → 打补丁 → 拉依赖 → gn+ninja
- `vendor/skiko/skia-mingw/skia-mingw.patch` — 14 个文件的 mingw 适配补丁（23KB）
- `vendor/skiko/skia-mingw/gn_args.txt` — GN 参数（可移植，无绝对路径）
- `vendor/skiko/skia-mingw/fetch_deps.py` — 按 DEPS 精确拉取 8 个第三方依赖（含重试）
- 构建命令：
  `nix-shell -p git gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run "SKIA_MINGW_WORK=<dir> ./vendor/skiko/skia-mingw/build-skia-mingw.sh"`

### 关键技术决策与踩坑（重要）
1. **工具链选择**：先试 konan 自带 clang 21 + msys2 头 → 缺 `<compare>` 等 C++20 头（msys2 libstdc++ 是 GCC 9.2）；
   换 nixpkgs mingw GCC 15 的 libstdc++ 头 + clang → 撞 `__is_integer<__int128>` 的 strict-ANSI 不一致；
   **最终选 nixpkgs mingw GCC 15 原生工具链**（编译器/libstdc++ 配套，最稳）。
2. **skcms / SkRasterPipeline 的 musttail**：GCC 15 的 `__has_cpp_attribute(clang::musttail)` 为真但无法编译这些
   尾调用（"argument must be passed by copying"）→ 用 `-DSKCMS_HAS_MUSTTAIL=0` +
   改 `src/core/SkRasterPipeline.h` 把 musttail 限定到 clang。
3. **`-std=c++20` 导致 `__STRICT_ANSI__`**：GCC 不再定义 `__GLIBCXX_TYPE_INT_N_0`，而 libstdc++ 的
   `max_size_type.h` 仍按 `__SIZEOF_INT128__` 选 `unsigned __int128` → `__is_integer` 无特化、静态断言失败。
   修法：`-D__GLIBCXX_TYPE_INT_N_0=__int128 -D__GLIBCXX_BITSIZE_INT_N_0=128`。
   （另需 `-fext-numeric-literals` 解决 `__float128` 的 `Q` 字面量。）
   ⚠️ 不要用 `-U__SIZEOF_INT128__`：会让 mingw `_mingw.h` 的 `__int128` typedef 与 GCC 关键字冲突。
4. **`__forceinline` / `__declspec`**：GCC 目标 Windows 时未定义（mingw 头里才有）→ 给
   `include/private/base/SkAttributes.h` 加 GCC 分支（`SK_ALWAYS_INLINE`/`SK_NEVER_INLINE`）。
5. **GN 里 `is_win` 语义过载**：需要区分「Windows 平台」与「MSVC 工具链」。引入 `is_msvc = is_win && !skia_mingw`，
   把 flag/库路径类的 `is_win` 全部改为 `is_msvc`（`gn/skia/BUILD.gn`、`gn/toolchain/BUILD.gn`、
   `third_party/third_party.gni` 的 `/w`→`-w`、`gn/portable`、`third_party/{zlib,icu,libjpeg-turbo,libwebp}`、顶层 `BUILD.gn` 的 `/arch:`）。
6. **`is_official_build=true` 默认使用系统库**：必须显式 `skia_use_system_* = false` 让 GN 从
   `third_party/externals` 构建（否则找不到 `png.h`/`ft2build.h`）。
7. **DirectWrite 头差异**：mingw-w64 的 `IDWritePaintReader`/`IDWriteFontFace4` 参数表与 Windows SDK 不同
   （多一个 `struct_size` 参数、`GetGlyphImageFormats_` 带下划线、`SetTextColor` 取指针）→
   在 `src/ports/SkScalerContext_win_dw.cpp` 用 `SK_DW_*` 宏按 `__MINGW32__` 归一化，**功能保留**。
8. **GPU 全部关闭**（`skia_enable_ganesh=false` 等）：Windows 渲染走软件光栅（StretchDIBits），
   不需要 GL/Vulkan/Dawn/ANGLE，省掉海量依赖。

### ★ 最终链接成功（同日完成）★
**`windows-demo.exe` (27MB) 已成功链接** —— Kotlin/Native mingwX64 + GNU-ABI Skia。
依赖仅系统 DLL（msvcrt / ucrtbase / ntdll / kernel32 / user32 / gdi32 / advapi32），
**无需附带任何额外运行时 DLL**，单文件即可运行。

#### 打通链接的三个关键点
1. **`-include-binary` 路径**：klib 的 `targets/mingw_x64/included/` 才是真正参与链接的库
   （KLIB 里的 `linkerOpts` 不会传到最终链接！Linux 侧靠的是 cinterop def 的 linkerOpts）。
   → 在 `NativeTasksConfiguration` 里用 gradle 属性 `skiko.skia.mingw.dir` 覆盖 `nativeArchives`。
2. **GPU 入口屏蔽**：软件光栅用不到 Ganesh，给 `SKIKO_MINGW_NO_GPU` 定义 + 在
   `Surface.cc`（WrapBackendRenderTarget / RenderTarget）与 `Image.cc`（AdoptTextureFrom）
   里 guard 掉 3 个 GPU 入口（保留 C 符号，返回 nullptr）。
3. **libstdc++ 版本差**：Skia 用 GCC 15 头编译（需要 C++20），konan 链接的是它自带的
   静态 libstdc++ **GCC 9.2** → 缺 3 个 GCC 11+ 符号。用
   `vendor/skiko/skia-mingw/shim/libstdcxx-symbols-shim.cpp` 显式实例化补齐
   （`_M_replace_cold` 定义直接取自安装的 `<bits/basic_string.tcc>`），
   **避免引入第二个 libstdc++（DLL）**，保持单一 C++ 运行时。
   另需 4 个导入库：`libmcfgthread.a`（GCC15 的 threading）、
   `libmsvcrt.a`/`libucrtbase.a`（`__timezone`/`__tzname`/`stat64i32`/`__imp__strtof_l`）、
   `libntdll.a`（mcfgthread 的 `Nt*`）、`libmingwex.a`（`__mingw_fix_stat_path`）。

#### 构建命令（已固化为脚本）
```bash
# 1) 构建 GNU-ABI Skia
nix-shell -p git gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run \
  "SKIA_MINGW_WORK=<dir> ./vendor/skiko/skia-mingw/build-skia-mingw.sh"

# 2) 端到端链接出 exe（含 shim 构建）
nix-shell -p gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run \
  "SKIA_MINGW_WORK=<dir> ./vendor/skiko/skia-mingw/build-windows-native-demo.sh"
```

### 下一步（尚未完成）
- [ ] **Windows 上实机运行验证**（本机是 Linux，只能验证链接；需跑
      `windows-demo.exe` 确认窗口/输入/软件光栅渲染）。
- [ ] CI 化：把 skia 构建产物缓存为 artifact，避免每次 ~20 分钟重建；
      可加 "启动 exe 3 秒不崩溃" 的 smoke test。
- [ ] skia 构建目前关闭了 GPU（ganesh/vulkan/dawn）；若将来需要 GPU 加速需另行处理。

---

## 13. Windows 原生 exe「启动即静默退出」根因与修复（2025-09-13）

### 症状
`ComposeKN-Windows-Native-Debug.exe` 在 Windows 10 实机与 Wine 上表现一致：
**进程干净退出、退出码 1、stdout 无任何输出、连 `composekn-startup.log` 都不生成**。

### 定位过程（Wine + winedbg）
1. `WINEDEBUG=+seh` 只看到无从判断的噪音；`hello.exe`（普通 mingw）与最小 K/N
   `konan` exe 都能正常跑 → 排除 Wine 与 K/N 运行时本身。
2. winedbg 在绝对地址下断点：命中 `mainCRTStartup`(0x1400014f0) → `__tmainCRTStartup`(0x140001180)
   → `_initterm`(XI/XC) → `_pei386_runtime_relocator` → `__mingw_init_ehandler` → `_fpreset`
   → `__main`(0x1407e3050) → `__do_global_ctors`，**之后再无断点命中，进程即终止**。
3. 从 PE 文件里解出 `__CTOR_LIST__`（refptr @0x141353460 → 0x141458548）：
   `-1` + 64 个构造器 + NULL 终止符，表本身是好的。
4. 逐个构造器下断点 → 第一个被调用的是 `0x1411603e0: jmp __gcc_register_frame`
   （`crtbegin.o` 的静态构造器，函数体只有一句 `atexit(__gcc_deregister_frame)`）。
5. `stepi` 单步跟进 `atexit`(0x140001520) → `call _onexit`(0x1407e1b40) →
   **`_onexit` 内部又 `call atexit`** → 无限相互递归 → 栈溢出 → 静默退出。

### 根因
两套 CRT 混链：

| 来源 | 实现 |
|---|---|
| konan 自带 sysroot 的 `crt2.o` | `int atexit(void(*f)(void)) { return _onexit(f) ? 0 : -1; }` |
| nixpkgs mingw-w64 (UCRT) 的 `libucrtbase.a` / `libmsvcrt.a` 各自带的 `_onexit` 成员 | `int _onexit(_onexit_t f) { return atexit(f) ? f : NULL; }` |

第一个 `atexit` 调用点就是被 `crtbegin.o` 注册的 `__gcc_register_frame`，
于是进程在 `__do_global_ctors` 阶段直接递归爆栈。

### 修复
1. **`shim/libstdcxx-symbols-shim.cpp` 导出 `_onexit`**（返回入参 = 注册成功，不真正登记），
   shim 归档排在所有 UCRT 导入库之前，递归即被打断。
   代价：退出时不会执行 `atexit` 注册的回调（含 C++ 全局析构）；本项目只有
   `__gcc_register_frame`（注册一个空函数）用到，因此无影响。
2. **构建脚本里删掉 nixpkgs UCRT 库中的 `_onexit` 成员**（`ar d`），避免重复定义。
3. **`-Pskiko.mingw.libDirs` 不能包含 nixpkgs 的 MinGW 库目录**：一旦包含，
   konan 默认的 `-lmingw32` 会解析到 nixpkgs 那个精简版 `libmingw32.a`，
   而 `mingw_app_type` / `__security_init_cookie` / `__mingw_init_ehandler` /
   `mingw_initlts*_force` 只存在于 konan 自带 sysroot 的 `libmingw32.a` 里
   → 链接报 7 个未定义符号。nixpkgs 的库全部用绝对路径给出，不需要 `-L`。

### 修复后进展
Kotlin `main` 已能执行、窗口创建成功（`WM_CREATE`/首次 `WM_PAINT`、952x606 客户区）、
`composekn-startup.log` 正常生成。

### 两个遗留问题
1. **ICU 数据文件**：Skia 的 `SkLoadICU()` 在运行时按 `<exe目录>\\icudtl.dat` 查找 ICU 数据
   （见 exe 内宽字符串 `SkLoadICU: datafile '%s' is missing`）。构建产物
   `skia/out/mingw/icudtl.dat`（10 MB）必须随 exe 一起分发，否则文本排版会失败，
   并且失败路径会向 `std::wcerr` 打印 —— 而 `wcerr` 在这个混链里 vptr 为 NULL，直接 access violation。
   （后续可考虑把 icudtl.dat 嵌进 exe 并在启动时释放到 exe 同目录，保持单文件。）
2. **~10 秒后 GC 崩溃**：Wine 下 `kotlin::alloc::FixedBlockPage::Sweep<ObjectSweepTraits>`
   读 `0x100000008` 崩溃。怀疑是 Wine 对 K/N GC 的线程挂起支持不完善
   （可能是 Wine 特有），需要在 Windows 实机验证。

### 13.1 追加修复：Kotlin 侧 Win32 桥接的指针语义错误（2025-09-13 深夜）

修掉 `atexit` 递归后，窗口能创建、但暴露出两个新症状：
**① 窗口标题与日志全是乱码；② 启动约 10 秒后 GC 崩溃**
（Wine 与 Windows 实机完全一致，`kotlin::alloc::FixedBlockPage::Sweep` 读到坏对象指针）。

根因都在 Win32 桥接的 Kotlin 声明上：

1. **字符串按 Kotlin 对象指针传入**。Linux 桥接用的是
   `external fun f(s: CPointer<ByteVar>)` + 调用处 `f(s.cstr.ptr)`；
   Windows 桥接却写成了 `external fun f(s: String)` —— 传过去的是 Kotlin String
   对象指针，C 侧按 `const char*` 处理，于是标题/日志乱码。
   本工程使用的 K/N 版本里 `"x".cstr` 返回 `CValues<ByteVar>`，而
   `CValues<ByteVar>.ptr` 这个扩展在此 target 不可用；直接用 `CValuesRef<ByteVar>`
   作 external 参数时传过去的又是「CValues 包装对象」的地址，仍是乱码。
   **最终做法**：手工 pin 一个 NUL 结尾的 UTF-8 `ByteArray` 并用 `addressOf(0)`
   取首地址（见 `Win32Native.kt` 的 `String.useCString {}`），参数类型用 `CPointer<ByteVar>`。

2. **数组按 Kotlin 对象指针传入 → 直接写坏 Kotlin 堆**。
   `composekn_win32_pop_event_flat` 原来声明了 8 个 `IntArray/FloatArray/UIntArray`
   参数，C 侧往里写就等于往 Kotlin 堆对象头上写，堆被破坏后几秒 GC 一跑就崩。
   已改成 `CValuesRef` 之外的正规做法：`memScoped { alloc<IntVar>() ... .ptr }`。
   `composekn_win32_clipboard_get_text` 的 `ByteArray` 同病，改成
   `usePinned { it.addressOf(0) }`。

修复后（Wine，带 `icudtl.dat`）：`main: entry` 日志正常、窗口标题正确
`title="ComposeKN Windows Demo"`、连续运行 60 秒无崩溃。

### 13.2 追加修复：Compose 界面全白（present 从未被调用）

修完指针语义后 exe 能稳定运行，但**窗口客户区整片空白**（Wine 截图确认）。
加了临时日志后一次定位：

```
swctx.initCanvas: 952x606 changed=true      <- surface/canvas 都建好了
swctx.flush #1: grab=false ... size=0x0     <- surfaceWidth/Height 一直是 0
```

`WindowsSoftwareContextHandler.initCanvas()` 创建 raster `Surface` 时**漏了记录尺寸**：
`surfaceWidth/surfaceHeight` 恒为 0 → `grabPixels()` 里 `if (w <= 0) return false`
→ `pixels` 永远为空 → `present()` 静默 return → 一帧都没 blit。
（副作用：`isSizeChanged()` 恒为 true，每帧都白白重建一次 surface。）

修复：创建 surface 后记录 `surfaceWidth = w; surfaceHeight = h`。
验证（Wine + icudtl.dat + 截图）：窗口完整渲染出
CSD 标题栏（含三个按钮）、标题文字、输入框、按钮 —— 与设计一致。

### 13.3 待验证：窗口缩放后的重绘
Wine 下没有窗口管理器，从 X 侧（xdotool）缩放无法转成 Win32 的 `WM_SIZE`，
因此「缩放后内容是否跟随重绘」这条路径**只能在真机验证**。
`Win32Native.kt`/`WindowsSoftwareContextHandler` 已加轻量日志：
- `event: resize WxH`（应用层收到 WM_SIZE）
- `swctx.present: surface=WxH window=WxH`（每次 surface 尺寸变化时记录一次）

### 13.4 窗口外观/DPI/缩放闪白修复（真机反馈）

真机截图暴露的四个问题与修复：

1. **两条标题栏**（系统标题栏 + 自绘 CSD 栏各一条）
   根因：`WM_NCCALCSIZE` 只在 `wParam != 0` 时返回 0 去掉非客户区，窗口创建
   (`wParam == 0`) 时 `break` 走了 `DefWindowProc` → 系统标题栏被画出来。
   修复：两种 `wParam` 都返回 0、不调用 `DefWindowProc`。
   验证：客户区从 944x601（含边框）变为整窗 960x640，截图中只剩自绘标题栏。
   （顺带修掉「拖拽缩放时系统标题栏消失」——那正是 `wParam != 0` 分支造成的。）

2. **缩放过程中整窗闪白**
   `WM_ERASEBKGND` 用类背景刷（白色）擦除，且 `WM_PAINT` 只 `BeginPaint/EndPaint`。
   修复：`WM_ERASEBKGND` 返回 1（不擦背景）；`present()` 缓存最近一帧像素，
   `WM_PAINT`/`blitFrame()` 立刻把缓存帧（按当前客户区拉伸）贴回去。

3. **HiDPI 下 UI 发糊**
   exe 没有 DPI 感知，Windows 把整个窗口位图拉伸。修复：启动时调用
   `SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2)`（失败回退 `SetProcessDPIAware`）。
   同时 `composekn_win32_width/height` 改为返回**逻辑像素**（物理 / dpiScale），
   新增 `composekn_win32_dpi_scale`；渲染表面 = 逻辑 × scale = 物理像素，
   与客户区 1:1，既不模糊也不过小。Kotlin 侧 `Win32Window.dpiScale` 不再硬编码 1.0f。

4. **缩放后重绘**
   由第 2 条的帧缓存 + `blitFrame()` 覆盖：缩放/重绘期间窗口显示上一帧（拉伸），
   不再出现白屏空窗。

### 13.5 首帧尺寸/密度 + CSD 标题栏按钮（真机第二轮反馈）

1. **刚启动时 UI 特别小，缩放一次才恢复正常**
   根因：`scene.density` 只在收到 `ResizeEvent` 时才设置，而首帧渲染早于该事件，
   于是首帧用默认 density(1.0) 布局 —— HiDPI 下画布是物理像素、density 却是 1，
   UI 就挤在左上角。
   修复：`SkikoRenderDelegate.onRender()` 里每帧同步 `scene.density = Density(contentScale)`。

2. **标题栏按钮显示不全/图标变形、悬浮无反馈**
   根因：按钮用 Material3 `TextButton` + 字形文本（`−` `□` `×`）。
   `TextButton` 自带 58x40dp 最小尺寸与 12dp 内边距，塞进 44x32 的框里会被裁切；
   这些 Unicode 字形在部分 Windows 字体下还会缺字回退（真机截图里 × 变成了 ∨）。
   修复：改为 `Canvas` 直接画矢线（− □ ×，最大化时画双矩形表示还原），
   尺寸完全可控；并用 `hoverable` + `collectIsHoveredAsState` 补上 hover 高亮。
   （注意：本工程 CMP 版本没有 `pointerMoveFilter`，用 `hoverable` 实现。）

3. **已知未修**：拖拽缩放过程中画面是被"拉伸/压缩"的旧帧，松手后才重绘正确。
   原因是 Windows 的模态缩放循环运行在 wndproc 内，Kotlin 侧渲染循环在该期间
   完全得不到执行；当前靠 `blitFrame()` 拉伸缓存帧来避免白屏。
   彻底解决需要新增 C++ -> Kotlin 的渲染回调（`staticCFunction`），
   在 WM_SIZE/WM_ENTERSIZEMOVE 里同步调用一次 `renderImmediately()`。

### 13.6 拖拽缩放逐帧重组（C++ -> Kotlin 渲染回调）

问题：拖拽缩放时窗口显示的是**上一帧被拉伸**的结果，松手后才重绘正确。
原因：Windows 的模态缩放循环运行在 `DefWindowProc` 内部，这期间 Kotlin 侧渲染
循环完全得不到执行（消息泵被模态循环占住）。

修复：
1. C++ 新增 `composekn_win32_set_render_tick(fn, user)`，保存 Kotlin 传来的
   函数指针；`WM_SIZE` 更新完 `window->width/height` 后同步调用 `fireRenderTick()`
   （带 `g_inRenderTick` 防重入）。
2. Kotlin 侧 `Win32Native.kt` 用 `staticCFunction<COpaquePointer?, Unit>` 做回调，
   经 `setWindowsRenderTick { ... }` 注册；`WindowsSoftwareRedrawer.init` 里注册
   `renderImmediately()`，`dispose` 里注销。

验证（Wine + 调试钩子）：每次 `SetWindowPos` 都立刻出现
`swctx.present: surface=WxH window=WxH`（尺寸已跟上新窗口），随后才是
`event: resize WxH`，说明内容确实按新尺寸逐帧重组，而不是拉伸旧帧。

调试钩子：设 `COMPOSEKN_TEST_RESIZE=1` 时，窗口创建后会自动 `SetWindowPos`
来回缩放 6 次（Wine/Xvfb 下没有窗口管理器，无法从外部触发 WM_SIZE）。

## 14. UI 组件画廊 + 自动化测试（2026-09-13）

### 14.1 组件画廊（`samples/windows-demo`）

原来的 demo 只有 2 个控件，覆盖面太窄。现在拆成三个文件：

| 文件 | 作用 |
|---|---|
| `main.kt` | 入口：默认跑画廊；`--selftest` / `COMPOSEKN_SELFTEST` 时跑自检并以退出码汇报 |
| `Gallery.kt` | 组件画廊（下方清单） |
| `SelfTest.kt` | 自动化自检（§14.2） |

画廊刻意覆盖**渲染 / 排版 / 输入**的各类路径（每一条都是潜在的后端缺陷面）：

- 文本：标题/正文/标签字号、长文本换行 + `TextOverflow.Ellipsis`、中英混排、28sp 大字号
- 按钮：`Button` / `FilledTonalButton` / `OutlinedButton` / `TextButton` / `IconButton`（Canvas 画图标）
- 输入：`OutlinedTextField`（单行 + 多行）、`Checkbox`、`Switch`、`Slider`、`RadioButton` 组
- 进度：确定性 `LinearProgressIndicator` + 不确定 `CircularProgressIndicator`（无限动画）
- 容器：`Card`（圆角/直角）、`HorizontalDivider`、hover 高亮的 `Box`
- 弹层：`DropdownMenu`、`AlertDialog`（验证 Skiko 多层合成）
- 列表：`LazyRow`（40 项）、`LazyColumn`（60 项，验证虚拟化）；外层 `verticalScroll`
- 绘制：`Canvas` 矩形/圆/描边/线/`linearGradient`/`radialGradient`/`Path.quadraticBezierTo`/`Modifier.rotate`
- 主题：`darkColorScheme`/`lightColorScheme` 切换、`primary/secondary/error` 取色
- 布局：`FlowRow` 自动换行、`BoxWithConstraints` 读取可用尺寸
- 诊断 HUD：窗口逻辑尺寸、可用尺寸、density、滚轮位置、交互计数、**每帧重组计数**
  （`LaunchedEffect { while(true) withFrameNanos { frames++ } }`，等于持续压力测试）

HUD 让「拖拽缩放是否逐帧重组」「HiDPI 下 density 是否对」这类问题**肉眼可读**，
不用再靠猜。

### 14.2 自检：`COMPOSEKN_SELFTEST=1|logic|window|all`

exe 内置三层自检，结果逐行打印 `SELFTEST ok / FAIL`，最后一行 `SELFTEST: RESULT PASS|FAIL`，
**退出码 0/1**（可直接被 script / CI 断言）：

| 层 | 跑什么 | 需要窗口吗 |
|---|---|---|
| `logic` | 纯函数：VK→Key 映射表（含「表内无重复键」断言）、lParam/滚轮增量解码、修饰键位、输入状态机、`GalleryProbe.summary()` | 否 |
| `render` | **离屏光栅化真实 Compose 场景 + 像素断言** | 否 |
| `window` | 真实 Win32 窗口：剪贴板往返、逐帧渲染循环、合成点击/滚轮、干净退出 | 是 |

`render` 层的关键断言（`SelfTest.kt`）：

- 像素通道序自检（`N2`(BGRA)→ARGB 转换搞反会让所有颜色断言失效，所以它排第一）
- 基准帧：背景色、CSD 标题栏颜色与 32dp 高度、标题栏下方是内容区
- **「拉伸回归」检测**：右下角固定 40dp 红方块，窗口 800x600 → 1200x900 后宽度必须**仍是 40px**；
  若渲染后端把旧帧拉伸（v0.2.6 的行为），这里会变成 60px
- HiDPI：`density=1.5` 时同一个方块必须是 60px（验证 逻辑dp→物理px 整条链）
- 交互：合成 点击 后 `probe.clickCount==1` **且**该处像素由橙变绿（验证 输入→重组→重绘 全链路）
- 交互：合成 WM_CHAR 后输入框内容 == "CK"（验证焦点 + 文本输入管道）
- 交互：滚轮后 `ScrollState.value > 0` **且**滚动区域像素发生变化；反向滚一格必须滚回去（锁死方向）
- 回归：**首帧渲染之前**派发鼠标事件不得崩（`LazyColumn` 在无界约束下会抛异常）
- 画廊整体能渲染出 > 40 种颜色（不是白屏）

关键实现细节（都踩过坑）：

1. `WindowsComposeApplication` 必须尽早调用 **`initWindowsMainThread()`**。
   Skiko 的 `WindowsMainDispatcher` 只在 `isWindowsMainThread()` 为真时 inline 执行任务，
   否则一律入队，而排空队列的 `flushMainUIDispatcher()` 自己也会先检查这个标志 —— 漏掉它，
   所有 `launch`/`LaunchedEffect`/`snapshotFlow` 静默不执行（滚轮就是被这个坑掉的，
   见 §14.4）。
2. 离屏驱动必须用**单调递增**的帧时钟（`OffscreenDriver`）。`renderOffscreen()` 每次从 0 开始，
   连续调用会让 `withFrameNanos()` 收到倒退的时间戳，动画/惯性滚动会算错。
3. 像素断言点要避开涟漪（Material indication 是半透明叠加层）。自检界面里
   `clickable(indication = null)`，这样「点击后颜色」才是确定的纯色。
4. `ScrollState` 由测试注入并直接读 `.value`，不依赖 `snapshotFlow` 的调度时机。

### 14.3 单元测试：`:compose-kn-tests`（linuxX64 + kotlin.test）

```bash
./gradlew :compose-kn-tests:linuxX64Test     # 20 个用例，秒级
```

为什么能在 Linux 上测 Windows 代码：把**平台无关**的那部分从 `compose-kn-windows` 抽到
`src/windowsCommonMain/kotlin`（`WindowsEvent` / `WindowsInputState` / `WindowsKeyMapper` /
`WindowsInputMapper` / `internal/Win32Structures`），`mingwX64Main` 与 `:compose-kn-tests`
的 `linuxX64Main` 各自 `srcDir` 同一份源码。mingwX64 的测试二进制在 Linux 上跑不了，
但纯逻辑用 linuxX64 编译执行完全等价。

覆盖：映射表无重复键、四类修饰键左右区分、OEM 键码、Unicode 码点、
`GET_X/Y_LPARAM` 的**符号扩展**、滚轮增量解码、`MK_*` 与桥接位掩码**不是同一套编码**、
按键按下/抬起（抬起不带码点）、指针状态机。

> 这层测试是有牙齿的：把 `0xDC` 改回 `0x5C`（历史上那个重复键 bug）后，
> `20 tests completed, 2 failed`。

CI：`.github/workflows/tests.yml`（ubuntu-latest，apt 装 wayland/EGL/xkbcommon 开发包）。

### 14.4 自检抓到的真实 bug（本次）

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1a | **任何滚动容器都不响应鼠标滚轮**（`LazyColumn`/`verticalScroll`/`LazyRow` 全都不动） | `foundation` 的 K/N 原生 `platformScrollConfig()` 返回 `calculateMouseWheelScroll = Offset.Zero`（overlay 里的占位实现），`MouseWheelScrollingLogic` 拿到 0 直接 return。mingwX64 复用 `linuxX64Main` 源集，**Linux 与 Windows 原生一起中招** | 改为真实实现：Windows 按「一格 = 视口高度/20」换算，Wayland 直接透传像素；用 skiko 的 `hostOs` 在运行时区分（见 `vendor/compose-core.local/overlay/foundation/.../LinuxScrollable.linux.kt`） |
| 1b | 修完 1a 后**滚轮仍然完全不动**（配置已算出非零 delta，见下方诊断输出） | `ComposeScenePointer` 传进来的 `scrollDelta` 被 mapper 取了负号。`scrollable` 内部对 `verticalScroll`/`LazyColumn` 的 `reverseDirection` 默认是 **true**，`canConsumeDelta` 会先 `reverseIfNeeded()`：符号反了 → `canScrollBackward` → 在 `value==0` 处判定「不可消费」→ 事件被丢弃（连 1px 都不滚）。mapper 里那行 `-event.deltaY` 是凭「正数=向下」的直觉写的，和 Compose 的实际约定相反 | `WindowsInputMapper` / `WaylandInputMapper` 都改成**原样透传**（Win32 的 delta/120 与 wl_pointer.axis 的 value 本身就是「负数=向下滚」）。自检新增 `interaction/wheel-up-scrolls-back` 双向锁死方向 |
| 9 | 真机/画廊**启动瞬间崩溃**：`IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` | `scene.size` 只在 `renderFrame()` 里设置；而窗口消息循环是「先 `translateAndDispatch` 再 `renderImmediately`」，且窗口刚出现时消息泵里通常已经有一串鼠标 `Enter/Move` → `sendPointerEvent` 在 `scene.size == 0` 时触发 `measureAndLayout`，根节点拿到无界约束 → 内容里任何 `LazyColumn`/`verticalScroll` 当场抛异常 | ① `WindowsComposeApplication.setContent()` 先按构造参数估一个初始 `scene.size`；② `WindowsComposeWindow.run()` 在进入循环前先渲染一帧。自检新增 `render/event-before-first-render`（故意渲染前派事件） |
| 2 | 离屏渲染时**所有协程静默不执行** | `WindowsComposeApplication` 未标记 UI 主线程，`WindowsMainDispatcher` 只入队不执行 | `init` 里调用 `initWindowsMainThread()` |
| 3 | 右 Win 键被映射成反斜杠、`MetaRight` 永远取不到 | `mapOf` 里 `0x5C` 写了两次（`VK_RWIN` 与「反斜杠」），后者静默覆盖前者。反斜杠应该是 `VK_OEM_5`=0xDC | 修正键码 + 保留 pair 列表，新增 `duplicateVkEntries()` 断言（单测 + 自检都查） |
| 4 | 「按住 Ctrl 点击」被当成 Shift | `MOUSE_BUTTON` 分支用 `modifiers and 1u`（=Shift 位）判 Ctrl | 改用 `MOD_CTRL`；并新增 `Win32Modifier` 与 `MK_*` 的区分注释与断言 |
| 5 | 鼠标消息里的修饰键**永远是全 false** | `handleEvent` 里硬编码 `updateModifiersFromMouse(0)`；`updateModifiers(wParam)` 读的是 0x1000/0x2000/0x4000（既不是 MK_* 也不是桥接位） | `updateModifiers` 走桥接位掩码；三个鼠标分支改用事件自带的布尔标志 |
| 6 | 拖到窗口左上角外时坐标变成 65535 | `GET_X_LPARAM` 用 `and 0xFFFF`（无符号），丢失符号位 | 改成 `(it shl 16) shr 16`（16 位有符号语义） |
| 7 | 构建脚本 SIGPIPE 自杀（**退出码 141、无任何输出**） | `nm ... \| awk '... exit'`：awk 提前退出 → nm 收 SIGPIPE → `set -o pipefail` + `set -e` 直接干掉脚本 | 先整段收下 `nm` 输出，再让 awk 读完全部输入（不在规则里 `exit`） |
| 8 | `libmcfgthread.a` 找不到（链接期才炸） | `x86_64-w64-mingw32-g++ -print-file-name=libmcfgthread.a` 找不到时原样返回 basename，`dirname` 得到 `.`，而判断用的是 `[ -d "$MCF_LIB" ]`（`.` 存在 → 走错分支） | 改为判断**文件**是否存在，回退用 `find ... -print -quit`（不用 `\| head -1`） |

### 14.5 一键测试：`scripts/test-windows-native.sh`

```bash
# 在仓库根目录（会自己起 Xvfb —— NixOS 的 xvfb 包没有 xvfb-run）
nix-shell -p wine64 xvfb xauth imagemagick xwininfo --run ./scripts/test-windows-native.sh
```

阶段：`logic` → `window` → `screenshot`（外部验证：抓窗口 PNG，检查标题栏 `#2D2D30`
与颜色数 ≥ 200，即「不是白窗口」）。可选 `--skip-build`（用现有 exe）、
`--no-screenshot`、`--only=<phase>`；产物与日志在 `/tmp/composekn-wintest/`。

在真机（Windows 10/11）上等价操作：

```powershell
$env:COMPOSEKN_SELFTEST = "all"
.\windows-demo.exe            # 退出码 0 = 全绿；结果在 stdout + composekn-startup.log
```

### 14.5.1 一次典型的排查过程（滚轮为什么还是不动）

改完 `NativeScrollConfig` 之后滚轮依然纹丝不动。自检只能给出「`value=0`、像素零变化」，
无法区分「事件没到」/「delta 被算成 0」/「算对了但没应用」，于是临时在三个位置加
`println`（`WindowsInputMapper` → `LinuxScrollable` 的配置 → 上游
`MouseWheelScrollingLogic.onMouseWheel`），一轮链接（7 分钟）就定位了：

```
WHEELMAP: dispatch at (400,540) rawDeltaY=-3 -> scrollDelta=Offset(0.0, 3.0)
WHEELLOGIC: startReceivingEvents ctx=CoroutineScope(... FlushCoroutineDispatcher ...)
WHEELLOGIC: receiver coroutine started
SCROLLCFG: delta=Offset(0.0, 3.0)
WHEELLOGIC: onMouseWheel delta=Offset(0.0, 18.0) reversed=Offset(0.0, -18.0) canConsume=false
                                                                 ^^^^^^^^^^^^^^^^
```

`canConsume=false` 就是全部答案：事件到了、配置算出了 18px、接收协程也起来了，
但符号反了导致 `canScrollBackward`（value=0 处不可用）。**能观测的信号要直接指向判定点**，
比反复猜测快得多。

### 14.6 后续项（尚未做）

> **2026-09-13 第二轮更新**：下面第 1/2/3 项已完成（见 §14.8 补充断言、§14.9 第 5 个真 bug、
> §14.10 CI）；第 4 项（Wayland 滚轮方向真机复验）仍待办，另新增第 5 项。

1. ~~CI 上跑 Windows 原生自检~~ ✅ 见 §14.10（`windows-native-selftest.yml`）
2. ~~弹层（`DropdownMenu`/`AlertDialog`）像素断言~~ ✅ 见 §14.8
3. ~~选区/光标/`Ctrl+A`/`Ctrl+C` 断言~~ ✅ 见 §14.8 —— 补断言过程本身抓出了第 5 个真 bug（§14.9）
4. Wayland 侧滚轮方向需要真机/合成器复验（目前只在 Windows 路径上做了端到端断言）。
5. ~~CI job 首次跑绿之后把 `continue-on-error` 删掉~~ ✅ 2026-09-13 已跑绿并删掉
   （`build_skia` 兜底入口保留）。

### 14.7 本次自检结果（Wine + Xvfb，Linux 主机）

```
$ nix-shell -p wine64 xvfb xauth imagemagick xwininfo \
      --run ./scripts/test-windows-native.sh --skip-build
==> 阶段 logic (COMPOSEKN_SELFTEST=logic)
✓ logic: RESULT PASS (73 checks)
==> 阶段 window (COMPOSEKN_SELFTEST=window)
✓ window: RESULT PASS (18 checks)
==> 阶段 screenshot（抓真实窗口 PNG 并检查像素）
✓ screenshot: 窗口已抓取（74072 bytes）
✓ screenshot: 标题栏颜色 = srgb(45,45,48)
✓ screenshot: 颜色数 1261（界面确实画出了内容，不是空白窗口）
全部通过 ✅
```

```
$ ./gradlew :compose-kn-tests:linuxX64Test       # 20 个用例全绿
```

### 14.8 补充断言：弹层 / 焦点 / 选区 / 剪贴板（第二轮）

`logic` 阶段 **50 → 73** 条，`window` 阶段 **8 → 18** 条。

**`logic`（离屏光栅化 + 像素/状态断言）新增 23 条：**

| 断言 | 断言的是什么 |
|---|---|
| `window-info/container-size` | 宿主把窗口尺寸喂给了 `LocalWindowInfo`（第 5 个 bug 的护栏，见 §14.9） |
| `focus/initially-unfocused` … `focus/unfocused-field-identical` | 点击让**左**输入框获得焦点；聚焦后左框区域像素发生变化，而右边那个一模一样的对照框**一个像素都没变** —— 证明「焦点可见」（边框 + 光标），不只是内部状态 |
| `caret/click-right-end` / `caret/click-left-start` | 点击定位光标：点最右端 → 选区到末尾，点文字左侧 → 回到开头（走「文本排版 → 命中测试 → 选区」全链路） |
| `selection/ctrl-a-selects-all` | Ctrl+A 全选 = `TextRange(0, 2)` |
| `selection/typing-replaces-selection` | 有选区时输入**替换**选区而不是插入 |
| `selection/backspace-deletes-selection` | 有选区时退格删除整段 |
| `popup/closed-no-pixels` / `open-pixel-count` / `open-bounds` / `anchored-at-offset` | `Popup` 图层：关闭时画面上一个弹层色像素都没有；打开后包围盒恰好 60×40，且左上角落在锚点 `(400, 200 + chrome)` 上 |
| `menu/closed-region-is-background` / `menu/open-draws-content` | `DropdownMenu`：关闭时那块区域是纯背景；打开后出现大量非背景像素 |
| `dialog/closed-corner-is-chrome` / `closed-content-is-bg` / `scrim-dims-content` / `visible-content-row` / `centered-bright-surface` | `AlertDialog`：遮罩把内容区压暗、中间一行整体变亮 —— 也就是对话框真的在**窗口中间**，而不是塌在左上角 |

**`window`（真实 Win32 窗口 + 合成按键）新增 10 条：**

| 断言 | 断言的是什么 |
|---|---|
| `window/container-size-nonzero` | 真实窗口里容器尺寸也非 0 |
| `window/compose-clipboard-manager` / `-readback` / `-reaches-win32` | Compose 层 `LocalClipboardManager` → skiko 的 Windows 桥接 → **Win32 剪贴板**，三条路径看到同一份内容 |
| `window/ctrl-a-selects-all` | 合成 Ctrl+A 真的让文本字段全选 |
| `window/ctrl-c-keeps-text` / `window/ctrl-c-copies-to-win32` | Ctrl+C 把选中的 `CK` 写进 Win32 剪贴板，且不改动文本 |
| `window/ctrl-x-clears-field` / `window/ctrl-x-copies` | Ctrl+X 剪切：文本框清空 + 剪贴板拿到内容 |
| `window/ctrl-v-pastes` | Ctrl+V 从剪贴板粘回来 |

> ⚠️ **实测踩到的时序坑（很重要）**：`TextFieldValue`（legacy API）和 `TextFieldState`
> （新 API）之间的**选区同步是按帧走的**。如果在同一个事件突发里「Ctrl+A 紧接着
> Ctrl+C」，复制会看到还没同步过去的**折叠**选区 → `copyWithResult()` 直接 `return`，
> 剪贴板不变。症状极具迷惑性：**第一次 Ctrl+C 毫无反应，第二次（选区已同步）就正常**。
> 所以自检里把「选择」和「复制 / 剪切」分到不同的帧（36 / 42 / 58 帧），这也和真实用户
> 按键之间的间隔一致。排查记录：单帧突发版本是 `1 failed`（`ctrl-c-copies-to-win32`），
> 而紧接着的第二次复制断言是绿的 —— 这就是定位到「同步按帧走」的线索。

### 14.9 补断言抓到的第 5 个真 bug：弹层全部塌到窗口左上角

**症状**：新加的三条弹层断言全红 —— 弹层色像素的包围盒是 `(0,0)-(59,39)`（位置错），
下拉菜单在目标区域里**一个像素都没有**，对话框把左上角盖成白色。

**根因**：宿主从来没把窗口尺寸喂给 `LocalWindowInfo`。
`WindowsPlatformContext` 用的是 `PlatformContext by PlatformContext.Empty()`，而
`PlatformContext.Empty()` 自带的 `WindowInfoImpl` 里 `containerSize` 恒为 `IntSize.Zero`，
没有任何人改过它。而 Popup/Dialog 的定位完全依赖它：

- `Popup.skiko.kt` 的 `rememberPopupMeasurePolicy` 会把算出来的位置过一遍
  `clipPosition(position, contentSize, containerSize)`：
  `position.x.coerceIn(0, containerSize.width - contentSize.width)`
  —— `containerSize` 为 0 时区间是 `coerceIn(0, 负数)`，**任何坐标都被夹到 `(0,0)`**；
- `Dialog.skiko.kt` 用 `containerSize` 把对话框摆到窗口中央 —— 为 0 就摆在 `(0,0)`。

对照组（都是宿主负责喂）：
`ImageComposeScene.skiko.kt` 里 `containerSize = imageSize`；
compose-desktop 的 `PlatformWindowContext.desktop.kt` 里
`_windowInfo.containerSize = component.sizeInPx.roundToIntSize()`。

**修法**：

- `WindowsPlatformContext` 自己实现 `WindowInfo`（三个属性用 `mutableStateOf` 包起来 ——
  弹层在 measure 阶段读它，尺寸变化必须触发重新测量），并暴露
  `updateContainerSize(size, density)`；
- `WindowsComposeApplication.setContent()` / `renderFrame()` 在 `scene.size` 与
  measure **之前**调用它。

**为什么以前的断言发现不了**：弹层照旧能渲染、能点击、能交互，**只是位置错**。
「画廊能画出东西、颜色数够多」这类断言完全看不见这个 bug —— 必须断言
**位置和尺寸**。补这几条断言的收益直接就体现在这里。

### 14.10 CI：`windows-native-selftest`（构建 HEAD 的 exe + 跑原生自检）

新增 `.github/workflows/windows-native-selftest.yml`，跑在 `ubuntu-latest`：

```
checkout → JDK 21 → Nix（pin nixpkgs）→ 取预编译 mingw-Skia（缓存）
        → 链接 windows-demo.exe（约 7 分钟）
        → apt 装 wine/xvfb/imageMagick → xvfb-run wineboot 建 prefix
        → ./scripts/test-windows-native.sh --skip-build    # logic + window + screenshot，退出码断言
        → 上传 exe / 日志 / 截图
```

三个关键设计：

1. **预编译 mingw-Skia 包**（Release 资产，约 40MB）。从源码构建 Skia 需要
   「nixpkgs mingw 交叉工具链 + 15GB 磁盘 + 半小时以上」，CI 上不现实。包里是
   22 个静态库 + 运行期/导入库 + shim，**全部是静态归档**，链接只需要 konan 自带的 lld。
   生成见 `vendor/skiko/skia-mingw/package-prebuilt.sh`，下载校验见
   `scripts/fetch-skia-mingw.sh`（sha256 不匹配直接拒绝解包），细节与踩过的坑见
   `vendor/skiko/skia-mingw/README-prebuilt.md`。
2. **pin nixpkgs**（`env.NIXPKGS_REV`）。预编译包是用 nixpkgs 交叉工具链
   （GCC 15 / UCRT / mcfgthread）构建的，宿主工具链换一个 ABI 家族就会链接失败
   （或者更糟：链接过了但运行时崩），所以 `-I nixpkgs=<pin 的 commit>`。
3. **CI 里不构建 Skia，但保留兜底**：`workflow_dispatch` 勾选 `build_skia` 时，
   会走 `build-skia-mingw.sh` 从源码重建并重新打包（约 40 分钟）。

配套新增的开关：

- `scripts/fetch-skia-mingw.sh [目标目录]` —— 下载/校验/解包预编译包
- `SKIA_MINGW_PREBUILT=<dir>` —— `build-windows-native-demo.sh` 的预编译模式：
  不需要 nix、不需要 mingw 交叉编译器（`x86_64-w64-mingw32-*` 只在复核 `_onexit` 时用一下）
- `scripts/test-windows-native.sh --exe=<path>` —— 直接用指定 exe（CI 从构建产物里取）

> 首次上线时这个 job 带 `continue-on-error: true`（不阻断流水线）；跑绿一次之后
> 按 §14.6 第 5 项删掉即可。

#### 附：去掉 `continue-on-error` 之后，又暴露了两个「CI 上从来没真正通过」的问题

`continue-on-error: true` 会把失败伪装成「run success」——`tests.yml` 那个
linux 单测 job 其实**一直**是失败的（本地却永远绿）。删掉这个开关之后连着修了两处：

| 问题 | 症状 | 修法 |
|---|---|---|
| `.def` 里写了开发机绝对路径 | `:skiko:cinteropSkikoLinuxX64` 报 `fatal error: '/home/miaox99/ComposeKN/vendor/.../wayland_bridge.h' file not found`（路径是 4aa4510 那次会话用绝对路径写进仓库的） | 仓库里的 `.def` 改成**相对 module 根**，构建期在 `NativeTasksConfiguration.resolveWaylandDefFile()` 里解析成绝对路径写到 build/ 再交给 cinterop（cinterop 解析 `headers =` 用的是**进程工作目录**，不是 .def 所在目录，所以只能在构建期解析） |
| 宿主 .so 引用更新的 glibc 符号 | `:compose-kn-tests:linkDebugTestLinuxX64` 报 `ld.lld: error: undefined reference: __isoc23_strtoul@GLIBC_2.38 ... referenced by /usr/lib/x86_64-linux-gnu/libxkbcommon.so (disallowed by --no-allow-shlib-undefined)`，还有 `stat64@GLIBC_2.33` | `LinuxNativeLinkerPlugin` 给 linuxX64 的 binaries 统一加 `-Wl,--allow-shlib-undefined`（这些符号运行时由宿主 glibc 解析，二进制只在同一台机器上跑） |

> 教训：**`continue-on-error` 只应该用在「第一次上线的探索期」，而且必须在跑绿后立刻删掉**
> —— 否则「绿的流水线」会一直骗人。顺带这也说明：**CI 的路径过滤要跟着改**，
> 比如 `build-logic/**`、`vendor/**` 这种会直接决定链接成败的目录必须包含在
> `paths:` 里，否则改了它们根本不触发 job。

**首次跑绿：2026-09-13，run [34750507633](https://github.com/TetraploidHuman/ComposeKN/actions/runs/34750507633)**

```
==> 阶段 logic (COMPOSEKN_SELFTEST=logic)      ✓ logic: RESULT PASS (73 checks)
==> 阶段 window (COMPOSEKN_SELFTEST=window)    ✓ window: RESULT PASS (18 checks)
==> 阶段 screenshot                            ✓ 标题栏 srgb(45,45,48)、颜色数 1152
全部通过 ✅
```

runner 上的开销：链接 exe 约 **16 分钟**（4 核，比本地 32 核慢一倍多）、
三段自检 + 截图约 **15 秒**、整条 job 约 **19 分钟**。

#### 把这条链路跑通踩的坑（每一轮 CI 一个，都写进 workflow 注释了）

| 坑 | 症状 | 修法 |
|---|---|---|
| 私有仓库的 Release 资产 | `curl` 直链 404（**带上 token 也是 404**） | 改走 `gh release download`，没 gh 就用 Releases API + `Accept: application/octet-stream` |
| `env: SKIA_DIR=<预编译包>` | skiko 的 `skia.dir` **恰好读环境变量 `SKIA_DIR`**（properties.kt:214），于是把只有 `.a` 的预编译目录当成 Skia 源码树 → `SkCanvas.h: No such file or directory` | 改名为 `COMPOSEKN_SKIA_MINGW_DIR` |
| ubuntu 24.04 的 `wine64` 包 | `wine: command not found`（它只装 `/usr/lib/wine/wine64`，不在 PATH；`/usr/bin/wine` 在 `wine` 包里） | 装 `wine`；脚本里也加了 `/usr/lib/wine/{wine64,wine}` 回退 |
| `--no-install-recommends` + `wine` | Wine 里**字体数为 0** → Compose `IllegalStateException: Could not load font`，logic 阶段只能过 26 条断言 | 装 **`fonts-wine`** —— Debian/Ubuntu 把 Wine 自带的 56 个字体（tahoma/marlett/wingding…）拆成了独立包，只是 Recommends。本地 nixpkgs 的 wine 把它们打包在 wine64 里，所以「本地绿、CI 红」 |
| 截图脚本结尾的裸 `wait` | job 挂死 **23 分钟**（wine 里的进程不理会 SIGTERM） | 改成「5 秒宽限期 + `kill -9`」，不再 `wait` |
| 自检没有任何超时护栏 | 挂住 = 耗到 job 超时，日志里连「跑到哪一条断言」都没有（K/N 的 stdout 接管道时是块缓冲） | 每阶段 `timeout`、exe 内置看门狗（420s）、每行 `fflush`、阶段边界 + 窗口每 20 帧一行进度 |

> 顺带一个诊断技巧：CI 上「挂住」和「失败」要能一眼分开。现在
> `scripts/test-windows-native.sh` 在阶段超时时会直接判失败并打印最后几条
> `SELFTEST` 行；exe 里的看门狗则用退出码 2 表示「我挂住了」，与断言失败的 1 区分。
