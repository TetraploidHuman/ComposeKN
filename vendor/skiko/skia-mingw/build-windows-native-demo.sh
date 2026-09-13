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
MINGW_W64_LIB="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libmsvcrt.a)")"
GCC_LIB="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libgcc.a)")"

# libmcfgthread.a 来自独立的 nixpkgs 包，`-print-file-name` 通常找不到它
# （会原样打印 basename，dirname 得到 "." —— 于是 LIBS 里出现 ./libmcfgthread.a
#   这种不存在的路径，链接期才炸）。这里按「文件是否存在」判断，并且用
# `find -print -quit` 而不是 `find | head -1`（后者会让 find 吃 SIGPIPE，
# 在 set -o pipefail 下把整行变成 141）。
mcf_candidate="$(x86_64-w64-mingw32-g++ -print-file-name=libmcfgthread.a 2>/dev/null || true)"
if [ -f "$mcf_candidate" ]; then
    MCF_LIB="$(dirname "$mcf_candidate")"
else
    mcf_found="$(find /nix/store -maxdepth 5 -name libmcfgthread.a -print -quit 2>/dev/null || true)"
    MCF_LIB="$(dirname "${mcf_found:-/nonexistent}")"
fi
[ -f "$MCF_LIB/libmcfgthread.a" ] || { echo "!! 找不到 libmcfgthread.a（MCF_LIB=$MCF_LIB）" >&2; exit 1; }

# 1) 构建符号补齐 shim（libstdc++ 15 新增、konan 的 GCC 9.2 里没有的 3 个符号）
mkdir -p "$SHIM_DIR"
if [ ! -f "$SHIM_DIR/libstdcxx-symbols-shim.a" ] || \
   [ "$HERE/shim/libstdcxx-symbols-shim.cpp" -nt "$SHIM_DIR/libstdcxx-symbols-shim.a" ]; then
    x86_64-w64-mingw32-g++ -std=c++20 -O2 -fno-exceptions -fno-rtti \
        -c "$HERE/shim/libstdcxx-symbols-shim.cpp" -o "$SHIM_DIR/libstdcxx-symbols-shim.o"
    x86_64-w64-mingw32-ar rcs "$SHIM_DIR/libstdcxx-symbols-shim.a" \
        "$SHIM_DIR/libstdcxx-symbols-shim.o"
fi

# 2) 修补 nixpkgs (UCRT) 的导入库
#
#    坑（Windows exe 在进入 main 之前静默退出的根因）：
#      konan 的 crt2.o 提供 atexit：  int atexit(void(*f)(void)) { return _onexit(f) ? 0 : -1; }
#      nixpkgs 的 UCRT 导入库 libucrtbase.a / libmsvcrt.a 里各带一个 _onexit 兼容包装：
#                                     int _onexit(_onexit_t f) { return atexit(f) ? f : NULL; }
#      两者互为递归。第一个触发点是 crtbegin.o 注册的静态构造器 __gcc_register_frame
#      （函数体只有一句 atexit(__gcc_deregister_frame)），于是进程在 __do_global_ctors
#      阶段就无限递归 -> 栈溢出 -> 在进入 main 之前静默退出（退出码 1、无任何输出）。
#      Windows 实机与 Wine 表现完全一致。
#
#    修法：① shim 里导出 _onexit（见 shim/libstdcxx-symbols-shim.cpp 第 4 节），
#          shim 排在所有 UCRT 导入库之前，递归被打断；
#          ② 顺手删掉这两个包装成员，避免它们因别的原因被拉进来造成重复定义。
PATCHED_DIR="$SHIM_DIR/patched-libs"
mkdir -p "$PATCHED_DIR"
patch_ucrt_lib() {   # $1 = 原始归档, $2 = 输出归档名
    local src="$1" out="$PATCHED_DIR/$2" mem="" nm_out=""
    cp --no-preserve=mode -f "$src" "$out"
    chmod u+w "$out"
    # 注意：不要把 `nm` 直接管道给 `awk ... exit`。
    # awk 一命中就退出 → nm 收到 SIGPIPE → 在 `set -o pipefail` 下整行返回 141，
    # `set -e` 把脚本当场干掉（症状：脚本无任何输出、退出码 141）。
    # 所以先完整收下 nm 的输出，再让 awk 读完全部输入（不在规则里提前 exit）。
    nm_out="$(x86_64-w64-mingw32-nm -A --defined-only "$src" 2>/dev/null || true)"
    mem="$(printf '%s\n' "$nm_out" | awk -v a="$src:" '
        !found && / [TtWw] _onexit$/ {sub("^"a, ""); sub(":.*", ""); mem=$0; found=1}
        END {if (found) print mem}')"
    if [ -n "$mem" ]; then
        x86_64-w64-mingw32-ar d "$out" "$mem"
    fi
    local left
    left="$(x86_64-w64-mingw32-nm --defined-only "$out" 2>/dev/null \
           | grep -cE ' [TtWw] _onexit$' || true)"
    printf '    patched %-16s 删除成员[%s] 剩余_onexit=%s\n' "$2" "$mem" "$left"
    [ "$left" = "0" ] || { echo "!! $2 仍有 _onexit 定义，链接会再次递归" >&2; exit 1; }
}

patch_ucrt_lib "$MINGW_W64_LIB/libucrtbase.a" libucrtbase.a
patch_ucrt_lib "$MINGW_W64_LIB/libmsvcrt.a"   libmsvcrt.a

# 3) 额外的运行时/导入库（全部为静态或系统 DLL，产物无需附带 DLL）
LIBS="$SHIM_DIR/libstdcxx-symbols-shim.a"                # 含 _onexit，必须排在 UCRT 库之前
LIBS="$LIBS,$MCF_LIB/libmcfgthread.a"
LIBS="$LIBS,$PATCHED_DIR/libmsvcrt.a,$PATCHED_DIR/libucrtbase.a"
LIBS="$LIBS,$MINGW_W64_LIB/libntdll.a,$MINGW_W64_LIB/libmingwex.a"

# 注意：这里**不要**把 nixpkgs 的 MinGW 库目录塞进 -L。
# 一旦塞进去，konan 链接时默认的 -lmingw32 会优先解析到 nixpkgs 那个精简版
# libmingw32.a，而 mingw_app_type / __security_init_cookie / __mingw_init_ehandler /
# mingw_initlts*_force 这些只存在于 konan 自带 sysroot 的 libmingw32.a 里，
# 链接会报这 7 个未定义符号。nixpkgs 的库全部用绝对路径给出，不需要 -L。
LIBDIRS=""

# 4) 链接 K/N exe
cd "$REPO"
./gradlew :samples:windows-demo:linkReleaseExecutableMingwX64 --no-daemon \
    -Pskiko.skia.mingw.dir="$SKIA_OUT" \
    -Pskiko.mingw.libs="$LIBS" \
    ${LIBDIRS:+-Pskiko.mingw.libDirs="$LIBDIRS"}

echo
echo "==> 产物: samples/windows-demo/build/bin/mingwX64/releaseExecutable/windows-demo.exe"
