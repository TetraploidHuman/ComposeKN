#!/usr/bin/env bash
# Copy libcrypt.so.1 next to a .kexe ($ORIGIN/lib) so it runs on NixOS without LD_LIBRARY_PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEXE="${1:-$ROOT/samples/wayland-demo/build/bin/linuxX64/releaseExecutable/wayland-demo.kexe}"

if [[ ! -f "$KEXE" ]]; then
  echo "Binary not found: $KEXE" >&2
  exit 1
fi

LIBDIR="$(dirname "$KEXE")/lib"
mkdir -p "$LIBDIR"

if command -v nix-shell >/dev/null 2>&1 && [[ -f "$ROOT/shell.nix" ]]; then
  nix-shell "$ROOT/shell.nix" --run "cp -L \"\$COMPOSEKN_LIBCRYPT_LIB/libcrypt.so.1\" \"$LIBDIR/\""
else
  echo "Need nix-shell + shell.nix (COMPOSEKN_LIBCRYPT_LIB)." >&2
  exit 1
fi

echo "Bundled $LIBDIR/libcrypt.so.1 — try: $KEXE"
