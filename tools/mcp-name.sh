#!/usr/bin/env bash
# Copyright (c) 2026 Breno Xavier. All rights reserved.
# Proprietary software: see the LICENSE file at the root of this repository.
#
# SRG name of a Minecraft 1.8.9 method or field, from the MCP readable name,
# for writing Stein Loader mods, which use SRG names (like a compiled Forge
# mod).
#
# Usage: tools/mcp-name.sh GuiScreen drawScreen [Class name ...]
#        tools/mcp-name.sh < list.txt          (one "Class name" per line)
#
# Output: Class.name  func_73863_a (IIF)V   one per owner that declares the member;
# ??? when the name does not exist in that class (it may be in a superclass).
# Needs the MCP names: ./gradlew extractMcp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M="$ROOT/build/tools/mcp-stable-22"
S="$ROOT/build/tools/joined-1.8.9.srg"
[ -f "$M/methods.csv" ] && [ -f "$S" ] || { echo "run ./gradlew extractMcp first" >&2; exit 1; }

look() {
  local cls="$1" n="$2" ids hit="" id l
  ids=$( (awk -F, -v n="$n" '$2==n{print $1}' "$M/methods.csv"; awk -F, -v n="$n" '$2==n{print $1}' "$M/fields.csv") | sort -u)
  for id in $ids; do
    l=$(grep -E "/$cls/$id( |$)" "$S" | head -1 || true)
    [ -n "$l" ] && hit="$hit $id$(echo "$l" | awk '{print ($1=="MD:") ? " " $5 : " (field)"}')"
  done
  printf '%-36s %s\n' "$cls.$n" "${hit:- ???}"
}

if [ $# -ge 2 ]; then
  while [ $# -ge 2 ]; do look "$1" "$2"; shift 2; done
else
  while read -r c n; do [ -n "${c:-}" ] && look "$c" "$n"; done
fi
