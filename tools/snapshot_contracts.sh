#!/usr/bin/env bash
# Freezes the current app sources as the contract snapshot used by module-mode typecheck/unittest.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${CONTRACTS:-${TC_DIR:-/opt/tc}/contracts}"
rm -rf "$DEST" && mkdir -p "$DEST" && cp -r "$ROOT/app/src/main/java/." "$DEST/"
echo "snapshot -> $DEST"
