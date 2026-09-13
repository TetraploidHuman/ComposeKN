#!/usr/bin/env bash
# 构建并运行 ComposeKN Windows GPU 后端「阶段 0」冒烟测试。
#
# 前置：先跑 build-skia-mingw-gl.sh 产出 out/mingw-gl/*.a（Ganesh + GL 版 Skia）。
#
# 用法（仓库根目录）：
#   nix-shell ./shell.nix --run \
#     "SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw ./vendor/skiko/skia-mingw/gl-smoke/run-gl-smoke.sh"
#
# 默认会自己起 Xvfb + wine 跑一遍（和 scripts/test-windows-native.sh 同样的方式）；
# 只想编译不想跑就加 --no-run。
set -euo pipefail

WORK="${SKIA_MINGW_WORK:-/mnt/hdd2/KtLLM/skia-mingw}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIA="$WORK/skia"
OUT_DIR="${SKIA_GL_OUT_DIR:-$SKIA/out/mingw-gl}"
BUILD="$HERE/build"
RUN="${COMPOSEKN_GL_SMOKE_DIR:-/tmp/composekn-gl-smoke}"
CXX="${MINGW_CXX:-x86_64-w64-mingw32-g++}"

[ -d "$OUT_DIR" ] || { echo "!! 找不到 $OUT_DIR（先跑 build-skia-mingw-gl.sh）" >&2; exit 1; }

mkdir -p "$BUILD" "$RUN"

echo "==> 编译 gl_smoke.exe（$CXX）"
# shellcheck disable=SC2086
"$CXX" -std=c++17 -O1 -o "$BUILD/gl_smoke.exe" "$HERE/gl_smoke.cpp" \
    -I"$SKIA" \
    -D__GLIBCXX_TYPE_INT_N_0=__int128 \
    -D__GLIBCXX_BITSIZE_INT_N_0=128 \
    -DSKCMS_HAS_MUSTTAIL=0 \
    -Wl,--start-group "$OUT_DIR"/*.a -Wl,--end-group \
    -lopengl32 -lgdi32 -luser32 -lole32 -luuid -ladvapi32 -lusp10 -lfontsub -lwinmm \
    -static -static-libgcc -static-libstdc++
ls -l "$BUILD/gl_smoke.exe"

if [ "${1:-}" = "--no-run" ]; then
    echo "==> 跳过运行（--no-run）"
    exit 0
fi

cp -f "$BUILD/gl_smoke.exe" "$RUN/gl_smoke.exe"
ICUDTL="$(ls /tmp/composekn-prebuilt/*/icudtl.dat 2>/dev/null | head -1)"
[ -n "$ICUDTL" ] && cp -f "$ICUDTL" "$RUN/icudtl.dat"

if [ -n "${DISPLAY:-}" ]; then
    echo "==> 直接用现有 DISPLAY=$DISPLAY 跑"
    cd "$RUN" && wine ./gl_smoke.exe
    exit $?
fi

export WINEPREFIX="${WINEPREFIX:-/mnt/hdd2/KtLLM/wineprefix}"
export WINEDEBUG=-all
Xvfb :89 -screen 0 1024x768x24 -nolisten tcp >"$RUN/xvfb.log" 2>&1 &
XPID=$!
export DISPLAY=:89
for _ in $(seq 1 40); do [ -e /tmp/.X89-lock ] && break; sleep 0.25; done
sleep 1
set +e
( cd "$RUN" && wine ./gl_smoke.exe ) >"$RUN/smoke.log" 2>&1
RC=$?
set -e
cat "$RUN/smoke.log"
kill -9 "$XPID" 2>/dev/null || true
echo "==> gl_smoke 退出码 = $RC（日志：$RUN/smoke.log）"
exit "$RC"
