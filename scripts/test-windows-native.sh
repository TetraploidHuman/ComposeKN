#!/usr/bin/env bash
# ComposeKN Windows 原生 exe 的自动化测试驱动。
#
# 四层验证（对应 exe 内置的 `--selftest` + 两个外部阶段）：
#   1. logic  —— 纯逻辑断言（键位映射/消息解码/输入状态），不需要窗口
#   2. render —— 离屏光栅化真实 Compose 场景 + 像素断言（布局/density/CSD 标题栏/
#                点击与键盘输入、滚轮滚动），同样不需要窗口
#   3. window —— 真实 Win32 窗口：剪贴板桥接、逐帧渲染循环、合成点击、干净退出，
#                外加「真实 Win32 消息」子阶段（PostMessage -> 真实 wndproc 分支）
#   3.5 input —— 外部注入：xdotool 打真实 X11 鼠标/键盘 -> wine -> wndproc
#                （证明「系统真的会把这些消息送到窗口」，window 阶段的 PostMessage
#                 证明不了这一点）
#   4. 截图   —— 独立于程序自述的外部验证：抓窗口 PNG，检查标题栏颜色/色彩数量
#
# 用法（在仓库根目录）：
#   nix-shell -p wine64 xvfb xauth imagemagick xwininfo xdotool \
#       --run ./scripts/test-windows-native.sh
#
#   --skip-build    不重新链接，直接用现有的 exe
#   --exe=PATH      用指定的 exe（隐含 --skip-build；CI 从构建产物里取）
#   --no-screenshot 跳过截图阶段（没有 X 时）
#   --no-input      跳过外部注入阶段（没有 xdotool 时）
#   --only=<phase>  只跑某个阶段: logic|window|input|screenshot
#
# 环境变量：
#   SKIA_MINGW_PREBUILT=<dir>  预编译 mingw-Skia 包（跳过 Skia 构建；icudtl.dat 在链接期被
#                              直接编进 exe，测试运行时**不需要**同目录数据文件）
#   SKIA_MINGW_WORK=<dir>      从源码构建时的 Skia 工作目录
#   COMPOSEKN_WINTEST_XDOTOOL_DX/DY  外部注入阶段两次点击的像素间隔（默认 40x25）
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXE="$REPO/samples/windows-demo/build/bin/mingwX64/releaseExecutable/windows-demo.exe"
EXE_OVERRIDE=""
SKIA_WORK="${SKIA_MINGW_WORK:-/mnt/hdd2/KtLLM/skia-mingw}"
# 预编译包模式（CI 用，见 vendor/skiko/skia-mingw/README-prebuilt.md）：
# 不构建 Skia，直接用下载好的静态库；icudtl.dat 也在包里。
PREBUILT="${SKIA_MINGW_PREBUILT:-}"
# 注意：这里**没有** icudtl.dat 的路径 —— ICU 数据已经在链接期编进 exe 了
# （见 scripts/../vendor/skiko/skia-mingw/build-windows-native-demo.sh），
# 测试必须有本事证明「只拷 exe 也能跑」，所以运行目录里故意不放数据文件。
RUN_DIR="${COMPOSEKN_WINTEST_DIR:-/tmp/composekn-wintest}"
export WINEPREFIX="${WINEPREFIX:-/mnt/hdd2/KtLLM/wineprefix}"
export WINEDEBUG="${WINEDEBUG:--all}"

SKIP_BUILD=0
DO_SCREENSHOT=1
ONLY=""
DO_INPUT=1

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --no-screenshot) DO_SCREENSHOT=0 ;;
        --no-input) DO_INPUT=0 ;;
        --only=*) ONLY="${arg#--only=}" ;;
        --exe=*) EXE_OVERRIDE="${arg#--exe=}"; SKIP_BUILD=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "未知参数: $arg" >&2; exit 2 ;;
    esac
done

FAILED=0
declare -a SUMMARY=()

