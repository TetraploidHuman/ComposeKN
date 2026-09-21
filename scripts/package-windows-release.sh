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
  · 进度条（确定 + 无限动画）、卡片、分割线、hover 高亮（触摸屏上点击也有反馈：
    触摸不会产生 hover，见 README 末尾"触摸/鼠标/hover"一节）
  · 下拉菜单、对话框（弹层合成）
  · 横向/纵向 Lazy 列表虚拟化 + **鼠标滚轮滚动**（横向列表：**Shift+滚轮** 或横向滚轮/
    触控板横滑 —— 与上游 Compose Desktop 一致；普通竖直滚轮会落到外层竖直列表上）
    + **触摸屏拖动滚动**
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
  · 富文本剪贴板：`Clipboard.getClipEntry()` / `ClipEntry.withHtml/withRtf/withImage`
    支持 HTML（CF_HTML）、RTF、位图（CF_DIBV5 + 传统 CF_DIB）与纯文本回退；
    `ClipEntry.getHtml()/getRtf()/getImage()/getPlainText()` 读回来。
    一次写入会把条目里所有格式放进同一个剪贴板事务（分开写会互相擦掉）。
    ⚠ Linux/Wayland 侧目前仍是纯文本（多 MIME 那条协议还没接）。
  · 拖放（接收侧）：从资源管理器拖文件、或从别的应用拖文本到窗口上，
    `Modifier.dragAndDropTarget` 会收到 onStarted/onEntered/onMoved/onDrop/onEnded。
    走的是真正的 OLE `IDropTarget`（和 AWT 同一条路），负载格式 CF_HDROP / CF_UNICODETEXT。
    读负载：`event.files`（路径列表）/ `event.text`（文本）/ `event.positionInWindow`。
    诊断日志：`drag: ENTER|OVER|DROP|LEAVE pos=x,y files=N textLen=M`。
  · 触摸板 / 精确滚轮：Win32 的 WM_MOUSEWHEEL `zDelta` **不保证**是 120 的倍数
    （触控板常送 40/80 这种值），宿主按上游 Compose Desktop 的模型换算成**浮点**的
    「格」（zDelta / 120），不再被整数除法截断成 0 —— 想确认自己设备的触控板送了什么，
    看 composekn-startup.log 里的 `wheel: 竖直|横向 delta=…`（原始值，不折算）

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
      SELFTEST: RESULT PASS (190 checks, 0 failures)     ← logic / window 各跑一次
      SELFTEST: RESULT PASS (187 checks, 0 failures)     ← all 一次跑完（性能契约那条自动跳过）

  logic  = 纯逻辑 + 离屏渲染断言（键位映射表、消息参数解码、布局/密度、CSD 标题栏、
           滚轮滚动、焦点/光标/选区、**中文输入法组字/提交/候选窗锚点**、弹层与对话框
           的位置和像素）、**重新转换的选区握手**、**多点触摸（捏合缩放/不泄漏触点）**、
           **触摸真实事件时间（时间戳原样到达 Compose 指针输入层）+ 按住不动不甩 +
           捏合不滚列表（两根手指都在手势区里）+ **捏合后停住不跳变（gesture pickup
           不再把整段位移当第一次拖动增量）**、
           **悬停/按压语义（触摸没有 hover 但必须有按压反馈 + 能点；鼠标 hover 必须能进能出）**、
           **悬停不会变成"按住的手指"（笔悬停的 WM_POINTERUPDATE 必须被丢掉）**、
           **按住 + 抖动只产生一次 click**、
           **滚轮翻写约定 + 横向列表（Shift+滚轮 / 横向滚轮能滚、竖直滚轮不动它）**、
           **精确滚轮/触控板（一格以内的 delta 必须原样到达滚动逻辑，不能被整数除法截断）**，
           不开窗口（118 条）
  window = 真实窗口（剪贴板往返、Ctrl+A/C/X/V 复制粘贴、逐帧渲染、合成点击/滚轮、
           IME 文本通道、**WM_IME_REQUEST 候选窗锚点（组字中塌缩到组字起点）**、
           **IMM32 文档馈送（IMR_DOCUMENTFEED 的答复结构和两段式约定）**、
           **组字字体（IMR_COMPOSITIONFONT）**、
           **重新转换（IMR_CONFIRMRECONVERTSTRING 的接受/拒绝 + 原文本不重复）**、干净退出、
           **真实 Win32 消息路径**（用 PostMessage 把真的 WM_MOUSEMOVE / WM_LBUTTONDOWN/UP /
           WM_MOUSEWHEEL / WM_MOUSEHWHEEL / WM_KEYDOWN/UP 投到窗口自己的消息队列，走主循环
           GetMessage -> TranslateMessage -> DispatchMessage -> 真实 wndproc 分支：
           悬停进出、按压/点击、竖向与横向滚轮、精确滚轮 zDelta=-40、真实点击聚焦文本框、
           真实按键输入字符都必须成立）、
           **富文本剪贴板**：clipboard.setClip(ClipEntry.withHtml/withRtf/withImage)
           -> CF_HTML（标准偏移头，含非 ASCII 片段）/ 注册格式 Rich Text Format /
           CF_DIBV5 + 传统 CF_DIB，再读回来（含 2×2 四色逐像素校验与独立头解析）、
           **OLE 拖放（接收侧）**：真的 IDropTarget（OleInitialize + RegisterDragDrop）
           解析 CF_HDROP / CF_UNICODETEXT，按上游 ComposeSceneDragAndDropNode 的顺序
           派发给 Modifier.dragAndDropTarget（Enter/Over/Drop/Leave、负载、命中位置、
           effect 写回、shouldStartDragAndDrop 的筛选）、
           **性能契约**：静止不空转 / 跨线程刷新能唤醒 /
           动画按刷新率节流）（72 条）
  all    = 两者都跑（187 条断言）
           注意：`all` 是"一个进程里跑完两个阶段"，必须真的有一个显示（第 2 个阶段
           要开窗口）；性能契约那三条在 `all` 模式下**自动跳过**（离屏阶段先跑过之后，
           窗口阶段的"后台写状态 -> 唤醒消息泵"链路在这个进程里不再驱动帧，实测三个
           子阶段全是 0 帧；原因与取舍见 HANDOVER §17.17）——所以它不会红，但要看
           真实的帧率/CPU 数据请单独跑 `window`。

