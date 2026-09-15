#!/usr/bin/env bash
# 打包 Windows 免安装版（zip：exe + README.txt）。
#
# ICU 数据（icudtl.dat，10MB）已经在链接期用 .incbin 编进 exe 里了
# （见 vendor/skiko/skia-mingw/build-windows-native-demo.sh 与
#   vendor/skiko/skiko/src/windowsMain/cpp/win32/win32_icu.cc），
# 所以发布物**只有一个 exe**：可以直接在 zip 里双击运行，也不用担心把 exe
# 单独拷走之后文本排版崩掉。
#
# 用法（仓库根目录）：
#   ./scripts/package-windows-release.sh 0.2.8                # 用现有 exe 打包
#   ./scripts/package-windows-release.sh 0.2.8 --build        # 先链接再打包
#
# 产物：build/release/ComposeKN-Windows-Native-v<version>.zip
set -euo pipefail

VERSION="${1:?用法: $0 <version> [--build]}"
DO_BUILD="${2:-}"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXE_DIR="$REPO/samples/windows-demo/build/bin/mingwX64/releaseExecutable"
EXE="$EXE_DIR/windows-demo.exe"
SKIA_WORK="${SKIA_MINGW_WORK:-/mnt/hdd2/KtLLM/skia-mingw}"
OUT_DIR="$REPO/build/release"
STAGE="$OUT_DIR/ComposeKN-Windows-Native"
ZIP="$OUT_DIR/ComposeKN-Windows-Native-v$VERSION.zip"

if [ "$DO_BUILD" = "--build" ]; then
    echo "==> 链接 windows-demo.exe（约 7 分钟）"
    SKIA_MINGW_WORK="$SKIA_WORK" \
        nix-shell "$REPO/shell.nix" --run "$REPO/vendor/skiko/skia-mingw/build-windows-native-demo.sh"
fi

[ -f "$EXE" ] || { echo "找不到 $EXE（先跑 build-windows-native-demo.sh）" >&2; exit 1; }

# 粗检：ICU 数据内嵌之后 exe 一定比数据文件本身（10MB）大得多；
# 明显偏小说明这次链接没有把数据编进去（那样发布出来的 exe 会文本排版崩）。
EXE_BYTES="$(stat -c %s "$EXE")"
if [ "$EXE_BYTES" -lt 20000000 ]; then
    echo "!! exe 只有 $EXE_BYTES 字节，疑似没有内嵌 ICU 数据 ——" >&2
    echo "   请确认走的是改造后的 build-windows-native-demo.sh（它会生成 win32_icu_data.generated.cpp）" >&2
    exit 1
fi

rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -f "$EXE" "$STAGE/ComposeKN-Windows-Native.exe"

# 注意：heredoc 必须**带引号**（<<'EOF'）。
# 不引号时 shell 会吃掉反引号（``cmd`` 会被当命令替换执行、文本直接消失），
# v0.2.9/v0.3.0 的 README 就是这样丢掉了一整行的命令提示。
# 版本号只能靠显式占位替换注入，所以下面用 sed 处理 __VERSION__。
cat > "$STAGE/README.txt" <<'EOF'
ComposeKN Windows 原生组件画廊 v__VERSION__
=========================================

免安装：**单个 exe**，双击即可运行（也可以在资源管理器里选中压缩包内的
ComposeKN-Windows-Native.exe 直接运行 —— 不会缺文件了）。
ICU 数据文件（icudtl.dat，10MB，Skia 文本排版用）在链接期被直接编进了 exe，
所以 exe 体积约 42MB、不需要任何同目录的附带文件。

内容
----
Kotlin/Native (mingwX64) + Compose Multiplatform + 自编译 GNU-ABI Skia，
默认走 GPU（OpenGL/WGL + Ganesh）、失败自动回退软件光栅（GDI），
不依赖任何第三方 DLL / 运行库。默认系统标题栏（对齐 Compose 桌面的 Window()），
支持**触摸屏**（单指拖动滚动 + 甩动惯性）与**中文输入法**（IMM32 组字/候选窗）。