pass() { SUMMARY+=("PASS  $1"); printf '\033[32m✓\033[0m %s\n' "$1"; }
fail() { SUMMARY+=("FAIL  $1"); FAILED=1; printf '\033[31m✗\033[0m %s\n' "$1"; }
info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

# ---------------------------------------------------------------- 0. 构建
if [ "$SKIP_BUILD" = "0" ]; then
    if [ -n "$PREBUILT" ]; then
        info "构建 windows-demo.exe（预编译 mingw-Skia：$PREBUILT）"
        nix-shell "$REPO/shell.nix" \
            --run "SKIA_MINGW_PREBUILT='$PREBUILT' '$REPO/vendor/skiko/skia-mingw/build-windows-native-demo.sh'"
    else
        info "构建 windows-demo.exe（mingw-Skia，约 7 分钟）"
        SKIA_MINGW_WORK="$SKIA_WORK" \
            nix-shell "$REPO/shell.nix" --run "$REPO/vendor/skiko/skia-mingw/build-windows-native-demo.sh"
    fi
fi
[ -n "$EXE_OVERRIDE" ] && EXE="$EXE_OVERRIDE"
[ -f "$EXE" ] || { echo "找不到 $EXE（先用 build-windows-native-demo.sh 构建，或用 --exe= 指定）" >&2; exit 1; }

# 只拷 exe：**故意不**放 icudtl.dat。
#
# ICU 数据现在已经用 .incbin 编进 exe 了（见 vendor/skiko/skia-mingw/
# build-windows-native-demo.sh 与 skiko/.../win32_icu.cc），所以这里放一份数据文件
# 反而会掩盖「嵌入失效」—— 测试必须证明 exe 单文件就能跑。
mkdir -p "$RUN_DIR"
cp -f "$EXE" "$RUN_DIR/windows-demo.exe"
rm -f "$RUN_DIR/icudtl.dat"
EXE_RUN="$RUN_DIR/windows-demo.exe"

# wine 可执行文件的位置在不同发行版/包名之间来回变：
#   * NixOS: wine64 / wine 在 PATH 里
#   * Ubuntu 22.04: wine64 在 PATH 里
#   * Ubuntu 24.04: `wine` 包给 /usr/bin/wine；只装 `wine64` 包的话可执行文件是
#                   /usr/lib/wine/wine64（**不在 PATH**）—— CI 上就是这么踩到的
WINECMD="$(command -v wine64 || command -v wine || true)"
if [ -z "$WINECMD" ]; then
    for candidate in /usr/lib/wine/wine64 /usr/lib/wine/wine /usr/local/bin/wine; do
        [ -x "$candidate" ] && { WINECMD="$candidate"; break; }
    done
fi
[ -n "$WINECMD" ] || {
    echo "找不到 wine（nix-shell -p wine64 ... / apt install wine）" >&2
    ls -la /usr/lib/wine/ 2>/dev/null | head -5 || true
    exit 1
}

# 起一个私有 Xvfb（NixOS 的 xvfb 包里**没有** xvfb-run），返回 DISPLAY 号
XVFB_PID=""
start_xvfb() {
    local disp
    for disp in 99 98 97 96; do
        if [ ! -e "/tmp/.X${disp}-lock" ]; then break; fi
        disp=""
    done
    [ -n "${disp:-}" ] || { echo "找不到空闲 X display" >&2; return 1; }
    Xvfb ":$disp" -screen 0 1600x1000x24 -nolisten tcp >"$RUN_DIR/xvfb.log" 2>&1 &
    XVFB_PID=$!
    export DISPLAY=":$disp"
    for _ in $(seq 1 40); do
        [ -e "/tmp/.X${disp}-lock" ] && break
        sleep 0.25
    done
    # shellcheck disable=SC2064
    trap "kill $XVFB_PID 2>/dev/null || true" EXIT
    sleep 1
}

