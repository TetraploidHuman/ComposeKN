#!/usr/bin/env bash
# Link LumiCode mingwX64 via ComposeKN + prebuilt MinGW Skia.
# Mirrors vendor/skiko/skia-mingw/build-windows-native-demo.sh but targets :samples:lumicode.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PREBUILT="${SKIA_MINGW_PREBUILT:-/tmp/composekn-skia-mingw/skia-mingw-$(cat "$REPO/vendor/skiko/skia-mingw/SKIA_TAG")}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die()  { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

[ -d "$PREBUILT" ] || die "SKIA_MINGW_PREBUILT=$PREBUILT missing — run ./scripts/fetch-skia-mingw.sh first"
[ -f "$PREBUILT/manifest.json" ] || die "$PREBUILT/manifest.json missing"
[ -f "$PREBUILT/libs/libskia.a" ] || die "$PREBUILT/libs/libskia.a missing"

SKIA_OUT="$PREBUILT/libs"
SHIM_DIR="$PREBUILT/shim"
PATCHED_DIR="$PREBUILT/runtime"
MCF_LIB="$PREBUILT/runtime"
MINGW_W64_LIB="$PREBUILT/runtime"

LIBS="$SHIM_DIR/libstdcxx-symbols-shim.a"
LIBS="$LIBS,$MCF_LIB/libmcfgthread.a"
LIBS="$LIBS,$PATCHED_DIR/libmsvcrt.a,$PATCHED_DIR/libucrtbase.a"
LIBS="$LIBS,$MINGW_W64_LIB/libntdll.a,$MINGW_W64_LIB/libmingwex.a"

# Embed icudtl.dat (same as windows-demo pipeline)
GEN_SRC="$REPO/vendor/skiko/skiko/src/windowsMain/cpp/win32/win32_icu_data.generated.cpp"
ICUDTL="$PREBUILT/icudtl.dat"
[ -f "$ICUDTL" ] || die "missing $ICUDTL"
ICUDTL_ABS="$(cd "$(dirname "$ICUDTL")" && pwd)/$(basename "$ICUDTL")"
ICUDTL_ESC="${ICUDTL_ABS//\\/\\\\}"
ICUDTL_ESC="${ICUDTL_ESC//\"/\\\"}"
info "嵌入 icudtl.dat: $(du -h "$ICUDTL_ABS" | cut -f1)"

cat > "$GEN_SRC" <<EOF
extern "C" {
__asm__(
    ".section .rdata,\\"dr\\"\\n"
    ".p2align 12\\n"
    ".globl composekn_icudtl_data\\n"
    "composekn_icudtl_data:\\n"
    ".incbin \\"$ICUDTL_ESC\\"\\n"
    ".globl composekn_icudtl_end\\n"
    "composekn_icudtl_end:\\n"
    ".text\\n"
);
}
EOF

cd "$REPO"
export https_proxy="${https_proxy:-http://172.20.128.142:7897}"
export HTTPS_PROXY="${HTTPS_PROXY:-$https_proxy}"

info "链接 :samples:lumicode mingwX64"
./gradlew :samples:lumicode:linkReleaseExecutableMingwX64 --no-daemon \
    -Pskiko.skia.mingw.dir="$SKIA_OUT" \
    -Pskiko.mingw.libs="$LIBS"

EXE_DIR="$REPO/samples/lumicode/build/bin/mingwX64/releaseExecutable"
rm -f "$EXE_DIR/icudtl.dat"
ls -lh "$EXE_DIR"/lumicode.exe
info "产物: $EXE_DIR/lumicode.exe"
