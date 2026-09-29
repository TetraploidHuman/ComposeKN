#!/usr/bin/env bash
# 校验 maven-repo 是否具备消费所需的最小集合（合成根 + 关键平台 klib）。
#
#   ./scripts/assert-composekn-maven-repo.sh [maven-repo-root]
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2="${1:-$REPO/build/maven-repo}"
VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
UI_VER="$(grep '^composekn.compose.ui.version=' "$REPO/gradle.properties" | cut -d= -f2)"
UI_VER="${UI_VER:-1.12.1-ckn.$VER}"

die() { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

[ -d "$M2/com/composekn" ] || die "missing $M2/com/composekn"

need_dir() { [ -d "$1" ] || die "missing dir: $1"; }
need_file() { [ -f "$1" ] || die "missing file: $1"; }

# hosts + resources
need_dir "$M2/com/composekn/compose-kn-linux/$VER"
need_file "$M2/com/composekn/compose-kn-linux/$VER/compose-kn-linux-$VER.module"
need_file "$M2/com/composekn/compose-kn-linux-linuxx64/$VER/compose-kn-linux-linuxx64-$VER.klib"
need_dir "$M2/com/composekn/compose-kn-windows/$VER"
need_file "$M2/com/composekn/compose-kn-windows-mingwx64/$VER/compose-kn-windows-mingwx64-$VER.klib"
need_dir "$M2/com/composekn/compose-kn-resources/$VER"
need_file "$M2/com/composekn/compose-kn-resources-linuxx64/$VER/compose-kn-resources-linuxx64-$VER.klib"
need_file "$M2/com/composekn/compose-kn-bom/$VER/compose-kn-bom-$VER.pom"

# skiko
need_dir "$M2/com/composekn/skiko/$VER"
need_file "$M2/com/composekn/skiko/$VER/skiko-$VER.module"
need_file "$M2/com/composekn/skiko-linuxx64/$VER/skiko-linuxx64-$VER.klib"
need_file "$M2/com/composekn/skiko-mingwx64/$VER/skiko-mingwx64-$VER.klib"

# UI 抽样
need_dir "$M2/com/composekn/compose/ui/$UI_VER"
need_file "$M2/com/composekn/compose/ui/$UI_VER/ui-$UI_VER.module"
need_file "$M2/com/composekn/compose/ui-linuxx64/$UI_VER/ui-linuxx64-$UI_VER.klib"
need_file "$M2/com/composekn/compose/foundation-linuxx64/$UI_VER/foundation-linuxx64-$UI_VER.klib"
need_file "$M2/com/composekn/compose/material3-linuxx64/$UI_VER/material3-linuxx64-$UI_VER.klib"

# plugins + catalog
need_dir "$M2/com/composekn/composekn-catalog/$VER"
need_file "$M2/com/composekn/composekn-catalog/$VER/composekn-catalog-$VER.toml"
need_dir "$M2/com/composekn/settings/com.composekn.settings.gradle.plugin/$VER"
need_dir "$M2/com/composekn/host/com.composekn.host.gradle.plugin/$VER"
need_file "$M2/com/composekn/composekn-build-logic/$VER/composekn-build-logic-$VER.jar"

KLIB_N="$(find "$M2" -name '*.klib' | wc -l | tr -d ' ')"
# hosts(4) + skiko(2+cinterop?) + UI(~28) ≈ 34+；下限保守取 30
[ "$KLIB_N" -ge 30 ] || die "expected >=30 klibs, got $KLIB_N"
# skiko 主 klib 应明显大于宿主
SKIKO_SZ="$(stat -c%s "$M2/com/composekn/skiko-linuxx64/$VER/skiko-linuxx64-$VER.klib" 2>/dev/null || stat -f%z "$M2/com/composekn/skiko-linuxx64/$VER/skiko-linuxx64-$VER.klib")"
[ "$SKIKO_SZ" -gt 1000000 ] || die "skiko-linuxx64 klib too small: $SKIKO_SZ"

info "OK maven-repo ver=$VER ui=$UI_VER klibs=$KLIB_N skiko_linux=${SKIKO_SZ}B"