run_phase() {   # $1 = 阶段名, $2 = COMPOSEKN_SELFTEST 取值, $3 = 是否需要 X
    local name="$1" mode="$2" need_x="$3" log="$RUN_DIR/$1.log"
    info "阶段 $name (COMPOSEKN_SELFTEST=$mode)"
    # 启动日志是 append 模式：先删掉，免得上一阶段的旧行让后面的断言假通过。
    rm -f "$RUN_DIR/composekn-startup.log"
    [ "$need_x" = "1" ] && start_xvfb
    set +e
    # 每个阶段都加硬超时：自检挂住时立刻失败并打出「最后跑到哪一条断言」，
    # 而不是耗到 CI job 超时、日志里什么线索都没有（这个坑真的踩过）。
    timeout "${PHASE_TIMEOUT:-600}" env COMPOSEKN_SELFTEST="$mode" "$WINECMD" "$EXE_RUN" >"$log" 2>&1
    local rc=$?
    set -e
    local checks failed
    if [ "$rc" = "124" ] || [ "$rc" = "137" ]; then
        fail "$name: 阶段超时（>${PHASE_TIMEOUT:-600}s），疑似挂死（日志: $log）"
        grep '^SELFTEST' "$log" | tail -15 || true
        tail -10 "$log"
        return 1
    fi
    checks="$(grep -c '^SELFTEST ok' "$log" || true)"
    failed="$(grep -c '^SELFTEST FAIL' "$log" || true)"

    if grep -q '^SELFTEST: RESULT PASS' "$log"; then
        pass "$name: RESULT PASS ($checks checks)"
    elif [ "$rc" = "0" ] && [ "$failed" = "0" ]; then
        # 没有 SELFTEST 输出但退出码 0 —— 属于异常（说明自检根本没跑）
        fail "$name: 退出码 0 但没有 SELFTEST 结果行（日志: $log）"
        tail -20 "$log"
    else
        fail "$name: rc=$rc, $checks ok / $failed failed（日志: $log）"
        grep '^SELFTEST' "$log" | tail -30 || true
        tail -20 "$log"
    fi
}

# ------------------------------------------------- 1+2. logic + 离屏渲染
if [ -z "$ONLY" ] || [ "$ONLY" = "logic" ]; then
    run_phase logic logic 0
fi

# ------------------------------------------------- 2.5 ICU 数据内嵌（单文件运行）
#
# 文本排版依赖 ICU 数据（10MB）。以前它是 exe 同目录的 icudtl.dat，发布物必须是
# 两个文件、单独拷走 exe 就会崩；现在数据编在 exe 里（.incbin + 覆盖版 SkLoadICU）。
# 这里直接查启动日志里的那一行 —— 它来自 win32_icu.cc，只有"真的用上内嵌数据"才会打印。
if [ -z "$ONLY" ] || [ "$ONLY" = "logic" ]; then
    STARTUP_LOG="$RUN_DIR/composekn-startup.log"
    if grep -q 'icu: 使用内嵌数据初始化成功' "$STARTUP_LOG" 2>/dev/null; then
        pass "icu: 内嵌数据初始化成功（exe 单文件可跑，无需 icudtl.dat）"
    else
        fail "icu: 启动日志里没有『使用内嵌数据初始化成功』（$STARTUP_LOG）—— 文本排版可能已经退回读文件"
        grep -i 'icu\|SkLoadICU' "$STARTUP_LOG" 2>/dev/null | head -5 || true
    fi
    if [ -e "$RUN_DIR/icudtl.dat" ]; then
        fail "icu: 运行目录里出现了 icudtl.dat（不该有：数据应当只在 exe 里）"
    fi
fi

# ------------------------------------------------- 2.6 画廊滚动/缩放状态日志
#
# 「缩放后跳 / 嵌套滚动跳」这类真机问题，光有触摸轨迹看不出**谁在动** —— 画廊必须把
# 外层/内层 scrollable 的位置写进启动日志（~20Hz 节流、只在变化时记）。这条在 logic
# 阶段就能验证：画廊离屏渲染时 LaunchedEffect 会写第一条基线。
if [ -z "$ONLY" ] || [ "$ONLY" = "logic" ]; then
    STARTUP_LOG="$RUN_DIR/composekn-startup.log"
    if grep -q 'gallery: outer=' "$STARTUP_LOG" 2>/dev/null; then
        pass "gallery: 滚动/缩放状态日志已写入（$(grep -c 'gallery: outer=' "$STARTUP_LOG") 行）"
    else
        fail "gallery: 启动日志里没有 'gallery: outer=…' 行（真机排查滚动跳变就靠它）"
    fi
