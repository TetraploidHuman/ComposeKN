# vendor/compose-core.local — 本地化改动外置

`vendor/compose-core/` 是 [JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core)
的 **14 个模块源码 vendoring**（见 `vendor/compose-core/settings.gradle.kts`）。
为了能用一条命令同步到上游新版本而不丢失本地改动，所有"本地对上游的偏离"都外置到这里：

```
vendor/compose-core.local/
├── VERSIONS        # 上游基线 tag/sha + 依赖版本矩阵
├── templates/      # 本地 build 文件（覆盖上游同名文件）
│   ├── build.gradle.kts / settings.gradle.kts / gradle.properties   # 根
│   └── modules/<module>/build.gradle.kts                            # 14 个模块
├── overlay/        # 本地新增文件（原样拷贝到 vendor/compose-core/<module>/...）
│   ├── <module>/src/...   # 39 个 linuxX64Main actual + 1 个 skikoMain actual
│   ├── ui-backhandler/    # 整模块冻结（jar 布局 + navigationevent 1.1.1 兼容 patch）
│   └── lifecycle-viewmodel-compose/  # 整模块冻结（2 个源文件，非上游仓库内容）
└── patches/        # 7 个对上游源码的最小 patch（patch -p1 应用）
```

## 上游基线

- **tag**: `v1.12.0-beta01+dev4339`（sha `6d473fc44b`）
- ⚠️ 不是 `v1.11.1`：`1.11.1` 只是从 Maven 拉的 runtime/collection-internal 等**依赖**的版本号，
  源码基线比它新（在 1.12.0 开发线上）。
- `lifecycle-viewmodel-compose` 与 `ui-backhandler` 不来自该 tag 的仓库布局（见 overlay 说明）。

## 本地改动清单（相对基线）

| 类别 | 数量 | 说明 |
|---|---|---|
| 新增 `linuxX64Main` actual | 39 | K/N linuxX64 后端实现（foundation 24、ui 8、ui-text 3、material3 2、ui-util 1、animation-core 1） |
| 新增 `skikoMain` actual | 1 | `ui-text/.../platform/Dispatcher.skiko.kt`（`FontCacheManagementDispatcher` = `SkikoDispatchers.Main`） |
| 修改上游 src | 7 | 见 `patches/`：`Dispatchers.Main`→`SkikoDispatchers.Main`、expect 可见性、`ClipMetadata.PlainText`、去 `@PublishedApi`、删 web 测试 |
| 实现 overlay 占位实现 | 1 | `ui/.../draganddrop/DragAndDrop.linux.kt`：原来是上游原样的 stub（`TODO("Not yet implemented")` + 两个空类），现在 `DragAndDropEvent` 带 `files`/`text`/`positionInWindow` 负载 + `forPlatformDrop(...)` 工厂（宿主在别的模块里，读不到 internal 构造函数）；由 skiko 的 Win32 `IDropTarget` 驱动（HANDOVER §17.33） |
| 修复 overlay 占位实现 | 1 | `foundation/.../LinuxScrollable.linux.kt`：原来 `calculateMouseWheelScroll` 返回 `Offset.Zero`，导致 K/N 原生后端（Linux **和** mingw 复用同一源集）完全不能滚轮滚动；现在按平台换算（HANDOVER §14.4） |
| ui-backhandler 替换 | 10 | 来自发布 sources jar（无 `kotlin/` 目录层级），jbMain 改为 `LocalCompatNavigationEventDispatcherOwner` 以兼容 navigationevent 1.1.1 |
| lifecycle-viewmodel-compose | 2 | 整模块冻结（androidx lifecycle MPP 的 2 个源文件） |
| build 文件 | 17 | 上游是 Groovy `build.gradle`，本地全替换为 KMP+linuxX64 的 Kotlin DSL `build.gradle.kts`（5 层 sourceSets 链：commonMain→skikoMain→nonJvmMain→nativeMain→linuxX64Main） |

## 同步（升级）流程

```bash
scripts/vendor-compose-core.sh                 # 用 VERSIONS 里的基线 tag（幂等重建）
scripts/vendor-compose-core.sh <new-tag>       # 同步到新 tag
COMPOSE_VERSION=1.12.0 scripts/vendor-compose-core.sh v1.12.0-beta01   # 同时 bump 依赖版本
scripts/vendor-compose-core.sh --check         # 重建后跑 :compose-kn-linux:compileKotlinLinuxX64
```

脚本步骤：fetch tag（sparse blobless clone，缓存在 `.cache/compose-core-git/`）→
清空 `vendor/compose-core/` → 拷 12 个上游模块的 `src/`+`api/`（ui-backhandler 冻结，不取上游）→ 覆盖 templates →
拷 overlay → `patch -p1` 应用 7 个 patch → （可选）编译验证。

## 升级到新版本时的检查清单

1. **新增 expect**：新上游代码若引入新的 `expect`，需在 `overlay/` 对应模块加 `linuxX64Main` actual（编译会直接报 `expected declaration is not initialized`）。
2. **sourceSets 结构变化**：若上游改了 `skikoMain`/`nonJvmMain` 等源集划分（如 1.12.10-alpha 线引入 `nonAndroidMain` 重构），templates 里的 5 层链可能要跟着调整。
3. **internal API 耦合**：`compose-kn-linux` 依赖 `CanvasLayersComposeScene`、`DefaultArchitectureComponentsOwner`、`FrameRecomposer` 等 `@InternalComposeUiApi`；上游重构这些类时需同步改 `compose-kn-linux/`。
4. **skiko 接口耦合**：`SkikoDispatchers.Main`、`SkikoRenderDelegate` 等来自 vendored skiko，compose 升级可能要求 skiko 同步升级（`vendor/skiko/dependencies.toml` 里 skia/skiko 版本）。
5. **版本矩阵**：`COMPOSE_VERSION`/`KOTLIN_VERSION` 会替换 templates 与 `vendor/skiko/dependencies.toml` 中的版本号；runtime 等 Maven 依赖必须真实存在该版本。
6. **ui-backhandler / lifecycle 冻结模块**：升级时保持冻结；如上游修复了相关问题，可考虑改回仓库布局（需同时改 templates 里对应 `srcDir`）。
