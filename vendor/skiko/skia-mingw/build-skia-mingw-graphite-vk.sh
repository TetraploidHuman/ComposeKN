#!/usr/bin/env bash
# 构建「Graphite + Vulkan」（同时保留 Ganesh+GL）的 GNU-ABI Skia 静态库。
# 输出到 out/mingw-graphite-vk，不覆盖 out/mingw-gl。
#
# 用法：
#   nix-shell -p git gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run \
#       "SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw \
#        ./vendor/skiko/skia-mingw/build-skia-mingw-graphite-vk.sh"
#
# 产物：$SKIA_MINGW_WORK/skia/out/mingw-graphite-vk/*.a
set -euo pipefail

SKIA_TAG="${SKIA_TAG:-m150-b8e40a7c49}"
WORK="${SKIA_MINGW_WORK:?请设置 SKIA_MINGW_WORK（要有 ~15GB 空间的目录）}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKIA="$WORK/skia"
OUT_DIR="${SKIA_GRAPHITE_VK_OUT_DIR:-out/mingw-graphite-vk}"
JOBS="${SKIA_GRAPHITE_VK_JOBS:-12}"

[ -d "$SKIA/.git" ] || { echo "!! 找不到 skia 源码树：$SKIA（先跑 build-skia-mingw.sh）" >&2; exit 1; }

cd "$SKIA"

# 复用 CPU/GL 构建时的补丁（幂等）
if git apply --check "$HERE/skia-mingw.patch" 2>/dev/null; then
    git apply "$HERE/skia-mingw.patch"
    echo "==> 已应用 mingw 补丁"
elif git apply --reverse --check "$HERE/skia-mingw.patch" 2>/dev/null; then
    echo "==> mingw 补丁已在树上"
else
    echo "!! 补丁无法应用（skia 树已改动？）" >&2
fi

echo "==> 拉取 Graphite/Vulkan 额外 deps"
export SKIA_MINGW_WORK="$WORK"
python3 "$HERE/fetch_deps.py" \
    vulkanmemoryallocator vulkan-headers vulkan-tools \
    spirv-headers spirv-tools

# Strip comments before flattening — a leading `# …` line collapsed into one
# string would comment-out every subsequent assign (seen with dng_sdk phantom deps).
ARGS="$(grep -v '^\s*#' "$HERE/gn_args_graphite_vk.txt" | grep -v '^\s*$' | tr '\n' ' ')"
echo "==> gn gen $OUT_DIR"
echo "    args: $ARGS"
gn gen "$OUT_DIR" --args="$ARGS"

echo "==> ninja（-j $JOBS），这一步很久"
ninja -C "$OUT_DIR" -j "$JOBS" \
    skia skparagraph skshaper skunicode skunicode_core skunicode_icu skottie sksg svg

echo
echo "==> 完成。Graphite+Vulkan 产物："
ls -la "$OUT_DIR"/*.a 2>/dev/null || ls -la "$OUT_DIR"
echo
echo "==> 自检：libskia.a 里有没有 Graphite Vulkan / GL 符号"
nm -g --defined-only "$OUT_DIR/libskia.a" 2>/dev/null \
    | grep -E "MakeVulkan|MakeGL|graphite" | head -40 || true
