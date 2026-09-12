#!/usr/bin/env bash
# 构建 GNU-ABI（MinGW-w64 / Itanium mangling）的 Skia 静态库集合，
# 供 Kotlin/Native 的 mingwX64 目标链接使用。
#
# 背景：JetBrains 发布的 Windows Skia 是 MSVC ABI（符号形如
#   ?drawRect@SkCanvas@@QEAAXAEBUSkRect@@...），
# 而 Kotlin/Native 的 mingwX64 后端产出 Itanium ABI（_ZN8SkCanvas8drawRect...），
# 两者无法链接。此脚本从源码构建 Itanium ABI 的 Skia。
#
# 用法：
#   nix-shell -p git gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run \
#       "SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw ./build-skia-mingw.sh"
#
# 产物：$SKIA_MINGW_WORK/skia/out/mingw/*.a （22 个静态库，libskia.a 约 33MB）
set -euo pipefail

SKIA_TAG="${SKIA_TAG:-m150-b8e40a7c49}"
WORK="${SKIA_MINGW_WORK:?请设置 SKIA_MINGW_WORK（要有 ~15GB 空间的目录）}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIA="$WORK/skia"
PROXY="${ALL_PROXY:-socks5h://172.20.128.142:7897}"

mkdir -p "$WORK"

# 1) 克隆与 JetBrains release 同源的 skia
if [ ! -d "$SKIA/.git" ]; then
    echo "==> 克隆 JetBrains/skia @ $SKIA_TAG"
    ALL_PROXY="$PROXY" git clone --depth 1 --branch "$SKIA_TAG" \
        https://github.com/JetBrains/skia "$SKIA"
fi

# 2) 应用 ComposeKN 的 mingw 补丁（可重复执行）
cd "$SKIA"
if git apply --check "$HERE/skia-mingw.patch" 2>/dev/null; then
    git apply "$HERE/skia-mingw.patch"
    echo "==> 已应用 mingw 补丁"
elif git apply --reverse --check "$HERE/skia-mingw.patch" 2>/dev/null; then
    echo "==> mingw 补丁已在树上"
else
    echo "!! 补丁无法应用（skia 树已改动？）" >&2
fi

# 3) 拉取最小依赖集
python3 "$HERE/fetch_deps.py" freetype harfbuzz icu libpng zlib libjpeg-turbo libwebp expat

# 4) GN 配置 + 构建
ARGS="$(tr '\n' ' ' < "$HERE/gn_args.txt")"
gn gen out/mingw --args="$ARGS"
ninja -C out/mingw -j "$(nproc)" \
    skia skparagraph skshaper skunicode skunicode_core skunicode_icu skottie sksg svg

echo
echo "==> 完成。产物："
ls -la out/mingw/*.a
