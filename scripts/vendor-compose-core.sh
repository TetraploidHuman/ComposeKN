#!/usr/bin/env bash
# 一键同步 Compose Multiplatform Core 源码到 vendor/compose-core（linuxX64 构建）。
#
# 上游基线与全部本地改动外置在 vendor/compose-core.local/（templates + overlay + patches），
# 因此本脚本是幂等的：无论当前 vendor/compose-core 处于什么状态，跑完都得到
# 「指定 tag 的上游源码 + 本地化改动」的确定性结果。
#
# 用法:
#   scripts/vendor-compose-core.sh                  # 用 VERSIONS 里的基线 tag（幂等重建）
#   scripts/vendor-compose-core.sh <tag>            # 同步到指定上游 tag
#   scripts/vendor-compose-core.sh --check          # 重建后跑 :compose-kn-linux:compileKotlinLinuxX64
#   scripts/vendor-compose-core.sh <tag> --check
#   scripts/vendor-compose-core.sh <tag> --refetch  # 强制重新 fetch（忽略本地 ref，清理假 ref 后重取）
#
# 环境变量:
#   COMPOSE_VERSION=1.12.0    # 重建后把依赖里的旧 compose 版本替换成该版本（同时改 skiko 目录）
#   KOTLIN_VERSION=2.4.10     # 同上，替换 kotlin 版本
#   GIT_PROXY=http://...      # 给 git 的代理（等价于 http(s)_proxy）
#   COMPOSE_CORE_REPO=...     # 覆盖上游仓库地址（默认 JetBrains/compose-multiplatform-core）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/vendor/compose-core.local"
DEST="$ROOT/vendor/compose-core"
CACHE="$ROOT/.cache/compose-core-git"
REPO="${COMPOSE_CORE_REPO:-https://github.com/JetBrains/compose-multiplatform-core}"

# ---- 解析参数 -------------------------------------------------------------
TAG=""
CHECK=0
REFETCH=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK=1 ;;
    --refetch) REFETCH=1 ;;   # 强制重新 fetch（绕过本地 ref 快路径，用于清理假 ref）
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) TAG="$arg" ;;
  esac
done
if [[ -z "$TAG" ]]; then
  TAG="$(awk -F= '$1=="tag"{print $2}' "$LOCAL/VERSIONS")"
fi
[[ -n "$TAG" ]] || { echo "未指定 tag，且 $LOCAL/VERSIONS 里没有 tag=" >&2; exit 1; }
OLD_COMPOSE="$(awk -F= '$1=="compose_deps"{print $2}' "$LOCAL/VERSIONS")"
OLD_KOTLIN="$(awk -F= '$1=="kotlin"{print $2}' "$LOCAL/VERSIONS")"

# ---- git 代理 -------------------------------------------------------------
if [[ -n "${GIT_PROXY:-}" ]]; then
  export http_proxy="$GIT_PROXY" https_proxy="$GIT_PROXY"
fi
GIT="${GIT_BIN:-git}"
command -v "$GIT" >/dev/null 2>&1 || { echo "找不到 git（可用 GIT_BIN 指定）" >&2; exit 1; }

# ---- 上游 12 个模块（仓库布局路径 -> vendor 模块名）；ui-backhandler 冻结在 overlay --------------
UPSTREAM_MODULES=(
  "compose/ui/ui"
  "compose/ui/ui-util"
  "compose/ui/ui-geometry"
  "compose/ui/ui-unit"
  "compose/ui/ui-graphics"
  "compose/ui/ui-text"
  # ui-backhandler 不取上游：整模块冻结在 overlay（jar 布局 + navigationevent 兼容 patch）
  "compose/animation/animation-core"
  "compose/animation/animation"
  "compose/foundation/foundation-layout"
  "compose/foundation/foundation"
  "compose/material/material-ripple"
  "compose/material3/material3"
)

echo "==> 同步 compose-core @ $TAG  ->  $DEST"