界面里可以试：
  · 按钮 / 文本排版（长文本省略、中英混排、多种字号）
  · 输入框（单行/多行、中文输入法、Tab 焦点切换）、复选、开关、滑杆、单选
  · 进度条（确定 + 无限动画）、卡片、分割线、hover 高亮
  · 下拉菜单、对话框（弹层合成）
  · 横向/纵向 Lazy 列表虚拟化 + **鼠标滚轮滚动** + **触摸屏拖动滚动**
    （触摸走 WM_POINTER -> PointerType.Touch；`set COMPOSEKN_TOUCH=0` 可关掉，
      退回系统「触摸提升成鼠标」的老行为）
  · Canvas 绘制（渐变/路径/描边/旋转）、FlowRow 自动换行、主题切换（深/浅色）
  · 渲染：**按需渲染 + 按刷新率节流**（没有内容变化就不重绘，空闲时 CPU ≈ 0；
    这个窗口的标题栏实时显示实测帧率）
  · 渲染：**默认走 GPU（OpenGL/WGL + Skia Ganesh）**，创建失败会自动回退到软件路径
    （CPU raster + GDI，零拷贝）——两者共用同一份 Skia，行为一致
  · 渲染：**按需渲染 + 按刷新率节流**（没有内容变化就不重绘，空闲时 CPU ≈ 0；
    这个窗口的标题栏实时显示实测帧率）
  · 想看空闲行为：ComposeKN-Windows-Native.exe --no-animate（关掉每帧动画）
  · 想强制后端：set COMPOSEKN_RENDER_API=software（或 gl）——日志里会写明实际用了哪条
  · 顶部诊断条：窗口尺寸、dpi、交互计数、**每帧重组计数**

自检（自动化测试）
------------------
exe 内置三层自检，用退出码 0/1 汇报，可以直接在 CI / 脚本里断言：

  普通 cmd/PowerShell:
      set COMPOSEKN_SELFTEST=1        && ComposeKN-Windows-Native.exe
      set COMPOSEKN_SELFTEST=all      && ComposeKN-Windows-Native.exe

  PowerShell:
      \$env:COMPOSEKN_SELFTEST = "all"; .\ComposeKN-Windows-Native.exe

输出形如：
      SELFTEST ok   : logic/vk-rwin
      ...
      SELFTEST: RESULT PASS (110 checks, 0 failures)

  logic  = 纯逻辑 + 离屏渲染断言（键位映射表、消息参数解码、布局/密度、CSD 标题栏、
           滚轮滚动、焦点/光标/选区、**中文输入法组字/提交**、弹层与对话框的位置和
           像素），不开窗口（85 条）
  window = 真实窗口（剪贴板往返、Ctrl+A/C/X/V 复制粘贴、逐帧渲染、合成点击/滚轮、
           IME 文本通道、干净退出、**性能契约**：静止不空转 / 跨线程刷新能唤醒 /
           动画按刷新率节流）（25 条）
  all    = 两者都跑（110 条断言）

性能日志（排查 CPU/帧率时直接拷这个文件）：
  composekn-startup.log —— exe 同目录，含启动诊断 + 每秒一行 GALLERY-STATS
  （帧率 / 各作用域重组次数 / 本进程 CPU 占用）+ 每 120 帧一行 profile
  （update / replay / present 耗时拆解、呈现路径 direct 还是 copy）。
  想复现「静止」对照：再加 --no-animate 跑一遍（预期 frames/s=+0、cpu≈0ms/s）。

日志：composekn-startup.log（exe 同目录，含启动诊断与事件日志）。
EOF

# 注入版本号（heredoc 带引号后无法做变量替换）
sed -i "s/__VERSION__/$VERSION/g" "$STAGE/README.txt"

( cd "$OUT_DIR" && rm -f "$ZIP" && zip -q -r "$ZIP" "ComposeKN-Windows-Native" )
echo
echo "==> 产物: $ZIP"
ls -l "$ZIP" "$STAGE"
