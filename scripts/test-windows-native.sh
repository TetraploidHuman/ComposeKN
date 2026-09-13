#!/usr/bin/env bash
# ComposeKN Windows 原生 exe 的自动化测试驱动。
#
# 三层验证（对应 exe 内置的 `--selftest` 三阶段）：
#   1. logic  —— 纯逻辑断言（键位映射/消息解码/输入状态），不需要窗口
#   2. render —— 离屏光栅化真实 Compose 场景 + 像素断言（布局/density/CSD 标题栏/
#                点击与键盘输入、滚轮滚动），同样不需要窗口
#   3. window —— 真实 Win32 窗口：剪贴板桥接、逐帧渲染循环、合成点击、干净退出
#   4. 截图   —— 独立于程序自述的外部验证：抓窗口 PNG，检查标题栏颜色/色彩数量
#
# 用法（在仓库根目录）：
#   nix-shell -p wine64 xvfb xauth imagemagick xwininfo \
#       --run ./scripts/test-windows-native.sh
#
#   --skip-build    不重新链接，直接用现有的 exe
#   --exe=PATH      用指定的 exe（隐含 --skip-build；CI 从构建产物里取）
#   --no-screenshot 跳过截图阶段（没有 X 时）
#   --only=<phase>  只跑某个阶段: logic|window|screenshot
#
# 环境变量：
#   SKIA_MINGW_PREBUILT=<dir>  预编译 mingw-Skia 包（跳过 Skia 构建，icudtl.dat 从这里取）
#   SKIA_MINGW_WORK=<dir>      从源码构建时的 Skia 工作目录
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXE="$REPO/samples/windows-demo/build/bin/mingwX64/releaseExecutable/windows-demo.exe"
EXE_OVERRIDE=""
SKIA_WORK="${SKIA_MINGW_WORK:-/mnt/hdd2/KtLLM/skia-mingw}"
# 预编译包模式（CI 用，见 vendor/skiko/skia-mingw/README-prebuilt.md）：
# 不构建 Skia，直接用下载好的静态库；icudtl.dat 也在包里。
PREBUILT="${SKIA_MINGW_PREBUILT:-}"
if [ -n "$PREBUILT" ]; then
    ICUDTL="$PREBUILT/icudtl.dat"
else
    ICUDTL="$SKIA_WORK/skia/out/mingw/icudtl.dat"
fi
RUN_DIR="${COMPOSEKN_WINTEST_DIR:-/tmp/composekn-wintest}"
export WINEPREFIX="${WINEPREFIX:-/mnt/hdd2/KtLLM/wineprefix}"
export WINEDEBUG="${WINEDEBUG:--all}"

SKIP_BUILD=0
DO_SCREENSHOT=1
ONLY=""

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --no-screenshot) DO_SCREENSHOT=0 ;;
        --only=*) ONLY="${arg#--only=}" ;;
        --exe=*) EXE_OVERRIDE="${arg#--exe=}"; SKIP_BUILD=1 ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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

# exe 必须和 icudtl.dat 同目录（SkLoadICU 找不到数据文件会导致文本排版失败）
mkdir -p "$RUN_DIR"
cp -f "$EXE" "$RUN_DIR/windows-demo.exe"
if [ -f "$ICUDTL" ]; then
    cp -f "$ICUDTL" "$RUN_DIR/icudtl.dat"
else
    info "警告: 找不到 icudtl.dat（$ICUDTL），文本排版可能失败"
fi
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

# ----------------------------------------------------------- 3. 真实窗口
if [ -z "$ONLY" ] || [ "$ONLY" = "window" ]; then
    run_phase window window 1
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
        # 标题栏（左上角）应当是 CSD 深色 #2D2D30
        TITLE_PX="$(convert "$SHOT" -format '%[pixel:p{8,8}]' info: 2>/dev/null || echo '?')"
        COLORS="$(convert "$SHOT" -format '%k' info: 2>/dev/null || echo 0)"
        case "$TITLE_PX" in
            *45,45,48*|*2D2D30*|*srgb\(45,45,48\)*) pass "screenshot: 标题栏颜色 = $TITLE_PX" ;;
            *) fail "screenshot: 标题栏颜色异常 ($TITLE_PX, 期望 #2D2D30)" ;;
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
