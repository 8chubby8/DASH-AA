#!/usr/bin/env bash
# DASH-AA — what changed upstream, and what can come across untouched.
#
#   tools/upstream-sync.sh            report
#   tools/upstream-sync.sh --apply    also copy every file the fork never changed and upstream did
#
# Compares each Kotlin file in upstream DASH (../dash by default, or $DASH_UPSTREAM) against two
# things: upstream's current code, and the upstream commit this fork was taken from (tools/upstream-base).
# That gives four honest answers per file:
#
#   same         fork == upstream now                       nothing to do
#   take         fork == upstream at base, upstream moved    copy it across (what --apply does)
#   fork-only    upstream unchanged since base, fork differs  an intended DASH-AA divergence (FORK.md)
#   MERGE        both changed since base                     merge by hand, then update FORK.md
#
# plus files new upstream (NEW — decide whether the fork wants them) and files that existed at base
# but the fork removed (dropped — the Android-only ones listed in FORK.md; a *change* to one of those
# upstream shows here too and is worth a glance). The reference documents named in
# FORK.md are checked the same way. After a sync, bump tools/upstream-base to the new upstream commit
# and record the new "Mirrors upstream" version in changelog.md.
set -euo pipefail
cd "$(dirname "$0")/.."
UP="${DASH_UPSTREAM:-../dash}"
BASE="$(head -1 tools/upstream-base)"
APPLY="${1:-}"
SRC="app/src/main/java"

[[ -d "$UP/.git" ]] || { echo "upstream DASH not found at $UP (set DASH_UPSTREAM)"; exit 1; }
echo "upstream: $UP @ $(git -C "$UP" rev-parse --short HEAD)   base: ${BASE:0:7}"
echo

declare -A count
check() {   # $1 = path in upstream, $2 = path in fork
  local up="$1" fork="$2" state
  local now; now="$(git -C "$UP" show "HEAD:$up" 2>/dev/null)" || { return; }
  local base; base="$(git -C "$UP" show "$BASE:$up" 2>/dev/null || echo '__absent__')"
  if [[ ! -f "$fork" ]]; then
    if [[ "$base" == "__absent__" ]]; then state="NEW"; else state="dropped"; fi
  else
    local mine; mine="$(cat "$fork")"
    if [[ "$mine" == "$now" ]]; then state="same"
    elif [[ "$mine" == "$base" ]]; then state="take"
    elif [[ "$now" == "$base" ]]; then state="fork-only"
    else state="MERGE"; fi
  fi
  count[$state]=$(( ${count[$state]:-0} + 1 ))
  [[ "$state" == "same" ]] || printf '%-10s %s\n' "$state" "$up"
  if [[ "$APPLY" == "--apply" && "$state" == "take" ]]; then
    printf '%s\n' "$now" > "$fork"
  fi
}

while IFS= read -r f; do
  check "app/src/main/java/$f" "$SRC/$f"
done < <(git -C "$UP" ls-tree -r --name-only HEAD -- app/src/main/java | sed 's#^app/src/main/java/##' | grep '\.kt$')

for doc in module-sdk.md module-layout.md transport.md system_commands.md interface.md hardware.md; do
  check "$doc" "docs/$doc"
done
check "svg-subset.json" "svg-subset.json"

echo
for k in same take fork-only MERGE NEW dropped; do printf '%s=%s  ' "$k" "${count[$k]:-0}"; done; echo
if [[ "$APPLY" == "--apply" ]]; then echo "(applied every 'take')"; fi
