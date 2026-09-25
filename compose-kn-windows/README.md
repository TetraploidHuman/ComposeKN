# ComposeKN Windows

Kotlin/Native **windowsX64** 宿主：Win32 + Skia（Graphite/Vulkan 优先，可回退 GL）上的
Compose Multiplatform。

当前文档基线：**v0.5.45**（详见仓库根 `HANDOVER.md`）。

## Status

可用（画廊 + 自检）。持续对齐 Desktop `androidx.compose.ui.window` API。

## Features

- [x] Win32 窗口创建 / 消息泵 / 多窗共享 Host
- [x] Skia 呈现（Graphite/Vulkan；Intel 无 `INPUT_ATTACHMENT` 时 offscreen+blit）
- [x] **多窗共享 VkDevice/Graphite Context**（refcount；每窗 surface/swapchain/Recorder）
- [x] 键盘 / 鼠标 / 滚轮 / 多点触摸（WM_POINTER）
- [x] IME（IMM32：组字、候选、DOCUMENTFEED、重转换等）
- [x] 系统标题栏 / 可选 CSD；MenuBar（HMENU）
- [x] 剪贴板；OLE 拖入 / 拖出
- [x] Per-Monitor DPI；任务栏进度（ITaskbarList3）
- [x] Tray + Notification（Shell_NotifyIcon；Painter→16×16 HICON）
- [x] FileDialog（comdlg32）；WindowPlacement / Aligned / always-on-top
- [ ] 自定义装饰图拖出 / CSD MOVE 语义（未做）

## Requirements

- Windows 10+ x64
- 交叉编：见 `vendor/skiko/skia-mingw/` 与 `shell.nix`
- 真机 Vulkan：系统 `vulkan-1.dll`（Intel/AMD/NVIDIA 均可；路径因机器而异）

## Building

```bash
# Linux 交叉链（推荐）
nix-shell ./shell.nix --run ./vendor/skiko/skia-mingw/build-windows-native-demo.sh
# 或
./scripts/test-windows-native.sh
```

产物：

```
samples/windows-demo/build/bin/windowsX64/releaseExecutable/windows-demo.exe
```

日志默认写在 **exe 同目录** `composekn-startup.log`。

## Running / 自检

```bash
COMPOSEKN_SELFTEST=window   # 或 all / logic
# 交互画廊：直接运行 exe；托盘 / 第二扇窗 / FileDialog / MenuBar 均在画廊内
```

## Architecture（与 Linux 对称）

| 类型 | 职责 |
|---|---|
| `WindowsComposeNativeBackend` | 登记 `ComposeNativeWindowBackend` |
| `WindowsApplicationHost` | 多窗共享消息泵 + wake |
| `WindowsComposeWindow` / `Application` | 窗 + Compose scene |
| skiko `win32_*.cc` | HWND / GL·Vulkan / Tray / OLE / IME |

## Known gaps

- 自定义装饰拖移 / 装饰图拖出未做
- Wine 下退出期偶发 AV（真机关窗路径已收口；见 HANDOVER Vulkan 节）

## License

与 ComposeKN 主项目相同。
