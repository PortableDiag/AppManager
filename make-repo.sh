#!/usr/bin/env bash
# Generate an App Manager repository index.json from a folder of APKs.
#
# Usage:  ./make-repo.sh <folder-with-apks>  [> index.json]
#
# Reads every *.apk in the folder with aapt and prints a JSON index the
# App Manager app can consume. Host the folder (apks + index.json) on any
# static web server and point Settings > Repository URL at the index.json.

set -euo pipefail

DIR="${1:-.}"
AAPT="$(command -v aapt || echo /home/null/android-sdk/build-tools/34.0.0/aapt)"

if [ ! -x "$AAPT" ]; then
  echo "aapt not found; set AAPT or install build-tools" >&2
  exit 1
fi

esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

first=1
printf '{\n  "apps": [\n'
for apk in "$DIR"/*.apk; do
  [ -e "$apk" ] || continue
  line="$("$AAPT" dump badging "$apk" 2>/dev/null | grep -m1 '^package:')"
  pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$line")"
  vc="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$line")"
  vn="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$line")"
  label="$("$AAPT" dump badging "$apk" 2>/dev/null | sed -n "s/^application-label:'\(.*\)'/\1/p" | head -1)"
  [ -n "$label" ] || label="$pkg"
  [ $first -eq 1 ] || printf ',\n'
  first=0
  printf '    {\n'
  printf '      "package": "%s",\n' "$(esc "$pkg")"
  printf '      "label": "%s",\n' "$(esc "$label")"
  printf '      "versionName": "%s",\n' "$(esc "$vn")"
  printf '      "versionCode": %s,\n' "${vc:-0}"
  printf '      "apk": "%s"\n' "$(esc "$(basename "$apk")")"
  printf '    }'
done
printf '\n  ]\n}\n'
