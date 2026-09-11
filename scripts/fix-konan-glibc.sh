#!/usr/bin/env bash
# fix-konan-glibc.sh — 把 konanc 的 linux_x64 工具链 sysroot glibc 换成 NixOS 系统 glibc。
#
# 为什么需要：
#   Kotlin/Native 2.4.0 的 linux_x64 工具链（konan.properties 的
#   toolchainDependency.linux_x64 = ...-glibc-2.19-...）捆绑 glibc 2.19，
#   链接 demo 时缺 stat64@GLIBC_2.33 / __isoc23_strtol@GLIBC_2.38 等较新符号，
#   而 skiko 原生代码按宿主 glibc（NixOS 上为 2.42）头文件编译、引用了这些符号，
#   于是 ld.lld 报 undefined reference。
#
# 修法：
#   glibc 向后兼容——用一个较新的 libc 同时满足“旧代码引用旧符号 + 新代码引用新符号”。
#   把 konan 工具链 sysroot 里整套 glibc 的 .so 内容替换为 NixOS 系统 glibc。
#   必须整套换（libc/libm/libpthread/libdl/librt/...），因为 glibc 2.34+ 已把
#   libpthread/librt/libdl 合并进 libc（变成仅 NEEDED libc 的薄 stub）；只换 libc
#   会在 --no-allow-shlib-undefined 下触发 2.19 libpthread/librt 引用的
#   __*@GLIBC_PRIVATE 跨版本不匹配。
#
# 幂等：若 sysroot 的 libc 已是目标 glibc 版本则直接跳过。
# 可复现：这是“运行时环境”的一次性修复（对 ~/.konan 缓存的原地修改）。
#   若 konan 工具链被重新下载/重置，重跑本脚本即可。
#
# 用法：
#   scripts/fix-konan-glibc.sh                 # 自动找 NixOS 系统 glibc
#   scripts/fix-konan-glibc.sh /path/to/glibc  # 指定 glibc（其 lib/ 需含 libc.so.6）
set -euo pipefail

# ---- 1. 定位 konanc prebuilt 与 linux_x64 工具链 sysroot -------------------
KONAN_HOME="${KONAN_HOME:-$HOME/.konan}"
PREBUILT="$(ls -d "$KONAN_HOME"/kotlin-native-prebuilt-linux-x86_64-* 2>/dev/null | sort | tail -1 || true)"
if [[ -z "$PREBUILT" || ! -f "$PREBUILT/konan/konan.properties" ]]; then
  echo "!! 找不到 konanc prebuilt：$PREBUILT" >&2; exit 1
fi

TC_NAME="$(grep -E '^toolchainDependency\.linux_x64[[:space:]]*=' "$PREBUILT/konan/konan.properties" \
           | sed -E 's/.*=[[:space:]]*//')"
if [[ -z "$TC_NAME" ]]; then
  echo "!! konan.properties 里没有 toolchainDependency.linux_x64" >&2; exit 1
fi
SYSROOT_LIB="$KONAN_HOME/dependencies/$TC_NAME/x86_64-unknown-linux-gnu/sysroot/lib"
if [[ ! -d "$SYSROOT_LIB" ]]; then
  echo "!! 找不到工具链 sysroot：$SYSROOT_LIB（工具链可能尚未下载）" >&2; exit 1
fi
echo "==> konan 工具链: $TC_NAME"
echo "==> sysroot/lib:  $SYSROOT_LIB"

# ---- 2. 确定目标 glibc ------------------------------------------------------
GLIBC_SRC="${1:-}"
if [[ -z "$GLIBC_SRC" ]]; then
  # 自动挑一个含 libc.so.6 的 NixOS glibc，优先取系统当前正在用的那一版
  GLIBC_SRC="$(ldd --version >/dev/null 2>&1; \
    for p in /nix/store/*/lib/libc.so.6; do
      [ -e "$p" ] && { echo "$(dirname "$(dirname "$p")")"; }
    done | sort -u | tail -1 || true)"
fi
if [[ -z "$GLIBC_SRC" || ! -f "$GLIBC_SRC/lib/libc.so.6" ]]; then
  echo "!! 找不到目标 glibc（libc.so.6）。用法: $0 /path/to/glibc" >&2; exit 1
fi
GLIBC_SRC_LIB="$GLIBC_SRC/lib"
echo "==> 目标 glibc: $GLIBC_SRC_LIB"

# ---- 3. 幂等检查：sysroot 的 libc 版本是否已达目标 glibc 版本 ---------------
# 用版本号比较（而非字节）：同一 glibc 版本的不同 store 构建（-61/-84）字节不同
# 但行为一致，按版本判等即可稳定幂等。
cur_ver="$(readelf -V "$SYSROOT_LIB/libc.so.6" 2>/dev/null | grep -oE 'GLIBC_2\.[0-9]+' | sort -uV | tail -1 || true)"
tgt_ver="$(readelf -V "$GLIBC_SRC_LIB/libc.so.6" 2>/dev/null | grep -oE 'GLIBC_2\.[0-9]+' | sort -uV | tail -1 || true)"
if [[ -n "$cur_ver" && "$cur_ver" == "$tgt_ver" ]]; then
  echo "==> sysroot glibc 已是 $cur_ver（= 目标 $tgt_ver），无需修改（幂等跳过）"
  exit 0
fi
echo "==> sysroot glibc 当前 $cur_ver -> 目标 $tgt_ver"

# ---- 4. 备份 + 整套替换 -----------------------------------------------------
BACKUP="$SYSROOT_LIB/.glibc-backup-$(date -u +%Y%m%d%H%M%S)"
mkdir -p "$BACKUP"

# sysroot 里的 2.19 真实文件名 <- NixOS glibc 的对应 soname 文件
declare -A M=(
  ["libc-2.19.so"]="libc.so.6"
  ["libm-2.19.so"]="libm.so.6"
  ["libpthread-2.19.so"]="libpthread.so.0"
  ["libdl-2.19.so"]="libdl.so.2"
  ["librt-2.19.so"]="librt.so.1"
  ["libutil-2.19.so"]="libutil.so.1"
  ["libnsl-2.19.so"]="libnsl.so.1"
  ["libresolv-2.19.so"]="libresolv.so.2"
  ["libanl-2.19.so"]="libanl.so.1"
  ["libBrokenLocale-2.19.so"]="libBrokenLocale.so.1"
  ["ld-2.19.so"]="ld-linux-x86-64.so.2"
  ["libthread_db-1.0.so"]="libthread_db.so.1"
  ["libnss_compat-2.19.so"]="libnss_compat.so.2"
  ["libnss_db-2.19.so"]="libnss_db.so.2"
  ["libnss_dns-2.19.so"]="libnss_dns.so.2"
  ["libnss_files-2.19.so"]="libnss_files.so.2"
  ["libnss_hesiod-2.19.so"]="libnss_hesiod.so.2"
)
n=0
for dst in "${!M[@]}"; do
  src="$GLIBC_SRC_LIB/${M[$dst]}"
  if [[ -e "$src" && -e "$SYSROOT_LIB/$dst" ]]; then
    cp -f "$SYSROOT_LIB/$dst" "$BACKUP/$dst"     # 备份原始 2.19 内容（真实拷贝）
    cp -f "$src" "$SYSROOT_LIB/$dst"
    n=$((n+1))
  fi
done
echo "==> 已替换 $n 个 glibc 库；原始文件备份在: $BACKUP"
echo "==> 完成。现在可正常链接 wayland-demo。"
