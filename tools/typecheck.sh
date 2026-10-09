#!/usr/bin/env bash
# Local compile check without the Android SDK: compiles app sources with kotlinc against the
# Robolectric android-all jar (Maven Central) and a stand-in R class. CI does the real build.
#
# Usage:
#   tools/typecheck.sh                      # whole app/src/main/java
#   tools/typecheck.sh --own ui/library     # module mode: live files under the owned paths (relative to
#                                           # the package root) + the frozen contract snapshot for the rest
#   --skip <path> keeps the snapshot version of a path inside an owned one (e.g. --own reader --skip reader/extras)
#   --own may repeat. Snapshot dir: $CONTRACTS (default /opt/tc/contracts), made by tools/snapshot_contracts.sh
set -euo pipefail
# kotlinc needs a larger heap for the whole tree plus tests.
export JAVA_OPTS="${JAVA_OPTS:--Xmx3g}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${TC_DIR:-/opt/tc}"
PKG=com/ggumtak/readeraplus
LIVE="$ROOT/app/src/main/java"
CONTRACTS="${CONTRACTS:-$TC/contracts}"
OWN=(); SKIP=()
while [ $# -gt 0 ]; do
  case "$1" in
    --own) OWN+=("$2"); shift 2 ;;
    --skip) SKIP+=("$2"); shift 2 ;;
    *) echo "unknown arg $1"; exit 2 ;;
  esac
done
TAG=$( (IFS=_; echo "${OWN[*]:-all}") | tr '/.' '__')
# Many --own paths make a name longer than the filesystem allows: keep a short readable prefix plus a hash.
[ ${#TAG} -gt 120 ] && TAG="own_$(printf '%s' "$TAG" | md5sum | cut -c1-16)"
OUT="${TC_OUT:-$ROOT/tools/out}/$TAG"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/src"
python3 "$ROOT/tools/gen_r.py" "$ROOT/app/src/main/res" com.ggumtak.readeraplus "$OUT/gen" >/dev/null
if [ ${#OWN[@]} -eq 0 ]; then
  SRC=("$LIVE")
else
  # contract snapshot minus owned paths, plus live owned paths
  cp -r "$CONTRACTS/." "$OUT/src/"
  for o in "${OWN[@]}"; do rm -rf "$OUT/src/$PKG/$o"; done
  for o in "${OWN[@]}"; do
    if [ -e "$LIVE/$PKG/$o" ]; then mkdir -p "$(dirname "$OUT/src/$PKG/$o")"; cp -r "$LIVE/$PKG/$o" "$OUT/src/$PKG/$o"; fi
  done
  for k in "${SKIP[@]}"; do
    rm -rf "$OUT/src/$PKG/$k" "$OUT/test/$PKG/$k" 2>/dev/null || true
    if [ -e "$CONTRACTS/$PKG/$k" ]; then mkdir -p "$(dirname "$OUT/src/$PKG/$k")"; cp -r "$CONTRACTS/$PKG/$k" "$OUT/src/$PKG/$k"; fi
  done
  SRC=("$OUT/src")
fi
CP="$TC/android-all-15.jar:$TC/kotlinx-coroutines-core-jvm-1.9.0.jar"
status=0
"$TC/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -Xjdk-release=17 -no-reflect \
  -cp "$CP" -d "$OUT/classes" "${SRC[@]}" "$OUT/gen" > "$OUT/compile.log" 2>&1 || status=$?
sed -e '/^warning:/d' -e '/Picked up JAVA_TOOL_OPTIONS/d' \
  -e "s#$OUT/src/#app/src/main/java/#g" "$OUT/compile.log"
exit "$status"
