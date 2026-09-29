#!/usr/bin/env bash
# 用 Release maven zip（或已有目录）跑 samples/consumer-smoke 编译冒烟。
#
#   ./scripts/smoke-composekn-consumer.sh ~/composekn-m2
#   COMPOSEKN_VERSION=0.5.66 ./scripts/smoke-composekn-consumer.sh   # 先 fetch 再编
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
M2="${1:-}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die() { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

if [ -z "$M2" ]; then
  M2="$REPO/build/consumer-m2"
  info "Fetch ComposeKN $VER → $M2"
  COMPOSEKN_VERSION="$VER" "$REPO/scripts/fetch-composekn-maven.sh" "$M2"
fi
[ -d "$M2/com/composekn" ] || die "not a maven-repo: $M2 (need com/composekn/…)"

chmod +x "$REPO/scripts/assert-composekn-maven-repo.sh"
"$REPO/scripts/assert-composekn-maven-repo.sh" "$M2"

# 对齐 sample 内版本号
SMOKE="$REPO/samples/consumer-smoke"
sed -i "s/^composekn.version=.*/composekn.version=$VER/" "$SMOKE/gradle.properties"
sed -i "s/^composekn.compose.ui.version=.*/composekn.compose.ui.version=1.12.1-ckn.$VER/" \
  "$SMOKE/gradle.properties"

info "Compile consumer-smoke against $M2"
cd "$SMOKE"
"$REPO/gradlew" --no-daemon \
  -Pcomposekn.maven.local="$M2" \
  -Pcomposekn.version="$VER" \
  -Pcomposekn.compose.ui.version="1.12.1-ckn.$VER" \
  -Pcomposekn.settings.skipGithubPackages=true \
  compileKotlinLinuxX64

info "consumer-smoke compileKotlinLinuxX64 OK"