fi

# ----------------------------------------------------------- 3. 真实窗口
if [ -z "$ONLY" ] || [ "$ONLY" = "window" ]; then
    run_phase window window 1
fi

# -------------------------------------- 3.2 宿主层（C++ wndproc）真的被跑过
#
# 窗口阶段新增了一组「真实 Win32 消息」断言：自检用 PostMessage 把真的
# WM_MOUSEMOVE / WM_LBUTTONDOWN/UP / WM_MOUSEWHEEL / WM_MOUSEHWHEEL /
# WM_KEYDOWN / WM_CHAR / WM_KEYUP 投到窗口自己的消息队列，走
# 主循环 GetMessage -> DispatchMessage -> 真实 wndproc 分支。
#
# 这里从**外部**再确认一次：C 侧那几条诊断日志必须真的出现。为什么不能只信
# Kotlin 侧的 PASS —— 那组断言最终看的是 Compose 里的探针，万一哪天有人把宿主
# 改回「从 Kotlin 侧合成事件」也能全绿；日志行是只有真的走过 wndproc 才会有的
# 证据（`mouse:`/`key:`/`wheel:` 三条都只在 C++ 分支里打印）。
if [ -z "$ONLY" ] || [ "$ONLY" = "window" ]; then
    STARTUP_LOG="$RUN_DIR/composekn-startup.log"
    check_log_line() {   # $1 = 名字, $2 = 必须出现的字面量
        if grep -qF -- "$2" "$STARTUP_LOG" 2>/dev/null; then
            pass "win32msg: 宿主日志出现『$1』"
        else
            fail "win32msg: 宿主日志里没有『$1』（grep -F '$2' $STARTUP_LOG）"
        fi
    }
    check_log_line "真实鼠标按下" "mouse: 左键 DOWN pos="
    check_log_line "真实鼠标抬起" "mouse: 左键 UP pos="
    check_log_line "真实竖向滚轮" "wheel: 竖直 delta=-120"
    # 触控板/自由滚轮的 zDelta 不是 120 的倍数 —— 宿主必须原样收到、原样换算成
    # 浮点「格」（40/120），不能整数除法截断成 0（HANDOVER §17.32）。
    check_log_line "真实精确滚轮（zDelta=-40）" "wheel: 竖直 delta=-40"
    check_log_line "真实横向滚轮" "wheel: 横向 delta=-120"
    check_log_line "真实按键按下" "key: DOWN vk=0x5A"
    check_log_line "真实按键抬起" "key: UP vk=0x5A"
    # OLE 拖放：C 侧的 IDropTarget 真的被调到、并且真的从 IDataObject 里解出了负载
    # （文件名只有 CF_HDROP/FORMATETC 解码对了才会出现在日志里）。
    check_log_line "拖放进入（文件）" "drag: ENTER pos=475,392 files=2"
    check_log_line "拖放放下（文件路径）" "第一个=C:\\composekn\\drop-test-1.txt"
    check_log_line "拖放文本" "drag: ENTER pos=475,462 files=0 textLen=28"
    check_log_line "拖放离开" "drag: LEAVE"
    # 反证：真实鼠标消息**不该**顺带产生触摸事件（v0.5.11 的「笔悬停变手指」
    # 就是触摸通道串了）。window 阶段全程没有真触摸，所以一条都不该有。
    TOUCH_LINES="$(grep -c '^.*touch: ' "$STARTUP_LOG" 2>/dev/null || true)"
    POINTER_HOVER="$(grep -c '^.*pointer: 悬停' "$STARTUP_LOG" 2>/dev/null || true)"
    if [ "${TOUCH_LINES:-0}" = "0" ] && [ "${POINTER_HOVER:-0}" = "0" ]; then
        pass "win32msg: 真实鼠标消息没有污染触摸通道（touch=0 悬停丢弃=0）"
    else
        fail "win32msg: 鼠标消息串进了触摸通道（touch=$TOUCH_LINES 悬停丢弃=$POINTER_HOVER）"
        grep -n 'touch: \|pointer: 悬停' "$STARTUP_LOG" | head -5 || true
    fi
