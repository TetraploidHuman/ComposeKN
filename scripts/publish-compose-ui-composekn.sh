#!/usr/bin/env bash
# 发布 com.composekn.compose:*（linuxX64 + mingwX64），依赖已发布的 com.composekn:skiko。
#
# 必须在 nix-shell 内（mingw bridges 若仍触发编译），且：
#   GITHUB_TOKEN、SKIA_MINGW_PREBUILT（可选，仅当还要编 skiko 时）
#
#   nix-shell ./shell.nix -I nixpkgs=$NIXPKGS_URL --keep GITHUB_TOKEN --keep … \
#     --run ./scripts/publish-compose-ui-composekn.sh
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CKN_VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
: "${GITHUB_TOKEN:?GITHUB_TOKEN required}"
: "${GITHUB_ACTOR:=github}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die() { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

INIT="$REPO/scripts/disable-kotlin-metadata.init.gradle"
[ -f "$INIT" ] || die "missing $INIT"

MODULES=(
  ui-util ui-geometry ui-unit ui-graphics ui-text ui-backhandler
  lifecycle-viewmodel-compose ui
  animation-core animation
  foundation-layout foundation
  material-ripple material3
)

TASKS=()
for m in "${MODULES[@]}"; do
  TASKS+=(
    ":$m:publishLinuxX64PublicationToComposeKnLocalRepository"
    ":$m:publishMingwX64PublicationToComposeKnLocalRepository"
    ":$m:publishLinuxX64PublicationToGitHubPackagesRepository"
    ":$m:publishMingwX64PublicationToGitHubPackagesRepository"
  )
done

info "Publish com.composekn.compose:* @$CKN_VER (platform only; use published skiko)"
cd "$REPO"
./gradlew -p vendor/compose-core \
  --init-script "$INIT" \
  --no-daemon \
  -Pcomposekn.compose.standalone=true \
  -Pcomposekn.usePublishedSkiko=true \
  -Pcomposekn.publish.composeUi=true \
  -Pcomposekn.version="$CKN_VER" \
  -Pcomposekn.publish.skipMetadata=true \
  "${TASKS[@]}"

info "done"
find "$REPO/build/maven-repo/com/composekn/compose" -name '*.klib' 2>/dev/null | sort | head -40 || true
