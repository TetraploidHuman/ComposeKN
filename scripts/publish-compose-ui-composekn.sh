#!/usr/bin/env bash
# 发布 com.composekn.compose:*（linuxX64 + mingwX64），依赖已发布的 com.composekn:skiko。
# 平台 publication 之后合成根坐标并（有真 token 时）上传到 GitHub Packages。
#
#   GITHUB_TOKEN=… ./scripts/publish-compose-ui-composekn.sh
#   COMPOSEKN_UI_LOCAL_ONLY=1 GITHUB_TOKEN=local-only ./scripts/publish-compose-ui-composekn.sh
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CKN_VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
UI_VER="$(grep '^composekn.compose.ui.version=' "$REPO/gradle.properties" | cut -d= -f2)"
UI_VER="${UI_VER:-1.12.1-ckn.$CKN_VER}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN required}"
: "${GITHUB_ACTOR:=github}"
LOCAL_ONLY="${COMPOSEKN_UI_LOCAL_ONLY:-0}"
if [ "$GITHUB_TOKEN" = "local-only" ]; then
  LOCAL_ONLY=1
fi

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
  )
  if [ "$LOCAL_ONLY" != 1 ]; then
    TASKS+=(
      ":$m:publishLinuxX64PublicationToGitHubPackagesRepository"
      ":$m:publishMingwX64PublicationToGitHubPackagesRepository"
    )
  fi
done

info "Publish com.composekn.compose:* @$UI_VER (platform; use published skiko)"
cd "$REPO"
./gradlew -p vendor/compose-core \
  --init-script "$INIT" \
  --no-daemon \
  -Pcomposekn.compose.standalone=true \
  -Pcomposekn.usePublishedSkiko=true \
  -Pcomposekn.publish.composeUi=true \
  -Pcomposekn.version="$CKN_VER" \
  -Pcomposekn.compose.ui.version="$UI_VER" \
  -Pcomposekn.publish.skipMetadata=true \
  "${TASKS[@]}"

info "Synthesize com.composekn.compose:* roots"
chmod +x "$REPO/scripts/synthesize-kmp-root-modules.sh"
"$REPO/scripts/synthesize-kmp-root-modules.sh" "$REPO/build/maven-repo"

for m in "${MODULES[@]}"; do
  root="$REPO/build/maven-repo/com/composekn/compose/$m/$UI_VER"
  [ -f "$root/$m-$UI_VER.module" ] \
    || die "missing synthesized $root/$m-$UI_VER.module"
done

if [ "$LOCAL_ONLY" != 1 ]; then
  info "Upload synthesized UI roots → GitHub Packages"
  chmod +x "$REPO/scripts/upload-maven-artifacts-to-gh-packages.sh"
  for m in "${MODULES[@]}"; do
    "$REPO/scripts/upload-maven-artifacts-to-gh-packages.sh" \
      "$REPO/build/maven-repo/com/composekn/compose/$m/$UI_VER"
    if [ -f "$REPO/build/maven-repo/com/composekn/compose/$m/maven-metadata.xml" ]; then
      "$REPO/scripts/upload-maven-artifacts-to-gh-packages.sh" \
        "$REPO/build/maven-repo/com/composekn/compose/$m/maven-metadata.xml"
    fi
  done
fi

info "done"
find "$REPO/build/maven-repo/com/composekn/compose" -name '*.klib' 2>/dev/null | sort | head -40 || true
