#!/usr/bin/env bash
# Build + run wayland-demo --selftest under a headless Wayland compositor.
# Used by .github/workflows/linux-native-selftest.yml and local smoke checks.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BUILD_ONLY=0
SKIP_BUILD=0
RENDER_API="${COMPOSEKN_RENDER_API:-}"
TIMEOUT_SEC="${COMPOSEKN_SELFTEST_TIMEOUT:-90}"

usage() {
  cat <<'EOF'
Usage: scripts/test-linux-native.sh [--build-only] [--skip-build] [--api=vulkan|gl]

  Starts weston (headless), links wayland-demo if needed, runs --selftest.
  Sets COMPOSEKN_SELFTEST_RELAX=1 so missing portal/SNI does not fail CI.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --build-only) BUILD_ONLY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --api=*) RENDER_API="${arg#--api=}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $arg" >&2; usage; exit 2 ;;
  esac
done

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing command: $1" >&2
    exit 1
  }
}

need_cmd java
need_cmd pkg-config

export XDG_RUNTIME_DIR="${COMPOSEKN_XDG_RUNTIME_DIR:-/tmp/composekn-xdg-$UID}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
# 强制独立 socket，避免继承 nix-shell/桌面会话的 WAYLAND_DISPLAY=wayland-0。
export WAYLAND_DISPLAY="${COMPOSEKN_WAYLAND_DISPLAY:-wayland-composekn}"

# lavapipe when present (Ubuntu mesa-vulkan-drivers / Nix mesa)
if [[ -z "${VK_ICD_FILENAMES:-}" ]]; then
  for cand in \
    /usr/share/vulkan/icd.d/lvp_icd.x86_64.json \
    /usr/share/vulkan/icd.d/lvp_icd.json
  do
    if [[ -f "$cand" ]]; then
      export VK_ICD_FILENAMES="$cand"
      export VK_DRIVER_FILES="$cand"
      break
    fi
  done
fi
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"
export COMPOSEKN_SELFTEST_RELAX="${COMPOSEKN_SELFTEST_RELAX:-1}"
export CI="${CI:-}"

if [[ -n "$RENDER_API" ]]; then
  export COMPOSEKN_RENDER_API="$RENDER_API"
fi

KEXE_DEBUG="$ROOT/samples/wayland-demo/build/bin/linuxX64/debugExecutable/wayland-demo.kexe"
KEXE_RELEASE="$ROOT/samples/wayland-demo/build/bin/linuxX64/releaseExecutable/wayland-demo.kexe"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "==> linkDebugExecutableLinuxX64"
  ./gradlew :samples:wayland-demo:linkDebugExecutableLinuxX64 --no-daemon --console=plain
fi

KEXE=""
if [[ -x "$KEXE_DEBUG" ]]; then
  KEXE="$KEXE_DEBUG"
elif [[ -x "$KEXE_RELEASE" ]]; then
  KEXE="$KEXE_RELEASE"
else
  echo "wayland-demo.kexe not found (build first)" >&2
  exit 1
fi

if [[ "$BUILD_ONLY" -eq 1 ]]; then
  echo "Built: $KEXE"
  exit 0
fi

need_cmd weston
need_cmd timeout

WESTON_LOG="$XDG_RUNTIME_DIR/weston.log"
echo "==> start weston headless (WAYLAND_DISPLAY=$WAYLAND_DISPLAY)"
# Clean stale socket from a previous run.
rm -f "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY.lock" || true

weston --backend=headless --renderer=pixman \
  --socket="$WAYLAND_DISPLAY" --idle-time=0 \
  --width=1280 --height=800 --refresh-rate=60000 \
  >"$WESTON_LOG" 2>&1 &
WESTON_PID=$!
cleanup() {
  kill "$WESTON_PID" 2>/dev/null || true
  wait "$WESTON_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Wait until the socket appears.
for _ in $(seq 1 50); do
  if [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]; then
    break
  fi
  if ! kill -0 "$WESTON_PID" 2>/dev/null; then
    echo "weston exited early; log:" >&2
    cat "$WESTON_LOG" >&2 || true
    exit 1
  fi
  sleep 0.1
done
if [[ ! -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]; then
  echo "weston socket not ready; log:" >&2
  cat "$WESTON_LOG" >&2 || true
  exit 1
fi

echo "==> selftest ($KEXE) RENDER_API=${COMPOSEKN_RENDER_API:-default} ICD=${VK_ICD_FILENAMES:-unset}"
set +e
timeout "$TIMEOUT_SEC" "$KEXE" --selftest
RC=$?
set -e
if [[ "$RC" -ne 0 ]]; then
  echo "selftest failed rc=$RC; weston log:" >&2
  tail -80 "$WESTON_LOG" >&2 || true
  exit "$RC"
fi
echo "==> selftest OK"
