#!/usr/bin/env bash
# 打包 Windows 免安装版（zip：exe + icudtl.dat + README.txt）。
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
ICUDTL="$SKIA_WORK/skia/out/mingw/icudtl.dat"
OUT_DIR="$REPO/build/release"
STAGE="$OUT_DIR/ComposeKN-Windows-Native"
ZIP="$OUT_DIR/ComposeKN-Windows-Native-v$VERSION.zip"

if [ "$DO_BUILD" = "--build" ]; then
    echo "==> 链接 windows-demo.exe（约 7 分钟）"
    SKIA_MINGW_WORK="$SKIA_WORK" \
        nix-shell "$REPO/shell.nix" --run "$REPO/vendor/skiko/skia-mingw/build-windows-native-demo.sh"
fi

[ -f "$EXE" ] || { echo "找不到 $EXE（先跑 build-windows-native-demo.sh）" >&2; exit 1; }
[ -f "$ICUDTL" ] || { echo "找不到 icudtl.dat（$ICUDTL）" >&2; exit 1; }

rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -f "$EXE" "$STAGE/ComposeKN-Windows-Native.exe"
cp -f "$ICUDTL" "$STAGE/icudtl.dat"

cat > "$STAGE/README.txt" <<EOF
ComposeKN Windows 原生组件画廊 v$VERSION
=========================================

免安装：解压后双击 ComposeKN-Windows-Native.exe 即可。
（icudtl.dat 必须和 exe 放在同一目录，Skia 的文本排版依赖它。）

内容
----
Kotlin/Native (mingwX64) + Compose Multiplatform + 自编译 GNU-ABI Skia，
用 Win32 + GDI 软件光栅直接出图，不依赖任何第三方 DLL / 运行库。

界面里可以试：
  · 按钮 / 文本排版（长文本省略、中英混排、多种字号）
  · 输入框（单行/多行、中文输入法、Tab 焦点切换）、复选、开关、滑杆、单选
  · 进度条（确定 + 无限动画）、卡片、分割线、hover 高亮
  · 下拉菜单、对话框（弹层合成）
  · 横向/纵向 Lazy 列表虚拟化 + **鼠标滚轮滚动**
  · Canvas 绘制（渐变/路径/描边/旋转）、FlowRow 自动换行、主题切换（深/浅色）
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
      SELFTEST: RESULT PASS (58 checks, 0 failures)

  logic  = 纯逻辑断言（键位映射表、消息参数解码、输入状态机），不开窗口
  window = 真实窗口（剪贴板往返、逐帧渲染、合成点击/滚轮、干净退出）
  all    = 两者都跑

日志：composekn-startup.log（exe 同目录，含启动诊断与事件日志）。
EOF

( cd "$OUT_DIR" && rm -f "$ZIP" && zip -q -r "$ZIP" "ComposeKN-Windows-Native" )
echo
echo "==> 产物: $ZIP"
ls -l "$ZIP" "$STAGE"
