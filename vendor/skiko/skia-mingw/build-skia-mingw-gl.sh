#!/usr/bin/env bash
# 构建「带 GPU（Ganesh + OpenGL/WGL）后端」的 GNU-ABI Skia 静态库，
# 与 build-skia-mingw.sh（纯 CPU）并存：输出到 **另一个 out 目录**（out/mingw-gl），
# 不会覆盖现有的 CPU 预编译包。
#
# 背景（为什么要单独一份）：现有预编译包的 gn_args 里
#   skia_use_gl / vulkan / direct3d / angle / metal = false，
#   连 skia_enable_ganesh / skia_enable_graphite 都是 false
#   —— libskia.a 里没有任何 GPU 后端符号，所以「上 GPU 后端」第一步是重建 Skia。
#
# 注意 skia 的 gn/skia.gni：
#   skia_use_gl = skia_use_gl && skia_enable_ganesh
#   即开 GL 必须同时开 Ganesh。
#
# 用法：
#   nix-shell -p git gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run \
#       "SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw ./build-skia-mingw-gl.sh"
#
# 产物：$SKIA_MINGW_WORK/skia/out/mingw-gl/*.a
set -euo pipefail

SKIA_TAG="${SKIA_TAG:-m150-b8e40a7c49}"
WORK="${SKIA_MINGW_WORK:?请设置 SKIA_MINGW_WORK（要有 ~15GB 空间的目录）}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIA="$WORK/skia"
OUT_DIR="${SKIA_GL_OUT_DIR:-out/mingw-gl}"
JOBS="${SKIA_GL_JOBS:-12}"
PROXY="${ALL_PROXY:-socks5h://172.20.128.142:7897}"

[ -d "$SKIA/.git" ] || { echo "!! 找不到 skia 源码树：$SKIA（先跑 build-skia-mingw.sh）" >&2; exit 1; }

cd "$SKIA"

# 复用 CPU 构建时的补丁/依赖状态（幂等）
if git apply --check "$HERE/skia-mingw.patch" 2>/dev/null; then
    git apply "$HERE/skia-mingw.patch"
    echo "==> 已应用 mingw 补丁"
elif git apply --reverse --check "$HERE/skia-mingw.patch" 2>/dev/null; then
    echo "==> mingw 补丁已在树上"
else
    echo "!! 补丁无法应用（skia 树已改动？）" >&2
fi

ARGS="$(tr '\n' ' ' < "$HERE/gn_args_gl.txt")"
echo "==> gn gen $OUT_DIR"
echo "    args: $ARGS"
gn gen "$OUT_DIR" --args="$ARGS"

echo "==> ninja（-j $JOBS），这一步很久"
ninja -C "$OUT_DIR" -j "$JOBS" \
    skia skparagraph skshaper skunicode skunicode_core skunicode_icu skottie sksg svg

echo
echo "==> 完成。GPU 版产物："
ls -la "$OUT_DIR"/*.a 2>/dev/null || ls -la "$OUT_DIR"
echo
echo "==> 自检：libskia.a 里有没有 GL 后端符号（应该有 MakeGL / MakeWin）"
nm -g --defined-only "$OUT_DIR/libskia.a" 2>/dev/null \
    | grep -c "GrDirectContexts::MakeGL\|GrGLInterfaces::MakeWin" || true