fi

# ------------------------------------------------- 3.5 外部注入（xdotool）
#
# 与 3.2 的区别（两者互补，缺一不可）：
#   * 3.2 是程序 PostMessage 给自己 —— 证明「消息到了之后宿主怎么处理」，
#     但 PostMessage 绕过了系统输入栈。
#   * 这一段用 xdotool 往 X 服务器打**真实**的鼠标/键盘事件，走
#     X11 -> wine 的 X 驱动 -> Win32 消息队列 -> wndproc 整条链路，
#     证明「系统真的会把这些消息送到我们的窗口」。
#
# 断言方式是**坐标差**而不是绝对坐标：不管窗口摆在哪、有没有装饰/缩放，
# 两次相隔 (dx,dy) 的点击在客户区坐标里也必须相隔 (dx,dy)。这样就不需要知道
# wine 的窗口框架偏移（无窗口管理器时它自己画装饰，偏移不可移植）。
if { [ -z "$ONLY" ] && [ "$DO_INPUT" = "1" ]; } || [ "$ONLY" = "input" ]; then
    if ! command -v xdotool >/dev/null 2>&1; then
        if [ "$ONLY" = "input" ]; then
            fail "input: 找不到 xdotool（nix-shell -p … xdotool）"
        else
            pass "input: 跳过（没装 xdotool；显式 --only=input 时会失败）"
        fi
    else
        info "阶段 input（xdotool 真实注入：X11 -> wine -> WndProc）"
        IN_LOG="$RUN_DIR/input.log"
        rm -f "$IN_LOG" "$RUN_DIR/composekn-startup.log"
        IN_DX="${COMPOSEKN_WINTEST_XDOTOOL_DX:-40}"
        IN_DY="${COMPOSEKN_WINTEST_XDOTOOL_DY:-25}"
        cat > "$RUN_DIR/input.sh" <<'EOIN'
set -e
export WINEDEBUG="${WINEDEBUG:--all}"
"$WINECMD" "$EXE_RUN" >"$IN_LOG" 2>&1 &
APP_PID=$!
WID=""
for _ in $(seq 1 60); do
    sleep 0.5
    WID="$(xwininfo -root -tree 2>/dev/null | grep -m1 'ComposeKN Windows Demo' | awk '{print $1}')" || true
    [ -n "${WID:-}" ] && break
done
if [ -z "${WID:-}" ]; then
    echo "INPUT-NO-WINDOW"
    kill $APP_PID 2>/dev/null || true
    exit 1
fi
# 等布局/首帧就位：坐标命中依赖布局已经完成
sleep 4
eval "$(xdotool getwindowgeometry --shell "$WID" 2>/dev/null || true)"
echo "INPUT-GEOM x=${X:-?} y=${Y:-?} w=${WIDTH:-?} h=${HEIGHT:-?}"
BX=$(( ${X:-0} + ${WIDTH:-200} / 2 ))
BY=$(( ${Y:-0} + ${HEIGHT:-200} / 2 ))
echo "INPUT-CLICK1 $BX,$BY"
xdotool mousemove "$BX" "$BY" click 1
sleep 1
echo "INPUT-CLICK2 $((BX+$IN_DX)),$((BY+$IN_DY))"
xdotool mousemove "$((BX+$IN_DX))" "$((BY+$IN_DY))" click 1
sleep 1
# 滚轮：X11 的 4/5 号键 = 滚轮上/下，wine 会翻成 WM_MOUSEWHEEL（±120）
xdotool mousemove "$BX" "$BY"
xdotool click 4
sleep 0.3
xdotool click 5
sleep 1
# 键盘：无窗口管理器时 X 的输入焦点是 PointerRoot，鼠标刚移进窗口，
# 所以这里不需要 windowactivate（没有 WM 也激活不了）。
xdotool key Return
sleep 2
kill $APP_PID 2>/dev/null || true
for _ in $(seq 1 20); do
    kill -0 $APP_PID 2>/dev/null || break
    sleep 0.25
