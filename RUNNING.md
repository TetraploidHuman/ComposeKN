# Running on NixOS

Kotlin/Native binaries need matching system libraries. **Do not run `.kexe` directly** unless you have bundled libs and the right GL stack.

## Quick start

```bash
# Build (inside project shell — sets COMPOSEKN_LIBCRYPT_LIB, Mesa, llvmpipe)
nix-shell ./shell.nix --run './scripts/link-wayland-demo.sh'

# Or manually:
# nix-shell ./shell.nix --run './gradlew :samples:wayland-demo:linkReleaseExecutableLinuxX64Stable'

# Run (Wayland session required)
./scripts/run-linux-native.sh
```

## Common errors

| Symptom | Fix |
|---------|-----|
| `libcrypt.so.1: cannot open shared object file` | `./scripts/bundle-libcrypt.sh` then run again, or use `./scripts/run-linux-native.sh` |
| `Failed to create Skia OpenGL context` / `Cannot init graphic context` | Use `./scripts/run-linux-native.sh` (sets `LIBGL_ALWAYS_SOFTWARE=1`, matched Mesa/libgbm). Rebuild after Skiko changes. |
| `Native link did not produce ... wayland-demo.kexe` | Run `./scripts/link-wayland-demo.sh` (bootstraps Temurin if needed). Check `build/composekn-link-logs/`. If still failing: `./scripts/bootstrap-temurin.sh` then retry. |
| Window never appears | Check `echo $WAYLAND_DISPLAY` and `echo $XDG_RUNTIME_DIR` |

## Direct `.kexe` (advanced)

Only after bundling libcrypt:

```bash
./scripts/bundle-libcrypt.sh
export LD_LIBRARY_PATH="$(nix-build ./shell.nix --no-out-link 2>/dev/null || true):$LD_LIBRARY_PATH"
# Prefer the run script instead — it sets Mesa/llvmpipe correctly.
```
