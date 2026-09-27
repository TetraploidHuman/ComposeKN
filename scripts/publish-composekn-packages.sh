#!/usr/bin/env bash
# 一键：hosts + resources + BOM → 本地 maven-repo，可选 GitHub Packages / compose UI。
#
#   ./scripts/publish-composekn-packages.sh              # 仅本地
#   GITHUB_TOKEN=… ./scripts/publish-composekn-packages.sh --github
#   ./scripts/publish-composekn-packages.sh --compose-ui
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

DO_GITHUB=0
DO_UI=0
DO_SKIKO=0
for a in "$@"; do
  case "$a" in
    --github) DO_GITHUB=1 ;;
    --compose-ui) DO_UI=1 ;;
    --skiko) DO_SKIKO=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
  esac
done

ARGS=( )
if [ "$DO_GITHUB" = 1 ]; then
  : "${GITHUB_TOKEN:?set GITHUB_TOKEN}"
  ARGS+=(-Pcomposekn.publish.github=true)
fi

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

info "Publish compose-kn-bom / resources / linux / windows"
./gradlew "${ARGS[@]}" \
  :compose-kn-bom:publish \
  :compose-kn-resources:publish \
  :compose-kn-linux:publish \
  :compose-kn-windows:publish \
  --no-daemon

if [ "$DO_UI" = 1 ]; then
  CKN_VER="$(grep '^composekn.version=' gradle.properties | cut -d= -f2)"
  info "Publish com.composekn.compose:* ($CKN_VER)"
  ./gradlew -p vendor/compose-core \
    -Pcomposekn.publish.composeUi=true \
    -Pcomposekn.version="$CKN_VER" \
    publish \
    --no-daemon
fi

if [ "$DO_SKIKO" = 1 ]; then
  info "Publish com.composekn:skiko"
  "$REPO/scripts/publish-skiko-composekn.sh"
fi

info "Local repo: $REPO/build/maven-repo"