外部验证（`scripts/test-windows-native.sh`，不依赖程序自述）：
  1. 宿主日志断言 —— 从 composekn-startup.log 里确认 `mouse:` / `wheel:` / `key:` / `drag:` 行
     真的出现过（这些行**只有 C++ wndproc 分支会打印**），并确认鼠标消息没有串进
     触摸通道（`touch:` 行为 0）。光看 Kotlin 侧 PASS 无法排除“断言改成不经过宿主也能过”。
  2. xdotool 真实注入 —— 往 X 服务器打真的鼠标/键盘事件，走
     X11 -> wine -> Win32 消息队列 -> wndproc 整条链路；断言两次相隔 (dx,dy) 的点击在
     客户区坐标里也相隔 (dx,dy)（不依赖窗口装饰偏移），滚轮/键盘消息也都必须到达。
  3. 截图 —— 抓真实窗口 PNG，检查标题栏像素与颜色数（不是空白窗口）。

性能日志（排查 CPU/帧率时直接拷这个文件）：
  composekn-startup.log —— exe 同目录，含启动诊断 + 每秒一行 GALLERY-STATS
  （帧率 / 各作用域重组次数 / 本进程 CPU 占用）+ 每 120 帧一行 profile
  （update / replay / present 耗时拆解、呈现路径 direct 还是 copy）。
  想复现「静止」对照：再加 --no-animate 跑一遍（预期 frames/s=+0、cpu≈0ms/s）。

日志：composekn-startup.log（exe 同目录，含启动诊断与事件日志）。

触摸 / 鼠标 / hover（一条已知的"看起来像 bug"的行为）
----------------------------------------------------
Compose 的 hover（`Modifier.hoverable` / `collectIsHoveredAsState`）**只对鼠标产生**：
skiko 的指针输入层只在指针类型是鼠标时才合成 Enter/Exit 事件
（`InternalPointerEvent.skiko.kt`: `activeHoverEvent = 指针类型 == PointerType.Mouse`）。
**触摸永远不会有 hover** —— Android 上触摸 `hoverable` 也是同样毫无反应，
这是上游模型，不是宿主漏发事件。

所以控件（包括本画廊里那个绿盒子）**不能只把可见变化挂在 hoverable 上**，否则触摸屏
用户看到的就是"点了没反应"。画廊里的绿盒子现在点击也会显示计数并保持高亮；
鼠标悬停的行为不变。宿主侧能保证的是：触摸必须产生**按压反馈**
（`PressInteraction.Press/Release` → 涟漪）并能触发 `click`（自检里有断言）。

日志里可以这样读（排查"点了没反应 / 自己乱动"时）：
  touch: …             触摸/笔的接触事件（带 type=TOUCH 或 PEN、真实事件时间）
  pointer: 悬停（不是触摸，已丢弃）…   笔在悬停。**这条不计入触摸**：以前它会被当成
                       一根按住的手指送进 Compose（点击被吞、单指被当双指），
                        现在只记一行、不派发
  mouse: 左键 DOWN/UP  鼠标按键（以前完全不进日志，所以"点击来源不明"时无从判断）
  clipboard: 写入 N 个格式（文本=… HTML=… 字节 RTF=… 位图=…）
                        富文本剪贴板写入摘要（一次事务里放了几个格式）
  drag: ENTER/OVER/DROP/LEAVE pos=x,y files=N textLen=M
                        OLE 拖放（接收侧）：文件数/文本长度；文件路径只记第一条，
                        全文在事件里（应用侧读 event.files）
  wheel: 竖直/横向 delta=…  滚轮原始 delta（不折算成"格"）。触控板/精确滚轮送来的是
                        任意小数倍 WHEEL_DELTA，"滚不动/一顿一顿"时先看这个值
  key: DOWN vk=0x..    键盘事件（prevDown=1 表示系统自动重复）—— Compose 的 clickable 在 Enter/Space
                        的 KeyUp 上也会触发 onClick，所以这条是必要的对照
  hoverbox: 点击/按下/抬起/取消  画廊里那个绿盒子的反馈与 PressInteraction（带坐标：
                        指针路径是按下点，键盘路径是控件正中心）
EOF

# 注入版本号（heredoc 带引号后无法做变量替换）
sed -i "s/__VERSION__/$VERSION/g" "$STAGE/README.txt"

( cd "$OUT_DIR" && rm -f "$ZIP" && zip -q -r "$ZIP" "ComposeKN-Windows-Native" )
echo
echo "==> 产物: $ZIP"
ls -l "$ZIP" "$STAGE"
