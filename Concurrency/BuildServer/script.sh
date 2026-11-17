#!/bin/bash
#
# Builds and runs the server.
#
#   ./script.sh              compile, then run miniserver.Main
#   ./script.sh arg1 arg2    same, but forwards args to main(String[])
#
set -euo pipefail

# Run from the script's own directory, so it works no matter where you call it.
cd "$(dirname "$0")"

SRC=miniserver
OUT=out
MAIN=miniserver.Main

# Clean build: a stale .class file is worse than a slow compile.
rm -rf "$OUT"
javac -d "$OUT" $(find "$SRC" -name '*.java')

echo "--- running $MAIN ---"
java -cp "$OUT" "$MAIN" "$@"
