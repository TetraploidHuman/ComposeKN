#!/usr/bin/env bash
# 把已构建好的 mingw-Skia 静态库 + 链接期需要的运行时/导入库打成一个**自包含预编译包**。
#
# 为什么需要它：构建 mingw-Skia 需要「nixpkgs 的 mingw 交叉工具链 + 15GB 磁盘 +
# 半小时以上」，CI（ubuntu-latest）上没法每次都重来。而这个包里的东西全部是
# **静态归档**，链接期只需要 konan 自带的 lld —— 也就是说拿到这个包之后，
# 目标机器上**不需要任何 mingw 交叉编译器、也不需要 nix**，只要有 JDK + konan
# 就能产出 Windows exe（见 vendor/skiko/skia-mingw/README-prebuilt.md）。
#
# 用法（在已经跑过 build-windows-native-demo.sh 的机器上）：
#   SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw \
#   nix-shell ./shell.nix --run ./vendor/skiko/skia-mingw/package-prebuilt.sh
#
# 产物：$SKIA_MINGW_WORK/prebuilt/skia-mingw-<skia tag>.tar.zst （约 60-70MB）
#       以及同目录的 .sha256 / manifest.json
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${SKIA_MINGW_WORK:?请设置 SKIA_MINGW_WORK}"
SKIA_OUT="$WORK/skia/out/mingw"
SHIM_DIR="$WORK/shim"
PREPARED="$SHIM_DIR/patched-libs"
SKIA_TAG="$(cat "$HERE/SKIA_TAG")"
OUT_ROOT="$WORK/prebuilt"
STAGE="$OUT_ROOT/stage"
PKG_NAME="skia-mingw-$SKIA_TAG"
PKG_DIR="$STAGE/$PKG_NAME"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die()  { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

[ -f "$SKIA_OUT/libskia.a" ] || die "找不到 $SKIA_OUT/libskia.a（先跑 build-skia-mingw.sh）"
[ -f "$SHIM_DIR/libstdcxx-symbols-shim.a" ] || die "找不到 shim（先跑 build-windows-native-demo.sh 生成）"
[ -f "$PREPARED/libucrtbase.a" ] || die "找不到 $PREPARED/libucrtbase.a（先跑 build-windows-native-demo.sh 生成）"

# 运行时/导入库：nixpkgs 交叉工具链里的那几个。它们同样是**普通静态归档**，
# 打进包里之后目标机器就不必再装 mingw 工具链了。
MINGW_W64_LIB="$(dirname "$(x86_64-w64-mingw32-g++ -print-file-name=libmsvcrt.a)")"
mcf_candidate="$(x86_64-w64-mingw32-g++ -print-file-name=libmcfgthread.a 2>/dev/null || true)"
if [ -f "$mcf_candidate" ]; then
    MCF_LIB="$(dirname "$mcf_candidate")"
else
    mcf_found="$(find /nix/store -maxdepth 5 -name libmcfgthread.a -print -quit 2>/dev/null || true)"
    MCF_LIB="$(dirname "${mcf_found:-/nonexistent}")"
fi
[ -f "$MCF_LIB/libmcfgthread.a" ] || die "找不到 libmcfgthread.a"

info "组装 $PKG_DIR"
rm -rf "$STAGE"
mkdir -p "$PKG_DIR/libs" "$PKG_DIR/runtime" "$PKG_DIR/shim"

# 1) Skia 本体（22 个 .a）+ ICU 数据文件
cp -f "$SKIA_OUT"/*.a "$PKG_DIR/libs/"
cp -f "$SKIA_OUT/icudtl.dat" "$PKG_DIR/"
# args.gn 只是给人看的（复现构建参数），链接用不到
cp -f "$SKIA_OUT/args.gn" "$PKG_DIR/libs/args.gn" 2>/dev/null || true

# 2) 运行期库：libmcfgthread（nixpkgs 的 mingw 工具链用它做线程模型，
#    Skia 的 libstdc++ 内联代码会引用 __mcfgthread_* 符号）
cp -f "$MCF_LIB/libmcfgthread.a" "$PKG_DIR/runtime/"

# 3) 系统导入库：已去掉 _onexit 兼容包装的 msvcrt/ucrtbase（见
#    build-windows-native-demo.sh 里那段「atexit <-> _onexit 递归」的注释）
cp -f "$PREPARED/libucrtbase.a" "$PREPARED/libmsvcrt.a" "$PKG_DIR/runtime/"
cp -f "$MINGW_W64_LIB/libntdll.a" "$MINGW_W64_LIB/libmingwex.a" "$PKG_DIR/runtime/"

# 4) 符号补齐 shim（_onexit + 3 个 GCC 11+ libstdc++ 符号）
cp -f "$SHIM_DIR/libstdcxx-symbols-shim.a" "$PKG_DIR/shim/"
cp -f "$HERE/shim/libstdcxx-symbols-shim.cpp" "$PKG_DIR/shim/"   # 供他人审计/重建

# 5) manifest：记录来源与哈希，便于复现和校验
GCC_VER="$(x86_64-w64-mingw32-g++ -dumpfullversion -dumpversion | tail -1)"
NIXPKGS_REV="$(cat "$(nix-instantiate --find-file nixpkgs 2>/dev/null)/.git-revision" 2>/dev/null || echo unknown)"
{
    echo "{"
    echo "  \"name\": \"$PKG_NAME\","
    echo "  \"skia_tag\": \"$SKIA_TAG\","
    echo "  \"skia_commit\": \"$(git -C "$WORK/skia" rev-parse HEAD 2>/dev/null || echo unknown)\","
    echo "  \"mingw_gcc\": \"$GCC_VER\","
    echo "  \"nixpkgs_rev\": \"$NIXPKGS_REV\","
    echo "  \"built_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"abi\": \"GNU/Itanium (MinGW-w64 + UCRT + mcfgthread), 供 Kotlin/Native mingwX64 链接\","
    echo "  \"lib_count\": $(ls -1 "$PKG_DIR/libs"/*.a | wc -l),"
    echo "  \"sha256\": {"
    first=1
    for f in "$PKG_DIR"/libs/*.a "$PKG_DIR"/runtime/*.a "$PKG_DIR"/shim/*.a "$PKG_DIR/icudtl.dat"; do
        rel="${f#"$PKG_DIR"/}"
        [ $first = 1 ] || echo ","
        first=0
        printf '    "%s": "%s"' "$rel" "$(sha256sum "$f" | cut -d' ' -f1)"
    done
    echo
    echo "  }"
    echo "}"
} > "$PKG_DIR/manifest.json"

# 6) 打包（--sort=name + 固定 mtime → 同样的输入产出同样的 tar，便于比对哈希）
info "打包 $OUT_ROOT/$PKG_NAME.tar.zst"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='@0' \
    -C "$STAGE" -cf - "$PKG_NAME" \
    | zstd -3 -T0 -q -o "$OUT_ROOT/$PKG_NAME.tar.zst" -f

( cd "$OUT_ROOT" && sha256sum "$PKG_NAME.tar.zst" | tee "$PKG_NAME.tar.zst.sha256" )

echo
info "完成"
ls -la "$OUT_ROOT/$PKG_NAME.tar.zst"
echo
echo "上传（需要 gh 已登录）："
echo "  gh release create skia-mingw-$SKIA_TAG \\"
echo "      $OUT_ROOT/$PKG_NAME.tar.zst \\"
echo "      --title 'mingw-Skia prebuilt $SKIA_TAG' \\"
echo "      --notes 'GNU ABI (MinGW-w64/UCRT) Skia 静态库集合，供 ComposeKN 的 mingwX64 链接使用。'"
