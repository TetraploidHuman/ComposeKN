#!/usr/bin/env bash
# 发布 Gradle 插件（build-logic）+ version catalog → 本地 maven-repo / GitHub Packages。
#
#   GITHUB_TOKEN=… ./scripts/publish-composekn-plugins.sh
#   COMPOSEKN_PLUGINS_LOCAL_ONLY=1 ./scripts/publish-composekn-plugins.sh
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CKN_VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
LOCAL_ONLY="${COMPOSEKN_PLUGINS_LOCAL_ONLY:-0}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
die() { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

GH_ARGS=()
TASKS_LOGIC=(
  publishAllPublicationsToComposeKnLocalRepository
)
TASKS_CATALOG=(
  :compose-kn-catalog:publishMavenPublicationToComposeKnLocalRepository
)
if [ "$LOCAL_ONLY" != 1 ]; then
  : "${GITHUB_TOKEN:?GITHUB_TOKEN required (or COMPOSEKN_PLUGINS_LOCAL_ONLY=1)}"
  GH_ARGS+=( -Pcomposekn.publish.github=true )
  TASKS_LOGIC+=( publishAllPublicationsToGitHubPackagesRepository )
  TASKS_CATALOG+=( :compose-kn-catalog:publishMavenPublicationToGitHubPackagesRepository )
fi

info "Publish build-logic plugins @ $CKN_VER"
cd "$REPO"
./gradlew -p build-logic --no-daemon \
  -Pcomposekn.version="$CKN_VER" \
  "${GH_ARGS[@]}" \
  "${TASKS_LOGIC[@]}"

info "Publish composekn-catalog @ $CKN_VER"
./gradlew --no-daemon \
  -Pcomposekn.version="$CKN_VER" \
  "${GH_ARGS[@]}" \
  "${TASKS_CATALOG[@]}"

# 插件 marker：com.composekn.settings.gradle.plugin
MARKER="$REPO/build/maven-repo/com/composekn/settings/com.composekn.settings.gradle.plugin/$CKN_VER"
[ -d "$MARKER" ] || die "missing settings plugin marker at $MARKER"

CATALOG_DIR="$REPO/build/maven-repo/com/composekn/composekn-catalog/$CKN_VER"
[ -d "$CATALOG_DIR" ] || die "missing catalog at $CATALOG_DIR"

info "done — plugins + catalog $CKN_VER"
ls -la "$MARKER" | head -10
ls -la "$CATALOG_DIR" | head -10
