#!/usr/bin/env bash
# Run a ComposeKN linuxX64 .kexe with Wayland/EGL/GL shared libraries (NixOS-safe).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEXE="${1:-$ROOT/samples/wayland-demo/build/bin/linuxX64/releaseExecutable/wayland-demo.kexe}"
shift || true

if [[ ! -f "$KEXE" ]]; then
  echo "Binary not found: $KEXE" >&2
  echo "Build first: nix-shell ./shell.nix --run './gradlew :samples:wayland-demo:linkReleaseExecutableLinuxX64Stable'" >&2
  exit 1
fi

if command -v nix-shell >/dev/null 2>&1 && [[ -f "$ROOT/shell.nix" ]]; then
  exec nix-shell "$ROOT/shell.nix" --run "
    export XDG_RUNTIME_DIR=\${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
    export WAYLAND_DISPLAY=\${WAYLAND_DISPLAY:-wayland-0}
    exec \"$KEXE\" \"\$@\"
  " -- "$@"
fi

echo "On NixOS, use: ./scripts/run-linux-native.sh" >&2
echo "Or set LD_LIBRARY_PATH to include libxcrypt.so.1 (see shell.nix)." >&2
exit 1
