#!/usr/bin/env bash
# 端到端构建 Windows 原生 (Kotlin/Native mingwX64) demo，并链接 K/N exe。
#
# 两种模式：
#
#   A. 预编译模式（CI 用；不需要 nix、不需要 mingw 交叉编译器）
#         SKIA_MINGW_PREBUILT=/path/to/skia-mingw-<tag> \
#             ./vendor/skiko/skia-mingw/build-windows-native-demo.sh
#      包内容见 vendor/skiko/skia-mingw/README-prebuilt.md，
#      由 scripts/fetch-skia-mingw.sh 下载。包里的运行时/导入库/shim 都是**静态归档**，
#      所以除了 konan 自带的 lld 之外不需要任何宿主工具链。
#
#   B. 从源码构建模式（本机开发用）
#         SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw \
#         nix-shell ./shell.nix --run ./vendor/skiko/skia-mingw/build-windows-native-demo.sh
#      前置：先跑 build-skia-mingw.sh 生成 GNU-ABI 的 Skia 静态库。
#      本脚本负责 shim + 导入库修补 + 链接（Skia 本身由 build-skia-mingw.sh 构建）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
PREBUILT="${SKIA_MINGW_PREBUILT:-}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die()  { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

if [ -n "$PREBUILT" ]; then
    # ------------------------------------------------------------------
    # A. 预编译模式
    # ------------------------------------------------------------------
    [ -d "$PREBUILT" ] || die "SKIA_MINGW_PREBUILT=$PREBUILT 不是目录"
    [ -f "$PREBUILT/manifest.json" ] || die "$PREBUILT/manifest.json 不存在（不是预编译包？）"
    [ -f "$PREBUILT/libs/libskia.a" ] || die "$PREBUILT/libs/libskia.a 不存在"
    for lib in libmcfgthread.a libmsvcrt.a libucrtbase.a libntdll.a libmingwex.a; do
        [ -f "$PREBUILT/runtime/$lib" ] || die "$PREBUILT/runtime/$lib 不存在"
    done
    [ -f "$PREBUILT/shim/libstdcxx-symbols-shim.a" ] || die "$PREBUILT/shim/libstdcxx-symbols-shim.a 不存在"

    SKIA_OUT="$PREBUILT/libs"
    SHIM_DIR="$PREBUILT/shim"
    PATCHED_DIR="$PREBUILT/runtime"
    MCF_LIB="$PREBUILT/runtime"
    MINGW_W64_LIB="$PREBUILT/runtime"
    # 预编译包里已经删掉了 _onexit 兼容包装，这里只是复核一遍（没有 nm 就跳过）
    if command -v x86_64-w64-mingw32-nm >/dev/null 2>&1; then
        for lib in "$PATCHED_DIR/libmsvcrt.a" "$PATCHED_DIR/libucrtbase.a"; do
            n="$(x86_64-w64-mingw32-nm --defined-only "$lib" 2>/dev/null | grep -cE ' [TtWw] _onexit$' || true)"
            [ "$n" = "0" ] || die "$lib 里仍有 _onexit（会触发 atexit 递归，exe 无法启动）"
        done
    fi
    info "预编译模式：$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print("skia " + d.get("skia_tag","?") + " / gcc " + d.get("mingw_gcc","?"))' "$PREBUILT/manifest.json" 2>/dev/null || echo "skia ?")"
else
    # ------------------------------------------------------------------
    # B. 从源码构建模式（需要 nix-shell 提供的 mingw 交叉工具链）
    # ------------------------------------------------------------------
    WORK="${SKIA_MINGW_WORK:?请设置 SKIA_MINGW_WORK（或改用 SKIA_MINGW_PREBUILT）}"
    SKIA_OUT="$WORK/skia/out/mingw"
    SHIM_DIR="$WORK/shim"
    PATCHED_DIR="$SHIM_DIR/patched-libs"
    MINGW_W64_LIB="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libmsvcrt.a)")"

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
    [ -f "$MCF_LIB/libmcfgthread.a" ] || die "找不到 libmcfgthread.a（MCF_LIB=$MCF_LIB）"

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
        [ "$left" = "0" ] || die "$2 仍有 _onexit 定义，链接会再次递归"
    }

    patch_ucrt_lib "$MINGW_W64_LIB/libucrtbase.a" libucrtbase.a
    patch_ucrt_lib "$MINGW_W64_LIB/libmsvcrt.a"   libmsvcrt.a
fi

# 3) 额外的运行时/导入库（全部为静态或系统 DLL，产物无需附带 DLL）
LIBS="$SHIM_DIR/libstdcxx-symbols-shim.a"                # 含 _onexit，必须排在 UCRT 库之前
LIBS="$LIBS,$MCF_LIB/libmcfgthread.a"
LIBS="$LIBS,$PATCHED_DIR/libmsvcrt.a,$PATCHED_DIR/libucrtbase.a"
LIBS="$LIBS,$MINGW_W64_LIB/libntdll.a,$MINGW_W64_LIB/libmingwex.a"

# 注意：这里**不要**把 MinGW 的库目录塞进 -L。
# 一旦塞进去，konan 链接时默认的 -lmingw32 会优先解析到那个精简版
# libmingw32.a，而 mingw_app_type / __security_init_cookie / __mingw_init_ehandler /
# mingw_initlts*_force 这些只存在于 konan 自带 sysroot 的 libmingw32.a 里，
# 链接会报这 7 个未定义符号。上面这些库全部用绝对路径给出，不需要 -L。
LIBDIRS=""

# 4) 链接 K/N exe
cd "$REPO"
./gradlew :samples:windows-demo:linkReleaseExecutableMingwX64 --no-daemon \
    -Pskiko.skia.mingw.dir="$SKIA_OUT" \
    -Pskiko.mingw.libs="$LIBS" \
    ${LIBDIRS:+-Pskiko.mingw.libDirs="$LIBDIRS"}

EXE_DIR="$REPO/samples/windows-demo/build/bin/mingwX64/releaseExecutable"
if [ -f "$PREBUILT/icudtl.dat" ]; then
    # Skia 的 SkLoadICU 只在 exe 同目录找 icudtl.dat（找不到时文本排版会失败/崩溃）
    cp -f "$PREBUILT/icudtl.dat" "$EXE_DIR/icudtl.dat"
elif [ -f "${SKIA_MINGW_WORK:-}/skia/out/mingw/icudtl.dat" ]; then
    cp -f "${SKIA_MINGW_WORK}/skia/out/mingw/icudtl.dat" "$EXE_DIR/icudtl.dat"
fi

echo
echo "==> 产物: $EXE_DIR/windows-demo.exe"
