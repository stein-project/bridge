#!/usr/bin/env bash
# For each LWJGL 2 method that Minecraft calls, checks whether LWJGL 3
# offers a method with an IDENTICAL descriptor.
#
# What matches: LWJGL 3 serves it directly — it does not go in the shim, and we must NOT
# declare the class (it would collide on the classpath, since both use the same
# package names).
# What does not match: becomes work for us, and requires deciding the strategy per class
# (a class is all-or-nothing: if we declare it, we have to implement everything).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/build/dev-libs"; OUT="$ROOT/scan"
[ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ] && PATH="$JAVA_HOME/bin:$PATH"
command -v javap >/dev/null || { echo "javap is not on the PATH (set JAVA_HOME to a JDK)" >&2; exit 1; }

mapfile -t JARS < <(find "$LIBS" -name '*.jar' ! -name '*natives*' 2>/dev/null)
[ "${#JARS[@]}" -gt 0 ] || { echo "build/dev-libs is empty: run ./gradlew devLibs" >&2; exit 1; }
# Classpath separator: ";" on Windows (including Git Bash/MSYS), ":" elsewhere. A Windows javap does not understand
# MSYS paths (/c/...), so they are converted when cygpath exists.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac
CP=""
for j in "${JARS[@]}"; do
  if command -v cygpath >/dev/null 2>&1; then j="$(cygpath -w "$j")"; fi
  CP="$CP$j$SEP"
done

# Classes that LWJGL 3 also defines (they collide) — the delegation candidates.
is_shared() {
  case "$1" in
    org/lwjgl/opengl/GL[0-9]*|org/lwjgl/opengl/ARB*|org/lwjgl/opengl/EXT*|org/lwjgl/BufferUtils) return 0 ;;
    *) return 1 ;;
  esac
}

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

# Indexes only the relevant classes, straight from the classpath (without extracting jars).
mapfile -t CLASSES < <(cut -d. -f1 "$OUT/lwjgl-methods.txt" | sort -u)
for cls in "${CLASSES[@]}"; do
  is_shared "$cls" || continue
  javap -p -s -cp "$CP" "${cls//\//.}" 2>/dev/null
done | awk '
  /^(public |final |abstract )*(class|interface) / {
    for (i=1;i<=NF;i++) if ($i=="class"||$i=="interface") { c=$(i+1); gsub(/\./,"/",c); gsub(/<.*/,"",c); cls=c }
    name=""; next }
  /^  / && /\(/ && !/descriptor:/ {
    m=$0; sub(/\(.*/,"",m); n=split(m,p," "); name=p[n]; sub(/.*\./,"",name); next }
  /^  / && !/\(/ && !/descriptor:/ { name=""; next }   # field: invalidates the previous name
  /descriptor: / { if (name != "") print cls "." name ":" $2 }
' | sort -u > "$OUT/lwjgl3-index.txt"

echo "methods indexed in LWJGL 3: $(wc -l < "$OUT/lwjgl3-index.txt")"
[ -s "$OUT/lwjgl3-index.txt" ] || { echo "empty index: javap did not resolve the classes" >&2; exit 1; }

: > "$OUT/compat-ok.txt"; : > "$OUT/compat-missing.txt"
while read -r ref; do
  is_shared "${ref%%.*}" || continue
  if grep -qaxF "$ref" "$OUT/lwjgl3-index.txt"; then echo "$ref" >> "$OUT/compat-ok.txt"
  else echo "$ref" >> "$OUT/compat-missing.txt"; fi
done < "$OUT/lwjgl-methods.txt"

ok=$(wc -l < "$OUT/compat-ok.txt"); miss=$(wc -l < "$OUT/compat-missing.txt")
echo
echo "compatible (LWJGL 3 serves it directly): $ok"
echo "incompatible (becomes work):          $miss"
if [ "$miss" -gt 0 ]; then
  echo; echo "--- incompatible, by class ---"
  sed 's|org/lwjgl/||' "$OUT/compat-missing.txt" | cut -d. -f1 | sort | uniq -c | sort -rn
fi
