#!/usr/bin/env bash
# 一键发布 compose-kn-* 到本地 maven-repo（可选 GitHub Packages）。
#
# 注意：umbrella `publish` 会触发 skiko/compose-core 的 common metadata 编译
# （@OptionalExpectation 在 KN 2.4 下会炸）。因此只发：
#   - BOM
#   - 各模块的 linuxX64 / mingwX64 publication（+ 尽量发 kotlinMultiplatform，失败则跳过）
#
#   ./scripts/publish-composekn-packages.sh
#   GITHUB_TOKEN=… ./scripts/publish-composekn-packages.sh --github
#   ./scripts/publish-composekn-packages.sh --compose-ui   # 另开 compose-core 工程
#   ./scripts/publish-composekn-packages.sh --skiko
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
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
  esac
done

ARGS=( --no-daemon )
if [ "$DO_GITHUB" = 1 ]; then
  : "${GITHUB_TOKEN:?set GITHUB_TOKEN}"
  ARGS+=(-Pcomposekn.publish.github=true)
fi

# 跳过会炸的 metadata 任务（仍能发 native klib）
EXCLUDE=(
  -x ':*:compileCommonMainKotlinMetadata'
  -x ':*:compileNativeMainKotlinMetadata'
  -x ':*:compileNativeJsMainKotlinMetadata'
  -x ':*:compileSkikoMainKotlinMetadata'
  -x ':*:compileNonJvmMainKotlinMetadata'
)

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

info "Publish BOM"
./gradlew "${ARGS[@]}" :compose-kn-bom:publish

info "Publish linuxX64 / mingwX64 host + resources klibs"
./gradlew "${ARGS[@]}" "${EXCLUDE[@]}" \
  :compose-kn-resources:publishLinuxX64PublicationToComposeKnLocalRepository \
  :compose-kn-resources:publishMingwX64PublicationToComposeKnLocalRepository \
  :compose-kn-linux:publishLinuxX64PublicationToComposeKnLocalRepository \
  :compose-kn-windows:publishMingwX64PublicationToComposeKnLocalRepository \
  || true

# 根坐标（kotlinMultiplatform）——若 metadata 仍炸则仅保留 *-linuxx64 / *-mingwx64
info "Try root kotlinMultiplatform publications (best-effort)"
./gradlew "${ARGS[@]}" "${EXCLUDE[@]}" \
  :compose-kn-resources:publishKotlinMultiplatformPublicationToComposeKnLocalRepository \
  :compose-kn-linux:publishKotlinMultiplatformPublicationToComposeKnLocalRepository \
  :compose-kn-windows:publishKotlinMultiplatformPublicationToComposeKnLocalRepository \
  || info "root KMP publication skipped (use platform artifacts compose-kn-*-linuxx64 / mingwx64)"

if [ "$DO_GITHUB" = 1 ]; then
  info "Mirror same tasks to GitHubPackages"
  ./gradlew "${ARGS[@]}" "${EXCLUDE[@]}" \
    :compose-kn-bom:publishMavenPublicationToGitHubPackagesRepository \
    :compose-kn-resources:publishLinuxX64PublicationToGitHubPackagesRepository \
    :compose-kn-resources:publishMingwX64PublicationToGitHubPackagesRepository \
    :compose-kn-linux:publishLinuxX64PublicationToGitHubPackagesRepository \
    :compose-kn-windows:publishMingwX64PublicationToGitHubPackagesRepository \
    || true
fi

if [ "$DO_UI" = 1 ]; then
  CKN_VER="$(grep '^composekn.version=' gradle.properties | cut -d= -f2)"
  info "Publish com.composekn.compose:* ($CKN_VER) — best-effort per-target"
  ./gradlew -p vendor/compose-core \
    -Pcomposekn.publish.composeUi=true \
    -Pcomposekn.version="$CKN_VER" \
    "${EXCLUDE[@]}" \
    publishAllPublicationsToComposeKnLocalRepository \
    --continue --no-daemon || true
fi

if [ "$DO_SKIKO" = 1 ]; then
  info "Publish com.composekn:skiko"
  "$REPO/scripts/publish-skiko-composekn.sh" || true
fi

info "Local repo: $REPO/build/maven-repo"
find build/maven-repo/com/composekn -name '*.klib' 2>/dev/null | head -20 || true