done
kill -9 $APP_PID 2>/dev/null || true
echo "INPUT-OK"
EOIN
        start_xvfb
        set +e
        timeout 300 env WINECMD="$WINECMD" EXE_RUN="$EXE_RUN" IN_LOG="$IN_LOG" \
            IN_DX="$IN_DX" IN_DY="$IN_DY" bash "$RUN_DIR/input.sh" \
            > "$RUN_DIR/input.out" 2>&1
        set -e
        STARTUP_LOG="$RUN_DIR/composekn-startup.log"
        if ! grep -q INPUT-OK "$RUN_DIR/input.out"; then
            fail "input: 注入脚本没跑完（见 $RUN_DIR/input.out）"
            tail -20 "$RUN_DIR/input.out" || true
        else
            # 两次点击的客户区坐标差必须等于我们注入的像素间隔。
            XN="$(grep -o 'mouse: 左键 DOWN pos=[-0-9]*,[-0-9]*' "$STARTUP_LOG" 2>/dev/null | head -2 || true)"
            N="$(printf '%s\n' "$XN" | grep -c 'pos=' || true)"
            if [ "${N:-0}" -lt 2 ]; then
                fail "input: 只看到 $N 条『mouse: 左键 DOWN』（期望 ≥2，见 $STARTUP_LOG）"
                tail -5 "$IN_LOG" || true
            else
                P1="$(printf '%s\n' "$XN" | sed -n 1p)"
                P2="$(printf '%s\n' "$XN" | sed -n 2p)"
                X1="$(printf '%s' "$P1" | sed 's/.*pos=//; s/,.*//')"
                Y1="$(printf '%s' "$P1" | sed 's/.*,//')"
                X2="$(printf '%s' "$P2" | sed 's/.*pos=//; s/,.*//')"
                Y2="$(printf '%s' "$P2" | sed 's/.*,//')"
                DX=$((X2 - X1))
                DY=$((Y2 - Y1))
                if [ "$DX" = "$IN_DX" ] && [ "$DY" = "$IN_DY" ]; then
                    pass "input: 真实 X11 鼠标点击到达 wndproc，客户区坐标差 = ($DX,$DY)"
                else
                    fail "input: 坐标差 ($DX,$DY) ≠ 注入的 ($IN_DX,$IN_DY)（$P1 / $P2）"
                fi
            fi
            # 滚轮 / 键盘：证明这两条消息也被系统送到了窗口
            for spec in "真实竖向滚轮|wheel: 竖直 delta=" "真实按键按下|key: DOWN vk=0x0D" "真实按键抬起|key: UP vk=0x0D"; do
                want_name="${spec%%|*}"
                want_text="${spec#*|}"
                if grep -qF -- "$want_text" "$STARTUP_LOG" 2>/dev/null; then
                    pass "input: $want_name（$(grep -F -- "$want_text" "$STARTUP_LOG" | head -1 | sed 's/^\[[^]]*\] //' | tr -d '\r\n')）"
                else
                    fail "input: 没看到『$want_name』（grep -F '$want_text' $STARTUP_LOG）"
                fi
            done
            # 反证：X11 鼠标输入**不该**在触摸通道里冒出来（鼠标必须只走 WM_MOUSE*）。
            TOUCH_LINES="$(grep -c '^.*touch: ' "$STARTUP_LOG" 2>/dev/null || true)"
            if [ "${TOUCH_LINES:-0}" = "0" ]; then
                pass "input: 真实鼠标输入没有串进触摸通道"
            else
                fail "input: 真实鼠标输入串进了触摸通道（touch=$TOUCH_LINES）"
                grep -n 'touch: ' "$STARTUP_LOG" | head -5 || true
            fi
        fi
    fi
