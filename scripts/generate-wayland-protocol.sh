#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROTO_DIR="$ROOT/vendor/protocols"
OUT_DIR="$ROOT/vendor/skiko/skiko/src/linuxMain/cpp/wayland"

generate() {
  local xml="$1"
  local base="$2"
  wayland-scanner client-header "$PROTO_DIR/$xml" "$OUT_DIR/${base}-client-protocol.h"
  wayland-scanner private-code "$PROTO_DIR/$xml" "$OUT_DIR/${base}-protocol.c"
}

if command -v wayland-scanner >/dev/null; then
  SCANNER=wayland-scanner
else
  SCANNER="nix-shell -p wayland-scanner --run wayland-scanner"
fi

$SCANNER client-header "$PROTO_DIR/xdg-shell.xml" "$OUT_DIR/xdg-shell-client-protocol.h"
$SCANNER private-code "$PROTO_DIR/xdg-shell.xml" "$OUT_DIR/xdg-shell-protocol.c"
generate fractional-scale-v1.xml fractional-scale-v1
generate xdg-decoration-unstable-v1.xml xdg-decoration-unstable-v1
generate text-input-unstable-v3.xml text-input-unstable-v3

echo "Generated Wayland protocols in $OUT_DIR"
