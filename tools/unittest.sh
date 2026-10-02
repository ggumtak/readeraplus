#!/usr/bin/env bash
# Local JVM unit tests without the Android SDK. Compiles main + test sources against android-all
# (compile + runtime: pure-Java framework classes such as org.json work; native ones such as
# Paint/Bitmap/SQLite do not) and runs JUnit4. CI runs the same tests through Gradle.
#
# Usage:
#   tools/unittest.sh [--own path ...] [TestClass ...]
#   --own path   module mode (see typecheck.sh); also restricts compiled tests to test/<path>
#   TestClass    fully qualified names; default = every *Test.kt compiled
set -euo pipefail
# kotlinc needs a larger heap for the whole tree plus tests.
export JAVA_OPTS="${JAVA_OPTS:--Xmx3g}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${TC_DIR:-/opt/tc}"
PKG=com/ggumtak/readeraplus
LIVE="$ROOT/app/src/main/java"
TESTS="$ROOT/app/src/test/java"
CONTRACTS="${CONTRACTS:-$TC/contracts}"
OWN=(); SKIP=(); CLASSES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --own) OWN+=("$2"); shift 2 ;;
    --skip) SKIP+=("$2"); shift 2 ;;
    *) CLASSES+=("$1"); shift ;;
  esac
done
TAG=test_$( (IFS=_; echo "${OWN[*]:-all}") | tr '/.' '__')
OUT="${TC_OUT:-$ROOT/tools/out}/$TAG"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/src" "$OUT/test"
python3 "$ROOT/tools/gen_r.py" "$ROOT/app/src/main/res" com.ggumtak.readeraplus "$OUT/gen" >/dev/null
if [ ${#OWN[@]} -eq 0 ]; then
  cp -r "$LIVE/." "$OUT/src/"
  [ -d "$TESTS" ] && cp -r "$TESTS/." "$OUT/test/"
else
  cp -r "$CONTRACTS/." "$OUT/src/"
  for o in "${OWN[@]}"; do rm -rf "$OUT/src/$PKG/$o"; done
  for o in "${OWN[@]}"; do
    if [ -e "$LIVE/$PKG/$o" ]; then mkdir -p "$(dirname "$OUT/src/$PKG/$o")"; cp -r "$LIVE/$PKG/$o" "$OUT/src/$PKG/$o"; fi
    if [ -e "$TESTS/$PKG/$o" ]; then mkdir -p "$(dirname "$OUT/test/$PKG/$o")"; cp -r "$TESTS/$PKG/$o" "$OUT/test/$PKG/$o"; fi
  done
  for k in "${SKIP[@]}"; do
    rm -rf "$OUT/src/$PKG/$k" "$OUT/test/$PKG/$k" 2>/dev/null || true
    if [ -e "$CONTRACTS/$PKG/$k" ]; then mkdir -p "$(dirname "$OUT/src/$PKG/$k")"; cp -r "$CONTRACTS/$PKG/$k" "$OUT/src/$PKG/$k"; fi
  done
fi
CP="$TC/android-all-15.jar:$TC/kotlinx-coroutines-core-jvm-1.9.0.jar:$TC/junit-4.13.2.jar:$TC/hamcrest-core-1.3.jar"
status=0
"$TC/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -Xjdk-release=17 -no-reflect -cp "$CP" -d "$OUT/classes" \
  "$OUT/src" "$OUT/test" "$OUT/gen" > "$OUT/compile.log" 2>&1 || status=$?
sed -e '/^warning:/d' -e '/Picked up JAVA_TOOL_OPTIONS/d' \
  -e "s#$OUT/src/#app/src/main/java/#g; s#$OUT/test/#app/src/test/java/#g" "$OUT/compile.log"
if [ "$status" -ne 0 ]; then exit "$status"; fi
javac -nowarn -d "$OUT/classes" "$OUT"/gen/com/ggumtak/readeraplus/*.java
if [ ${#CLASSES[@]} -eq 0 ]; then
  find "$OUT/test" -name '*Test.kt' | sed "s#$OUT/test/##; s#\.kt\$##; s#/#.#g" > "$OUT/test-classes.txt"
  mapfile -t CLASSES < "$OUT/test-classes.txt"
fi
if [ ${#CLASSES[@]} -eq 0 ]; then echo "no tests"; exit 0; fi
java -Xmx2g -cp "$OUT/classes:$CP:$TC/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore "${CLASSES[@]}" 2>&1 | grep -v "Picked up JAVA_TOOL_OPTIONS"
