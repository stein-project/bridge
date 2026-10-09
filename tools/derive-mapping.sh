#!/usr/bin/env bash
# Derives the LWJGL 2 -> LWJGL 3 mapping for the incompatible methods.
#
# Rule: LWJGL 3 kept OpenGL's C names, so the equivalent has the LWJGL 2
# name as a prefix plus a type suffix (fv/iv/f/i). We look in
# the same class for a method with an IDENTICAL DESCRIPTOR whose name starts with the
# LWJGL 2 name. The ARB suffix is handled separately: glUniform1ARB -> glUniform1fvARB,
# that is, the type suffix goes BEFORE the ARB.
#
# Output: scan/name-mapping.txt  (class<TAB>name2<TAB>descriptor<TAB>name3)
# Ambiguous cases or ones with no candidate go to the error output, for manual decision.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$ROOT/scan"
IDX="$OUT/lwjgl3-index.txt"; MISS="$OUT/compat-missing.txt"
for f in "$IDX" "$MISS"; do
  [ -s "$f" ] || { echo "missing $f: run tools/check-compat.sh" >&2; exit 1; }
done

: > "$OUT/name-mapping.txt"; : > "$OUT/name-mapping-manual.txt"
auto=0; manual=0
while read -r ref; do
  cls="${ref%%.*}"; rest="${ref#*.}"
  name="${rest%%:*}"; desc="${rest#*:}"

  base="$name"; suffix=""
  case "$name" in *ARB) base="${name%ARB}"; suffix="ARB" ;; esac

  # Candidates in the same class with the same descriptor.
  mapfile -t cands < <(grep -aF "$cls." "$IDX" | grep -aF ":$desc" \
    | sed "s|^$cls\.||; s|:.*||" \
    | awk -v b="$base" -v s="$suffix" '
        index($0, b) == 1 && $0 != b s {
          if (s == "") { if ($0 !~ /ARB$/) print }
          else         { if ($0 ~ /ARB$/) print }
        }' | sort -u)

  if [ "${#cands[@]}" -eq 1 ]; then
    printf '%s\t%s\t%s\t%s\n' "$cls" "$name" "$desc" "${cands[0]}" >> "$OUT/name-mapping.txt"
    auto=$((auto + 1))
  else
    printf '%s\t%s\t%s\t%s\n' "$cls" "$name" "$desc" "${cands[*]:-NONE}" >> "$OUT/name-mapping-manual.txt"
    manual=$((manual + 1))
  fi
done < "$MISS"

echo "mapped automatically:   $auto"
echo "need a decision:        $manual"
echo
echo "--- derived mapping ---"
sed 's|org/lwjgl/opengl/||' "$OUT/name-mapping.txt" | column -t -s$'\t'
if [ "$manual" -gt 0 ]; then
  echo; echo "--- no direct equivalent (manual adapter) ---"
  sed 's|org/lwjgl/opengl/||' "$OUT/name-mapping-manual.txt" | column -t -s$'\t'
fi