# ---- 1. 获取源码（sparse blobless clone，缓存复用） ------------------------
# 注意：blobless 克隆的 lazy blob fetch 在弱网/代理下容易 TLS 中断，
# 所以 clone/fetch 允许失败，靠下面的 checkout 重试循环补齐 blob。
if [[ ! -d "$CACHE/.git" ]]; then
  rm -rf "$CACHE"
  mkdir -p "$CACHE"
  # --no-checkout：refs 拉下来即可，blob 全部留给下面的重试循环补齐
  "$GIT" clone -q --depth 1 --filter=blob:none --no-checkout "$REPO" "$CACHE" || true
fi
# 允许失败：缓存处于 partial 状态时这一步可能触发 blob fetch 而中断，重试循环兜底
"$GIT" -C "$CACHE" sparse-checkout set --cone "${UPSTREAM_MODULES[@]}" || true
# 新 clone 只有默认分支，tag 需要先 fetch 进来。
# 注意：fetch origin <tag> 只更新 FETCH_HEAD（不建本地 ref），
# 且 rev-parse 失败时不带 --verify 会把参数原样打到 stdout，所以统一用 --verify。
if [[ "$REFETCH" -eq 0 ]] && "$GIT" -C "$CACHE" rev-parse --verify -q "${TAG}^{commit}" >/dev/null 2>&1; then
  TARGET_SHA="$("$GIT" -C "$CACHE" rev-parse --verify -q "${TAG}^{commit}")"
else
  # 本地无该 tag（或 --refetch）：fetch 进来。
  # 关键：fetch 失败（弱网/代理）时绝不能回退陈旧 FETCH_HEAD——那会 update-ref
  # 造出指向旧 commit 的假本地 ref，之后 rev-parse 命中假 ref 就永不再 fetch。
  # 因此只在 fetch 成功（退出码 0）后才读 FETCH_HEAD，并带重试。
  TARGET_SHA=""
  for attempt in 1 2 3 4 5; do
    if "$GIT" -C "$CACHE" fetch -q --depth 1 origin "$TAG"; then
      cand="$("$GIT" -C "$CACHE" rev-parse --verify -q "FETCH_HEAD^{commit}" 2>/dev/null || true)"
      [[ -n "$cand" ]] && { TARGET_SHA="$cand"; break; }
    fi
    echo "    fetch $TAG 失败，重试 $attempt/5 ..." >&2
    sleep 3
  done
  # 仅在解析到有效 commit 时才建本地 ref
  [[ -n "$TARGET_SHA" ]] && "$GIT" -C "$CACHE" update-ref "refs/tags/$TAG" "$TARGET_SHA" 2>/dev/null || true
fi
if [[ -z "$TARGET_SHA" ]]; then
  echo "!! 无法解析 tag $TAG（网络/代理问题？可用 GIT_PROXY 指定代理）" >&2
  exit 1
fi
if [[ "$("$GIT" -C "$CACHE" rev-parse HEAD 2>/dev/null)" != "$TARGET_SHA" ]]; then
  "$GIT" -C "$CACHE" checkout -q -f "$TARGET_SHA" || true
fi
ok=0
for i in 1 2 3 4 5 6 7 8 9 10; do
  if "$GIT" -C "$CACHE" checkout -q -f HEAD; then ok=1; break; fi
  echo "    blob fetch 中断，重试 $i/10 ..." >&2
  sleep 3
done
[[ "$ok" -eq 1 && -d "$CACHE/compose/ui/ui/src" ]] \
  || { echo "缓存里缺少源码（网络/代理问题？可用 GIT_PROXY 指定代理）" >&2; exit 1; }

# ---- 2. 重建 vendor/compose-core（12 个上游模块的 src/api/res） ------------
rm -rf "$DEST"
mkdir -p "$DEST"
for mod in "${UPSTREAM_MODULES[@]}"; do
  name="${mod##*/}"
  target="$DEST/$name"
  mkdir -p "$target"
  rsync -a --delete \
    --include='src/' --include='src/**' \
    --include='api/' --include='api/**' \
    --include='res/' --include='res/**' \
    --exclude='*' \
    "$CACHE/$mod/" "$target/"
done

