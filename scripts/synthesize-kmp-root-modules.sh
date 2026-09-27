#!/usr/bin/env bash
# 为只发了平台 variant 的 KMP 模块合成根坐标（com.composekn:compose-kn-linux 等），
# 让 implementation("com.composekn:compose-kn-linux:$VER") 能按 native target 解析。
#
# 用法：./scripts/synthesize-kmp-root-modules.sh [maven-repo-root]
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2="${1:-$REPO/build/maven-repo}"
VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
GROUP_PATH="com/composekn"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

synthesize() {
  local name="$1"          # compose-kn-linux
  shift
  local -a platforms=("$@") # linuxx64 mingwx64
  local root="$M2/$GROUP_PATH/$name/$VER"
  mkdir -p "$root"

  local variants=""
  local first=1
  for p in "${platforms[@]}"; do
    local plat_mod="${name}-${p}"
    local plat_dir="$M2/$GROUP_PATH/$plat_mod/$VER"
    if [ ! -d "$plat_dir" ]; then
      info "skip $plat_mod (not published)"
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
        \"org.gradle.usage\": \"kotlin-api\",
        \"org.jetbrains.kotlin.platform.type\": \"native\",
        \"org.jetbrains.kotlin.native.target\": \"${native_target}\"
      },
      \"available-at\": {
        \"url\": \"../../${plat_mod}/${VER}/${plat_mod}-${VER}.module\",
        \"group\": \"com.composekn\",
        \"module\": \"${plat_mod}\",
        \"version\": \"${VER}\"
      }
    },
    {
      \"name\": \"${p}MetadataElements-published\",
      \"attributes\": {
        \"org.gradle.category\": \"library\",
        \"org.gradle.usage\": \"kotlin-metadata\",
        \"org.jetbrains.kotlin.platform.type\": \"native\",
        \"org.jetbrains.kotlin.native.target\": \"${native_target}\"
      },
      \"available-at\": {
        \"url\": \"../../${plat_mod}/${VER}/${plat_mod}-${VER}.module\",
        \"group\": \"com.composekn\",
        \"module\": \"${plat_mod}\",
        \"version\": \"${VER}\"
      }
    }"
  done

  if [ "$first" = 1 ]; then
    info "nothing to synthesize for $name"
    return 0
  fi

  cat > "$root/$name-$VER.module" <<EOF
{
  "formatVersion": "1.1",
  "component": {
    "group": "com.composekn",
    "module": "$name",
    "version": "$VER",
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

  cat > "$root/$name-$VER.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.composekn</groupId>
  <artifactId>$name</artifactId>
  <version>$VER</version>
  <packaging>pom</packaging>
  <name>$name</name>
  <description>ComposeKN KMP root (platform variants via Gradle Module Metadata)</description>
</project>
EOF

  # maven-metadata.xml
  cat > "$M2/$GROUP_PATH/$name/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.composekn</groupId>
  <artifactId>$name</artifactId>
  <versioning>
    <latest>$VER</latest>
    <release>$VER</release>
    <versions><version>$VER</version></versions>
    <lastUpdated>$(date -u +%Y%m%d%H%M%S)</lastUpdated>
  </versioning>
</metadata>
EOF

  info "synthesized com.composekn:$name:$VER → $root"
}

synthesize compose-kn-linux linuxx64
synthesize compose-kn-windows mingwx64
synthesize compose-kn-resources linuxx64 mingwx64
info "done"
