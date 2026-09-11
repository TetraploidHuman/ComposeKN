#!/usr/bin/env bash
# Install Eclipse Temurin JDK 21 to /tmp/temurin21 (konanc is unstable on NixOS OpenJDK).
set -euo pipefail

DEST=/tmp/temurin21
if [[ -x "$DEST/bin/java" ]]; then
  echo "Temurin already installed: $DEST"
  "$DEST/bin/java" -version
  exit 0
fi

if command -v nix-build >/dev/null 2>&1; then
  echo "Installing JDK via nixpkgs..."
  JDK=$(
    nix-build '<nixpkgs>' -A temurin.packages.jdk-21 --no-out-link 2>/dev/null ||
      nix-build '<nixpkgs>' -A zulu21 --no-out-link 2>/dev/null ||
      nix-build '<nixpkgs>' -A temurin-bin-21-jdk --no-out-link
  )
  ln -sfn "$JDK" "$DEST"
  echo "Linked $DEST -> $JDK"
  "$DEST/bin/java" -version
  exit 0
fi

ARCH=$(uname -m)
case "$ARCH" in
  x86_64) ADOPTIUM_ARCH=x64 ;;
  aarch64) ADOPTIUM_ARCH=aarch64 ;;
  *) echo "Unsupported arch: $ARCH" >&2; exit 1 ;;
esac

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Downloading Temurin JDK 21 ($ADOPTIUM_ARCH) from Adoptium..."
JSON=$(curl -fsSL --max-time 120 "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=${ADOPTIUM_ARCH}&image_type=jdk&os=linux")
URL=$(printf '%s' "$JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["link"])')
SHA=$(printf '%s' "$JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["checksum"])')
FILE="$TMP/temurin.tar.gz"
curl -fL --max-time 600 "$URL" -o "$FILE"
echo "$SHA  $FILE" | sha256sum -c -

mkdir -p "$TMP/extract"
tar -xzf "$FILE" -C "$TMP/extract"
EXTRACTED=$(find "$TMP/extract" -maxdepth 1 -type d -name 'jdk-*' | head -1)
if [[ -z "$EXTRACTED" ]]; then
  echo "Could not find extracted jdk directory" >&2
  exit 1
fi

rm -rf "$DEST"
mv "$EXTRACTED" "$DEST"
echo "Installed Temurin to $DEST"
"$DEST/bin/java" -version
