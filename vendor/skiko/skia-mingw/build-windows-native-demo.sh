#!/usr/bin/env bash
# 端到端构建 Windows 原生 (Kotlin/Native mingwX64) demo。
#
# 前置：先跑 build-skia-mingw.sh 生成 GNU-ABI 的 Skia 静态库，
#       并准备好 libstdcxx-symbols-shim.a（见 shim/README 或本脚本的 build_shim）。
#
# 用法：
#   SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw \
#   nix-shell -p git gn ninja python3 pkgsCross.mingwW64.stdenv.cc --run \
#       "./vendor/skiko/skia-mingw/build-windows-native-demo.sh"
set -euo pipefail

WORK="${SKIA_MINGW_WORK:?请设置 SKIA_MINGW_WORK}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
SKIA_OUT="$WORK/skia/out/mingw"
SHIM_DIR="$WORK/shim"
MINGW_LIB_DIR="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libgcc.a)")/../../../../x86_64-w64-mingw32/lib/../lib"
MINGW_W64_LIB="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libmsvcrt.a)")"
GCC_LIB="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libgcc.a)")"
MCF_LIB="$(x86_64-w64-mingw32-g++ -print-file-name=libmcfgthread.a | xargs dirname 2>/dev/null || true)"
[ -d "$MCF_LIB" ] || MCF_LIB=$(dirname "$(find /nix/store -maxdepth 4 -name libmcfgthread.a 2>/dev/null | head -1)")

# 1) 构建符号补齐 shim（libstdc++ 15 新增、konan 的 GCC 9.2 里没有的 3 个符号）
mkdir -p "$SHIM_DIR"
if [ ! -f "$SHIM_DIR/libstdcxx-symbols-shim.a" ]; then
    x86_64-w64-mingw32-g++ -std=c++20 -O2 -fno-exceptions -fno-rtti \
        -c "$HERE/shim/libstdcxx-symbols-shim.cpp" -o "$SHIM_DIR/libstdcxx-symbols-shim.o"
    x86_64-w64-mingw32-ar rcs "$SHIM_DIR/libstdcxx-symbols-shim.a" \
        "$SHIM_DIR/libstdcxx-symbols-shim.o"
fi

# 2) 额外的运行时/导入库（全部为静态或系统 DLL，产物无需附带 DLL）
LIBS="$SHIM_DIR/libstdcxx-symbols-shim.a"
LIBS="$LIBS,$MCF_LIB/libmcfgthread.a"
LIBS="$LIBS,$MINGW_W64_LIB/libmsvcrt.a,$MINGW_W64_LIB/libucrtbase.a"
LIBS="$LIBS,$MINGW_W64_LIB/libntdll.a,$MINGW_W64_LIB/libmingwex.a"

LIBDIRS="$MINGW_W64_LIB,$GCC_LIB"

# 3) 链接 K/N exe
cd "$REPO"
./gradlew :samples:windows-demo:linkReleaseExecutableMingwX64 --no-daemon \
    -Pskiko.skia.mingw.dir="$SKIA_OUT" \
    -Pskiko.mingw.libs="$LIBS" \
    -Pskiko.mingw.libDirs="$LIBDIRS"

echo
echo "==> 产物: samples/windows-demo/build/bin/mingwX64/releaseExecutable/windows-demo.exe"
