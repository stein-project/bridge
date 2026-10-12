#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# Copyright (c) 2026 Breno Xavier
#
# Extracts the LWJGL 2 API surface actually used by Minecraft.
# Usage: tools/scan-api.sh [path/to/1.8.9.jar]
# Default jar: $MCDIR/versions/1.8.9/1.8.9.jar, or the OS default .minecraft folder.
# Needs javap (PATH or $JAVA_HOME/bin) and unzip.
set -euo pipefail

if [ -z "${MCDIR:-}" ]; then
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) MCDIR="${APPDATA:-$HOME/AppData/Roaming}/.minecraft" ;;
    Darwin) MCDIR="$HOME/Library/Application Support/minecraft" ;;
    *) MCDIR="$HOME/.minecraft" ;;
  esac
fi
JAR="${1:-$MCDIR/versions/1.8.9/1.8.9.jar}"
[ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ] && PATH="$JAVA_HOME/bin:$PATH"
OUT="$(cd "$(dirname "$0")/.." && pwd)/scan"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[ -f "$JAR" ] || { echo "jar not found: $JAR" >&2; exit 1; }
command -v javap >/dev/null || { echo "javap is not on the PATH (set JAVA_HOME to a JDK)" >&2; exit 1; }

echo "extracting $JAR ..."
unzip -qo "$JAR" -x 'META-INF/*' 'assets/*' -d "$WORK"
find "$WORK" -name '*.class' > "$WORK/classes.txt"
echo "  $(wc -l < "$WORK/classes.txt") classes"

mkdir -p "$OUT"
# -a: javap may emit non-UTF8 bytes; without this grep aborts and truncates the result.
echo "disassembling ..."
xargs -a "$WORK/classes.txt" -n 150 javap -p -c 2>/dev/null > "$WORK/disasm.txt"

grep -aoE 'org/lwjgl/[A-Za-z0-9/$_]+."?[A-Za-z0-9_$<>]+"?:\([^)]*\)[A-Za-z0-9/;\[$_]*' \
  "$WORK/disasm.txt" | sort -u > "$OUT/lwjgl-methods.txt"

grep -aoE '// Field org/lwjgl/[A-Za-z0-9/$_]+\.[A-Za-z0-9_$]+' "$WORK/disasm.txt" \
  | sed 's|// Field ||' | sort -u > "$OUT/lwjgl-fields.txt"

echo "methods: $(wc -l < "$OUT/lwjgl-methods.txt")"
echo "fields:  $(wc -l < "$OUT/lwjgl-fields.txt")"