# ---- 3. 覆盖本地 build 文件模板 ------------------------------------------
cp "$LOCAL/templates/build.gradle.kts" "$LOCAL/templates/settings.gradle.kts" "$LOCAL/templates/gradle.properties" "$DEST/"
for d in "$LOCAL/templates/modules/"*/; do
  m="$(basename "$d")"
  mkdir -p "$DEST/$m"
  cp "$d/build.gradle.kts" "$DEST/$m/build.gradle.kts"
done

# ---- 4. 拷贝 overlay（本地新增/冻结文件，含整模块冻结） --------------------
# 注意：ui-backhandler 与 lifecycle-viewmodel-compose 在 overlay 里是「整模块冻结」，
# 上游 rsync 出来的同名目录会被 overlay 覆盖/补全。
if [[ -d "$LOCAL/overlay" ]]; then
  rsync -a "$LOCAL/overlay/" "$DEST/"
fi

# ---- 5. 应用 patches（对上游源码的最小本地改动） --------------------------
if compgen -G "$LOCAL/patches/*.patch" >/dev/null; then
  ( cd "$DEST" && patch -p1 -N -r /tmp/compose-core-reject \
      < <(cat "$LOCAL"/patches/*.patch) )
  if [[ -e /tmp/compose-core-reject ]]; then
    echo "!! 有 patch 未干净应用，见 /tmp/compose-core-reject（可能 tag 与基线漂移）" >&2
    exit 1
  fi
fi

# ---- 6. 可选：版本替换 ----------------------------------------------------
bump() { # $1=旧 $2=新 $3=文件...
  [[ -n "$2" && -n "$1" ]] || return 0
  local f
  for f in "$@"; do
    [[ -f "$f" ]] || continue
    sed -i.bak "s/$1/$2/g" "$f" && rm -f "$f.bak"
  done
}
if [[ -n "${COMPOSE_VERSION:-}" ]]; then
  mapfile -t KTS < <(find "$DEST" -name 'build.gradle.kts' -o -name 'settings.gradle.kts' -o -name 'gradle.properties' | grep -v '/build/')
  bump "$OLD_COMPOSE" "$COMPOSE_VERSION" "${KTS[@]}" "$ROOT/vendor/skiko/dependencies.toml"
  echo "==> compose 依赖版本 $OLD_COMPOSE -> $COMPOSE_VERSION"
fi
if [[ -n "${KOTLIN_VERSION:-}" ]]; then
  mapfile -t KTS < <(find "$DEST" -name 'build.gradle.kts' | grep -v '/build/')
  bump "$OLD_KOTLIN" "$KOTLIN_VERSION" "${KTS[@]}" "$ROOT/vendor/skiko/dependencies.toml"
  echo "==> kotlin 版本 $OLD_KOTLIN -> $KOTLIN_VERSION"
fi

# ---- 7. 记录本次同步信息 --------------------------------------------------
{
  echo "tag=$TAG"
  echo "upstream_sha=$("$GIT" -C "$CACHE" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "synced_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "compose_version=${COMPOSE_VERSION:-$OLD_COMPOSE}"
  echo "kotlin_version=${KOTLIN_VERSION:-$OLD_KOTLIN}"
} > "$DEST/.sync-info"

# ---- 8. 基本自检 ----------------------------------------------------------
N_ACTUALS="$(find "$DEST" -path '*/linuxX64Main/*' -name '*.kt' | wc -l | tr -d ' ')"
if [[ "$N_ACTUALS" -lt 39 ]]; then
  echo "!! linuxX64Main actual 只有 $N_ACTUALS 个（预期 >=39），overlay 可能没拷全" >&2
  exit 1
fi
grep -q "SkikoDispatchers.Main" "$DEST/ui/src/nonJvmMain/kotlin/androidx/compose/ui/Actuals.nonJvm.kt" \
  || { echo "!! patch 0001 未生效" >&2; exit 1; }

echo "==> 完成：$N_ACTUALS 个 linuxX64Main actual，8 patches 已应用"

# ---- 9. 可选：编译验证 ----------------------------------------------------
if [[ "$CHECK" -eq 1 ]]; then
  echo "==> 编译验证 :compose-kn-linux:compileKotlinLinuxX64 ..."
  ( cd "$ROOT" && ./gradlew :compose-kn-linux:compileKotlinLinuxX64 )
  echo "==> 编译通过 ✔"
fi