fi

# ------------------------------------------------- 4. 截图（外部像素校验）
if { [ -z "$ONLY" ] && [ "$DO_SCREENSHOT" = "1" ]; } || [ "$ONLY" = "screenshot" ]; then
    info "阶段 screenshot（抓真实窗口 PNG 并检查像素）"
    SHOT="$RUN_DIR/gallery.png"
    LOG="$RUN_DIR/gallery.log"
    rm -f "$SHOT"
    cat > "$RUN_DIR/shot.sh" <<'EOSH'
set -e
export WINEDEBUG="${WINEDEBUG:--all}"
"$WINECMD" "$EXE_RUN" >"$LOG" 2>&1 &
APP_PID=$!
for _ in $(seq 1 40); do
    sleep 0.5
    WID="$(xwininfo -root -tree 2>/dev/null | grep -m1 'ComposeKN Windows Demo' | awk '{print $1}')" || true
    [ -n "${WID:-}" ] && break
done
if [ -z "${WID:-}" ]; then
    echo "SHOT-NO-WINDOW"
    kill $APP_PID 2>/dev/null || true
    exit 1
fi
sleep 4
# demo 每秒把**实测帧率**写进窗口标题（见 main.kt）：读出来作为「按需渲染 + 帧节流」
# 的外部验证（不依赖程序自述）。
#   * 读多次取**最大值**：`import` 抓图会让 wine 侧短暂停顿（X 服务端抓图），
#     偶尔还会赶上应用刚启动的那一秒（帧率天然偏低），单次读会把噪声当成结论。
#   * 每次都打印原始标题（含 frames=），排查时能看出「是停摆了还是刚起步」。
read_title() {
    # 注意 `"\([^"]*\)"`：用 `"\(.*\)"` 的话贪婪匹配会吃到行内最后一个引号，
    # 把 `": ("windows-demo.exe"` 这类尾巴也带进来（曾经把 59.8 解析成 9.8）。
    xwininfo -root -tree 2>/dev/null | grep -m1 'ComposeKN Windows Demo' \
      | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p'
}
read_fps() {
    # 同理：`.*\([0-9]+\.[0-9]+\)` 这种贪婪前缀会把 59.8 捕获成 9.8（少一位数字）。
    # 用 grep -oE 从最左边开始找 `数字.数字 fps`。
    printf '%s' "$1" | grep -oE '[0-9][0-9]*\.[0-9]+ fps' | head -1 \
      | grep -oE '^[0-9][0-9]*\.[0-9]+'
}
FPS=""
for i in 1 2 3 4; do
    TITLE="$(read_title || true)"
    echo "SHOT-TITLE-$i=$TITLE"
    V="$(read_fps "${TITLE:-}" || true)"
    if [ -n "${V:-}" ] && awk "BEGIN{exit !($V > ${FPS:-0})}"; then FPS="$V"; fi
    if [ "$i" = 2 ]; then
        import -window "$WID" "$SHOT" 2>/dev/null || echo "SHOT-IMPORT-FAILED"
    fi
    sleep 2
done
echo "SHOT-FPS=${FPS:-unknown}"
# 收尾：**不要**用裸 `wait` —— 如果 wine 里的进程对 SIGTERM 没反应，
# `wait` 会一直阻塞（CI 上真的把整个 job 挂死了 23 分钟）。这里给 5 秒宽限后强杀。
kill $APP_PID 2>/dev/null || true
for _ in $(seq 1 20); do
    kill -0 $APP_PID 2>/dev/null || break
    sleep 0.25
