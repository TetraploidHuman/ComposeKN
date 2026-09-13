#!/usr/bin/env bash
# 下载 + 校验 + 解包「预编译 mingw-Skia 包」（见 vendor/skiko/skia-mingw/README-prebuilt.md）。
#
# 这个包让 Windows exe 的链接不再依赖 nix / mingw 交叉工具链 —— 包里全部是静态归档，
# 链接只用 konan 自带的 lld。CI（ubuntu-latest）就是靠它把「构建 HEAD 的 exe + 跑原生
# 自检」压到十几分钟内（从源码构建 Skia 要半小时以上、15GB 磁盘）。
#
# 用法：
#   ./scripts/fetch-skia-mingw.sh [目标目录]
#       # 默认 $SKIA_MINGW_WORK，未设置则用 /tmp/composekn-skia-mingw
#   COMPOSEKN_SKIA_PREBUILT_URL=file:///path/to.tar.zst ./scripts/fetch-skia-mingw.sh /tmp/x
#
# 解包后目录布局：<dir>/skia-mingw-<tag>/{libs,runtime,shim,icudtl.dat,manifest.json}
# 打包/上传见 vendor/skiko/skia-mingw/package-prebuilt.sh。
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIA_TAG="$(cat "$REPO/vendor/skiko/skia-mingw/SKIA_TAG")"
PKG_NAME="skia-mingw-$SKIA_TAG"
DEST="${1:-${SKIA_MINGW_WORK:-/tmp/composekn-skia-mingw}}"
SHA_FILE="$REPO/vendor/skiko/skia-mingw/prebuilt.sha256"

URL="${COMPOSEKN_SKIA_PREBUILT_URL:-}"
if [ -z "$URL" ]; then
    URL="https://github.com/TetraploidHuman/ComposeKN/releases/download/${PKG_NAME}/${PKG_NAME}.tar.zst"
fi

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die()  { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

mkdir -p "$DEST"
# 解包后的目标目录：<DEST>/<PKG_NAME>
if [ -f "$DEST/$PKG_NAME/manifest.json" ]; then
    info "已存在：$DEST/$PKG_NAME（跳过下载）"
    echo "$DEST/$PKG_NAME"
    exit 0
fi

TARBALL="$DEST/$PKG_NAME.tar.zst"
info "下载 $PKG_NAME.tar.zst"
case "$URL" in
    file://*|/*) cp -f "${URL#file://}" "$TARBALL" ;;
    *) curl -fL --retry 5 --retry-all-errors --retry-delay 3 \
             --connect-timeout 30 -o "$TARBALL" "$URL" ;;
esac
[ -s "$TARBALL" ] || die "下载失败或文件为空：$URL"

ACTUAL="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
EXPECTED="$( [ -f "$SHA_FILE" ] && tr -d '[:space:]' < "$SHA_FILE" || true )"
if [ -n "$EXPECTED" ]; then
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        die "sha256 不匹配（期望 $EXPECTED 实际 $ACTUAL）—— 资产可能被替换过，拒绝解包"
    fi
    info "sha256 校验通过：$ACTUAL"
else
    info "未找到 $SHA_FILE，跳过校验（实际 sha256=$ACTUAL）"
fi

info "解包"
command -v zstd >/dev/null 2>&1 || die "找不到 zstd（apt install zstd）"
tar --zstd -xf "$TARBALL" -C "$DEST"
[ -f "$DEST/$PKG_NAME/manifest.json" ] || die "解包后没有 manifest.json（包结构不对？）"

info "完成：$DEST/$PKG_NAME"
ls -la "$DEST/$PKG_NAME"
echo "$DEST/$PKG_NAME"
