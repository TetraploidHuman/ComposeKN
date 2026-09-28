#!/usr/bin/env bash
# 把本地 maven-repo 下某个文件或目录 curl PUT 到 GitHub Packages。
#
#   ./scripts/upload-maven-artifacts-to-gh-packages.sh build/maven-repo/com/composekn/skiko/0.5.65
#   ./scripts/upload-maven-artifacts-to-gh-packages.sh build/maven-repo/com/composekn/skiko/maven-metadata.xml
#
# 路径须位于 build/maven-repo/ 下。需要 GITHUB_TOKEN；409 = 已存在（OK）。
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${GITHUB_TOKEN:?GITHUB_TOKEN required}"
OWNER="${GITHUB_REPOSITORY_OWNER:-TetraploidHuman}"
GH_REPO_NAME="${GITHUB_REPOSITORY##*/}"
GH_REPO_NAME="${GH_REPO_NAME:-ComposeKN}"
M2_ROOT="$REPO/build/maven-repo"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
warn() { printf '\033[33m!!\033[0m %s\n' "$1"; }
die() { printf '\033[31m!!\033[0m %s\n' "$1" >&2; exit 1; }

TARGET="${1:?usage: $0 <maven-repo-file-or-dir>}"
if [[ "$TARGET" != /* ]]; then
  TARGET="$REPO/$TARGET"
fi
# 解析到真实路径（文件则取其目录再拼回）
if [ -f "$TARGET" ]; then
  TARGET_FILE="$TARGET"
  TARGET_DIR="$(cd "$(dirname "$TARGET")" && pwd)"
  TARGET="$TARGET_DIR/$(basename "$TARGET_FILE")"
elif [ -d "$TARGET" ]; then
  TARGET="$(cd "$TARGET" && pwd)"
  TARGET_FILE=""
else
  die "not found: $TARGET"
fi

case "$TARGET" in
  "$M2_ROOT"|"$M2_ROOT"/*) ;;
  *) die "path must be under $M2_ROOT (got $TARGET)" ;;
esac

REL_CHECK="${TARGET#"$M2_ROOT"/}"
[ -n "$REL_CHECK" ] || die "refusing to upload entire maven-repo root"

BASE="https://maven.pkg.github.com/$OWNER/$GH_REPO_NAME"
uploaded=0
failed=0

upload_one() {
  local f="$1"
  local rel="${f#"$M2_ROOT"/}"
  local bn
  bn="$(basename "$f")"
  case "$bn" in
    *.md5|*.sha1|*.sha256|*.sha512) return 0 ;;
  esac
  local url="$BASE/$rel"
  local code
  code="$(curl -sS -o /tmp/gh-pkg-up.out -w '%{http_code}' \
    -X PUT \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" \
    "$url" || true)"
  case "$code" in
    200|201|204)
      info "  $rel → $code"
      uploaded=$((uploaded + 1))
      ;;
    409)
      warn "  $rel → 409 (exists)"
      uploaded=$((uploaded + 1))
      ;;
    *)
      warn "  $rel → HTTP $code $(head -c 160 /tmp/gh-pkg-up.out 2>/dev/null || true)"
      failed=$((failed + 1))
      ;;
  esac
}

if [ -n "${TARGET_FILE:-}" ] && [ -f "$TARGET_FILE" ]; then
  upload_one "$TARGET_FILE"
else
  while IFS= read -r -d '' f; do
    upload_one "$f"
  done < <(find "$TARGET" -type f -print0)
fi

info "uploaded=$uploaded failed=$failed"
[ "$failed" = 0 ] || die "some uploads failed"
[ "$uploaded" -gt 0 ] || die "nothing uploaded"
