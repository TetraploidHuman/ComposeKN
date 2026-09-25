# ComposeKN Linux

Kotlin/Native **linuxX64** 宿主：Wayland + Graphite/Vulkan（回退 EGL/GLES）+ Skia 上的 Compose Multiplatform。

当前文档基线：**v0.5.47**（详见仓库根 `HANDOVER.md`）。

## Status

可用（wayland-demo + `--selftest`）。与 Windows 共用 Desktop
`androidx.compose.ui.window` API（`ComposeNativeWindowBackend`）。

## Features

- [x] Wayland 窗口（xdg-shell）；多窗 **共享一条 `wl_display`**（refcount + surface 路由）
- [x] **Graphite + Vulkan**（默认；进程级共享 VkDevice/Graphite；失败回退 GLES）
- [x] Skia OpenGL ES 回退（单线程共用一份 EGLContext + `GrDirectContext`）
- [x] 指针 / 键盘（xkbcommon）/ 触摸；IME（zwp_text_input_v3）
- [x] SSD/CSD；Fullscreen；`setResizable`（min=max 锁尺寸）
- [x] 剪贴板（wl_data_device）
- [x] Tray：StatusNotifierItem + DBusMenu；通知 `notify-send`（无 SNI 时亦算 available）
- [x] FileDialog：xdg-desktop-portal FileChooser
- [x] Dialog 软模态；eventfd 唤醒共享泵
- [ ] Absolute / Aligned 定位；always-on-top（无标准协议）
- [x] 自定义装饰拖移：`WindowDraggableArea` → `beginMove`（xdg_toplevel_move）

## Requirements

- Wayland compositor（`WAYLAND_DISPLAY`）
- 依赖见 `shell.nix`（wayland、egl、xkbcommon、dbus、libnotify、mesa、vulkan-loader 等）

## Building

```bash
nix-shell ./shell.nix --run './scripts/link-wayland-demo.sh'
```

产物：

```
samples/wayland-demo/build/bin/linuxX64/releaseExecutable/wayland-demo.kexe
```

Stable 链接若 UP-TO-DATE 未吃到 native 改动：先删上述 `.kexe` 再链，或对
`:skiko:compileNativeBridgesLinuxX64` 加 `--rerun-tasks`。

## Running / 自检

```bash
# 交互（默认 Graphite/Vulkan）
./scripts/run-linux-native.sh

# 强制后端
COMPOSEKN_RENDER_API=vulkan ./scripts/run-linux-native.sh
COMPOSEKN_RENDER_API=gl     ./scripts/run-linux-native.sh

# 无交互自检（tray + portal + placement + 双窗）
COMPOSEKN_SELFTEST=1 ./scripts/run-linux-native.sh
# 或
.../wayland-demo.kexe --selftest
```

期望日志含 `vk: Graphite/Vulkan ready`（或 GLES 时 `EGL ready` / `GrDirectContext`），
末行：`SELFTEST: PASS tray=true filedialog=true dual=true`，退出码 0。

### Vulkan ICD

`shell.nix` 默认把 `VK_ICD_FILENAMES` 指到 mesa **lavapipe**（与
`LIBGL_ALWAYS_SOFTWARE=1` 配套）。**不要**写成带未展开 `*` 的路径。

真机 GPU：

```bash
COMPOSEKN_VK_HARDWARE=1 nix-shell ./shell.nix --run './scripts/run-linux-native.sh'
```

## Architecture

| 类型 | 职责 |
|---|---|
| `LinuxComposeNativeBackend` | 登记 `ComposeNativeWindowBackend` |
| `LinuxApplicationHost` | 多窗共享泵；`composekn_display_begin_poll_cycle` |
| `LinuxComposeWindow` / `Application` | 窗 + Compose scene；detach 与 render 重入安全 |
| skiko `wayland_window.cc` | 共享 display / seat；FRAME（`egl_ready \|\| prefer_vulkan`） |
| skiko `wayland_vulkan.cc` | Graphite/Vulkan instance/device/swapchain/present |
| `LinuxWaylandVulkanRedrawer` | 默认 redrawer；失败回退 GLES |
| `LinuxSharedGpuContext` | GLES 回退时进程级共用 `GrDirectContext` |

## Known gaps

- Wayland 无法通用绝对/对齐定位；always-on-top 仅记账
- 无 SNI watcher 的会话：Tray 菜单不可用，通知仍可走 `notify-send`
- weston headless 对 maximized geometry 较严（CI selftest 在 RELAX 下跳过 maximize）

## License

与 ComposeKN 主项目相同。
