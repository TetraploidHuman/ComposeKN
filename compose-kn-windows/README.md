# ComposeKN Windows

Kotlin/Native Windows (Win32) platform support for Compose Multiplatform.

## Status

🚧 **Work in Progress** - This module is under active development.

## Overview

This module provides Windows x64 support for ComposeKN, enabling Compose UI applications to run natively on Windows using Kotlin/Native and Win32 API.

## Features

- [ ] Win32 window creation and management
- [ ] Skia rendering integration
- [ ] Keyboard input handling
- [ ] Mouse input handling
- [ ] Text input (IME support)
- [ ] Window chrome (title bar, controls)
- [ ] Clipboard support
- [ ] High DPI support
- [ ] Drag and drop

## Requirements

- Windows 10 or later (x64)
- Kotlin/Native compiler
- Visual Studio Build Tools with Windows SDK
- MinGW or similar cross-compilation toolchain (for building on Linux)

## Building

### On Windows

```bash
# Build the windows-demo sample
./gradlew :samples:windows-demo:linkReleaseExecutableWindowsX64

# Or use the convenience task
./gradlew linkWindowsX64Stable
```

### Cross-compilation from Linux

```bash
# Note: Cross-compilation requires additional setup
# See Kotlin/Native documentation for Windows target
```

## Running

After building, the executable will be located at:
```
samples/windows-demo/build/bin/windowsX64/releaseExecutable/windows-demo.exe
```

## Architecture

The module follows the same architecture as `compose-kn-linux`:

- `WindowsComposeWindow` - Win32 window management
- `WindowsComposeApplication` - Compose UI host
- `WindowsPlatformContext` - Platform services
- `WindowsInputMapper` - Input event handling
- `WindowsKeyMapper` - Keyboard mapping
- `WindowsTextInputService` - Text input
- `WindowsWindowChrome` - Window decorations

## Implementation Details

### Win32 API Integration

The module uses Kotlin/Native's cinterop to interface with Win32 APIs:

- `CreateWindowExW` - Window creation
- `DefWindowProcW` - Default message handling
- `GetMessageW` / `PeekMessageW` - Message loop
- `TranslateMessage` / `DispatchMessageW` - Message dispatch

### Rendering

Rendering is handled through SkiaLayer, which provides:

- Hardware-accelerated rendering via Direct3D or OpenGL
- Automatic DPI scaling
- Double buffering

### Input Handling

Input events are mapped from Win32 messages to Compose events:

- `WM_KEYDOWN` / `WM_KEYUP` → Key events
- `WM_MOUSEMOVE` → Pointer move events
- `WM_LBUTTONDOWN` / `WM_LBUTTONUP` → Pointer click events
- `WM_MOUSEWHEEL` → Scroll events

## Known Issues

- Window chrome is simplified (no native title bar)
- IME support is basic
- No touch/pen input yet
- Limited DPI awareness

## Contributing

Contributions are welcome! Please see the main project README for guidelines.

## License

Same as the main ComposeKN project.
