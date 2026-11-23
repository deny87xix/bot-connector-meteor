#!/usr/bin/env bash
# Выгружает сигнатуры классов из ci/api-queries.txt в docs/api-notes.md.
# Только сигнатуры членов (javap -p) и список вызовов; декомпилированные тела не коммитятся.
set -u
OUT=docs/api-notes.md
mkdir -p docs

JARS=$(find "$HOME/.gradle" ./.gradle -type f -name '*.jar' ! -name '*sources*' \
  \( -path '*loom*' -o -iname '*meteor*' -o -iname '*baritone*' \) 2>/dev/null | sort -u)
CP=$(printf '%s' "$JARS" | tr '\n' ':')

{
  echo "# API notes (генерируется CI, только сигнатуры)"
  echo
  echo "Коммит: ${GITHUB_SHA:-local}, время: $(date -u +%FT%TZ)"
  echo
  echo "Jar-файлы в classpath:"
  echo '```'
  printf '%s\n' "$JARS" | sed "s|$HOME/||"
  echo '```'
} > "$OUT"

while IFS= read -r raw || [ -n "$raw" ]; do
  line=$(printf '%s' "$raw" | sed -e 's/[[:space:]]*#.*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  [ -z "$line" ] && continue
  spec="$line"
  filter=""
  case "$line" in
    *"::"*) spec="${line%%::*}"; filter="${line#*::}" ;;
  esac
  spec=$(printf '%s' "$spec" | sed 's/[[:space:]]*$//')
  filter=$(printf '%s' "$filter" | sed 's/^[[:space:]]*//')

  case "$spec" in
    find:*)
      name="${spec#find:}"
      {
        echo; echo "## find: $name"; echo '```'
        for j in $JARS; do
          unzip -Z1 "$j" 2>/dev/null | grep -F "$name" | grep '\.class$' | head -n 20 | sed "s|^|$(basename "$j"): |"
        done
        echo '```'
      } >> "$OUT"
      ;;
    calls:*)
      cls="${spec#calls:}"
      {
        echo; echo "## calls: $cls"; echo '```'
        javap -p -c -cp "$CP" "$cls" 2>&1 | awk '
          /^  [^ ].*[;{]$/ { print; next }
          /invoke|: new / { s = $0; sub(/^.*\/\/ */, "    -> ", s); print s }
        ' | head -n 400
        echo '```'
      } >> "$OUT"
      ;;
    *)
      cls="$spec"
      {
        echo; echo "## $cls"; echo '```'
        if [ -n "$filter" ]; then
          javap -p -cp "$CP" "$cls" 2>&1 | grep -E "Compiled from|class |interface |Error|$filter" | head -n 300
        else
          javap -p -cp "$CP" "$cls" 2>&1 | head -n 300
        fi
        echo '```'
      } >> "$OUT"
      ;;
  esac
done < ci/api-queries.txt
