#!/usr/bin/env bash
# 为只发了平台 variant 的 KMP 模块合成根坐标，
# 让 implementation("com.composekn:compose-kn-linux:$VER") /
# implementation("com.composekn.compose:ui:$UI_VER") 能按 native target 解析。
#
# 用法：./scripts/synthesize-kmp-root-modules.sh [maven-repo-root]
#
# 只写 ApiElements（不写 MetadataElements）：发布默认 skipMetadata，
# 虚假 metadata variant 会干扰解析。
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2="${1:-$REPO/build/maven-repo}"
VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
UI_VER="$(grep '^composekn.compose.ui.version=' "$REPO/gradle.properties" | cut -d= -f2)"
UI_VER="${UI_VER:-1.12.1-ckn.$VER}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

# synthesize <group.path> <artifact> <version> <platform...>
# group.path 例：com/composekn 或 com/composekn/compose
synthesize() {
  local group_path="$1"
  local name="$2"
  local version="$3"
  shift 3
  local -a platforms=("$@")
  local group_id="${group_path//\//.}"
  local root="$M2/$group_path/$name/$version"
  mkdir -p "$root"

  local variants=""
  local first=1
  for p in "${platforms[@]}"; do
    local plat_mod="${name}-${p}"
    local plat_dir="$M2/$group_path/$plat_mod/$version"
    if [ ! -d "$plat_dir" ]; then
      info "skip $group_id:$plat_mod:$version (not published)"
      continue
    fi
    local native_target
    case "$p" in
      linuxx64) native_target="linux_x64" ;;
      mingwx64) native_target="mingw_x64" ;;
      *) native_target="$p" ;;
    esac
    local comma=""
    [ "$first" = 1 ] || comma=","
    first=0
    variants+="${comma}
    {
      \"name\": \"${p}ApiElements-published\",
      \"attributes\": {
        \"org.gradle.category\": \"library\",
        \"org.gradle.jvm.environment\": \"non-jvm\",
        \"org.gradle.usage\": \"kotlin-api\",
        \"org.jetbrains.kotlin.platform.type\": \"native\",
        \"org.jetbrains.kotlin.native.target\": \"${native_target}\"
      },
      \"available-at\": {
        \"url\": \"../../${plat_mod}/${version}/${plat_mod}-${version}.module\",
        \"group\": \"${group_id}\",
        \"module\": \"${plat_mod}\",
        \"version\": \"${version}\"
      }
    }"
  done

  if [ "$first" = 1 ]; then
    info "nothing to synthesize for $group_id:$name:$version"
    return 0
  fi

  cat > "$root/$name-$version.module" <<EOF
{
  "formatVersion": "1.1",
  "component": {
    "group": "$group_id",
    "module": "$name",
    "version": "$version",
    "attributes": {
      "org.gradle.status": "release"
    }
  },
  "createdBy": {
    "gradle": { "version": "9.0" }
  },
  "variants": [
$variants
  ]
}
EOF

  cat > "$root/$name-$version.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$group_id</groupId>
  <artifactId>$name</artifactId>
  <version>$version</version>
  <packaging>pom</packaging>
  <name>$name</name>
  <description>ComposeKN KMP root (platform variants via Gradle Module Metadata)</description>
</project>
EOF

  cat > "$M2/$group_path/$name/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>$group_id</groupId>
  <artifactId>$name</artifactId>
  <versioning>
    <latest>$version</latest>
    <release>$version</release>
    <versions><version>$version</version></versions>
    <lastUpdated>$(date -u +%Y%m%d%H%M%S)</lastUpdated>
  </versioning>
</metadata>
EOF

  info "synthesized $group_id:$name:$version → $root"
}

# —— host / skiko（com.composekn）——
synthesize com/composekn compose-kn-linux "$VER" linuxx64
synthesize com/composekn compose-kn-windows "$VER" mingwx64
synthesize com/composekn compose-kn-resources "$VER" linuxx64 mingwx64
synthesize com/composekn skiko "$VER" linuxx64 mingwx64

# —— compose UI（com.composekn.compose，版本 = composekn.compose.ui.version）——
UI_MODULES=(
  ui-util ui-geometry ui-unit ui-graphics ui-text ui-backhandler
  lifecycle-viewmodel-compose ui
  animation-core animation
  foundation-layout foundation
  material-ripple material3
)
for m in "${UI_MODULES[@]}"; do
  synthesize com/composekn/compose "$m" "$UI_VER" linuxx64 mingwx64
done

info "done"
