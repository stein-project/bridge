#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# Copyright (c) 2026 Breno Xavier
#
# Regenerates org/lwjgl/opengl/ContextCapabilities.java from the fields that
# Minecraft reads (scan/lwjgl-fields.txt) crossed with those of LWJGL 3's
# GLCapabilities. Run it again if the LWJGL version changes.
# Needs the LWJGL jars in build/dev-libs (./gradlew devLibs) and javap (PATH or $JAVA_HOME/bin).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
[ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ] && PATH="$JAVA_HOME/bin:$PATH"
command -v javap >/dev/null || { echo "javap is not on the PATH (set JAVA_HOME to a JDK)" >&2; exit 1; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
# Classpath separator: ";" on Windows (including Git Bash/MSYS), ":" elsewhere; cygpath is optional.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac
CP=""
while read -r j; do
  if command -v cygpath >/dev/null 2>&1; then j="$(cygpath -w "$j")"; fi
  CP="$CP$j$SEP"
done < <(find build/dev-libs -name 'lwjgl*.jar' ! -name '*natives*' 2>/dev/null)
[ -n "$CP" ] || { echo "build/dev-libs is empty: run ./gradlew devLibs" >&2; exit 1; }
javap -p -cp "$CP" org.lwjgl.opengl.GLCapabilities 2>/dev/null \
  | grep -oE '\b(GL_[A-Za-z0-9_]+|OpenGL[0-9]+)\b;?' | tr -d ';' | sort -u > "$W/have.txt"
grep -a 'ContextCapabilities' scan/lwjgl-fields.txt | sed 's|.*\.||' | sort -u > "$W/need.txt"
echo "present: $(comm -12 "$W/need.txt" "$W/have.txt" | wc -l) | queried from the driver: $(comm -23 "$W/need.txt" "$W/have.txt" | wc -l)"