done
kill -9 $APP_PID 2>/dev/null || true
echo "SHOT-OK"
EOSH
    start_xvfb
    set +e
    timeout 300 env WINECMD="$WINECMD" EXE_RUN="$EXE_RUN" LOG="$LOG" SHOT="$SHOT" bash "$RUN_DIR/shot.sh" \
        > "$RUN_DIR/shot.out" 2>&1
    set -e

    if grep -q SHOT-OK "$RUN_DIR/shot.out" && [ -s "$SHOT" ]; then
        pass "screenshot: 窗口已抓取（$(stat -c%s "$SHOT") bytes）"
        # 标题栏：默认是**系统标题栏**（对齐 Compose JVM 的 Window()），
        # 颜色由 DWM/系统主题决定（浅色主题下是浅色）——所以不能再写死 #2D2D30。
        # 这里改成模式无关的判定：顶部条带必须是一根**均匀的**横条，且与内容区颜色不同
        #（即"确实画了一根标题栏"，而不是内容铺到顶 或 空白/花屏）。
        TITLE_PX="$(convert "$SHOT" -format '%[pixel:p{8,8}]' info: 2>/dev/null || echo '?')"
        MID_PX="$(convert "$SHOT" -format '%[pixel:p{8,300}]' info: 2>/dev/null || echo '?')"
        STRIP_PX="$(convert "$SHOT" -format '%[pixel:p{200,4}]' info: 2>/dev/null || echo '?')"
        COLORS="$(convert "$SHOT" -format '%k' info: 2>/dev/null || echo 0)"
        case "$TITLE_PX" in
            # CSD（undecorated = true）：自绘深色标题栏
            *45,45,48*|*2D2D30*|*srgb\(45,45,48\)*) pass "screenshot: 标题栏颜色 = $TITLE_PX（自绘 CSD）" ;;
            # 系统标题栏：DWM 画的，只要与内容区不同色、且顶部横条均匀即可
            *) if [ "$TITLE_PX" != "$MID_PX" ] && [ "$TITLE_PX" = "$STRIP_PX" ]; then
                   pass "screenshot: 系统标题栏 = $TITLE_PX（内容区 $MID_PX）"
               else
                   fail "screenshot: 顶部疑似没有标题栏 / 不均匀 (top=$TITLE_PX strip=$STRIP_PX content=$MID_PX)"
               fi ;;
        esac
        if [ "$COLORS" -ge 200 ] 2>/dev/null; then
            pass "screenshot: 颜色数 $COLORS（界面确实画出了内容，不是空白窗口）"
        else
            fail "screenshot: 颜色数仅 $COLORS（疑似空白窗口）"
        fi
        # 外部帧率验证：画廊在跑「每帧 +1」的动画，标题里的实测 fps 必须
        #   * > 5   —— 动画确实在跑（不是卡死/停摆）
        #   * < 90  —— 被节流到刷新率附近（老代码是无节制重绘，这里会是 100+）
        FPS="$(grep -o 'SHOT-FPS=[0-9.]*' "$RUN_DIR/shot.out" 2>/dev/null | head -1 | cut -d= -f2)"
        case "${FPS:-unknown}" in
            ''|unknown) fail "screenshot: 没能从窗口标题读到帧率（见 $RUN_DIR/shot.out）" ;;
            *)
                if awk "BEGIN{exit !($FPS > 5 && $FPS < 90)}"; then
                    pass "screenshot: 画廊实测帧率 $FPS fps（动画在跑，且已被节流）"
                else
                    fail "screenshot: 画廊实测帧率 $FPS fps（期望 5..90：太小=动画停摆，太大=没节流）"
                fi
                ;;
        esac
    else
        fail "screenshot: 未能抓到窗口（见 $RUN_DIR/shot.out）"
        tail -20 "$RUN_DIR/shot.out" 2>/dev/null || true
    fi
fi

echo
echo "==================== 测试汇总 ===================="
printf '%s\n' "${SUMMARY[@]}"
echo "================================================="
if [ "$FAILED" = "0" ]; then
    echo "全部通过 ✅"
else
    echo "存在失败 ❌"
fi
exit "$FAILED"
