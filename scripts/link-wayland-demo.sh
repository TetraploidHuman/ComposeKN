#!/usr/bin/env bash
# Reliable link for wayland-demo on NixOS (konanc JVM crash workaround + native bridge rebuild).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if command -v nix-shell >/dev/null 2>&1 && [[ -f "$ROOT/shell.nix" ]]; then
  exec nix-shell "$ROOT/shell.nix" --run "
    set -euo pipefail
    cd '$ROOT'
    if ! java -version 2>&1 | grep -qiE 'temurin|zulu|Eclipse Adoptium'; then
      echo '==> JDK is not Zulu/Temurin; installing to /tmp/temurin21'
      bash '$ROOT/scripts/bootstrap-temurin.sh'
      export JAVA_HOME=/tmp/temurin21
      export COMPOSEKN_JAVA_HOME=/tmp/temurin21
      export PATH=/tmp/temurin21/bin:\$PATH
    fi
    echo \"==> Using JAVA_HOME=\$JAVA_HOME\"
    java -version
    echo '==> Rebuild build-logic + Skiko native bridges (Wayland/xkbcommon)'
    ./gradlew :build-logic:compileKotlin :skiko:compileNativeBridgesLinuxX64 :skiko:linkNativeBridgesLinuxX64 --rerun-tasks --no-daemon -Pkotlin.native.disableCompilerDaemon=true
    echo '==> Link wayland-demo (Stable = isolated subprocess, tolerates konanc JVM crash)'
    ./gradlew :samples:wayland-demo:linkReleaseExecutableLinuxX64Stable --no-daemon -Pkotlin.native.disableCompilerDaemon=true
    ls -la samples/wayland-demo/build/bin/linuxX64/releaseExecutable/
  "
fi

echo "On NixOS, run from project root: ./scripts/link-wayland-demo.sh" >&2
exit 1
