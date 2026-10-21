#!/usr/bin/env bash
# Push config.json (shelf, city, buttons, services; see launcher-ui/config.example.json) or a model's remote.json
# to CleanYourTV UI, without rebuilding. The app applies a new config.json once, the next time Home opens.
#
# Usage: TV_HOST=192.168.1.50 launcher-ui/config.sh my-config.json
#        TV_HOST=192.168.1.50 launcher-ui/config.sh --remote examples/tcl-55c725/remote.json
#        TV_HOST=192.168.1.50 launcher-ui/config.sh --file tomato.png      # a logo for config.json "app_tiles"
#        TV_HOST=192.168.1.50 launcher-ui/config.sh --reset        # removes both files from the TV
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
ARG_DIR="$(pwd)"
cd "$(dirname "$0")"
TOOLS="$(cd ../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }
PKG=io.cleanyourtv.ui
URI=content://$PKG.config

case "${1:-}" in
  --reset)
    tv "content delete --uri $URI/config.json; content delete --uri $URI/remote.json; input keyevent KEYCODE_HOME; echo reset" | tail -n 1
    exit 0 ;;
  --remote) NAME=remote.json; FILE="${2:?usage: config.sh --remote <remote.json>}" ;;
  --file) FILE="${2:?usage: config.sh --file <logo.png>}"; NAME=$(basename "$FILE")
    [[ "$NAME" =~ ^[A-Za-z0-9_.-]+\.(png|PNG)$ ]] || { echo "logo files are PNGs named with letters, digits, . _ -" >&2; exit 1; } ;;
  "") echo "usage: config.sh <config.json> | --remote <remote.json> | --reset" >&2; exit 1 ;;
  *) NAME=config.json; FILE="$1" ;;
esac
case "$FILE" in /*) ;; *) FILE="$ARG_DIR/$FILE" ;; esac
[ -f "$FILE" ] || { echo "no such file: $FILE" >&2; exit 1; }
case "$NAME" in *.json) ;; *)
  "$PY" -I "$TOOLS/b64push.py" "$FILE" "/data/local/tmp/cyt-ui-$NAME" >/dev/null
  tv "content write --uri $URI/$NAME < /data/local/tmp/cyt-ui-$NAME && rm -f /data/local/tmp/cyt-ui-$NAME && echo pushed" | tail -n 1
  echo "$NAME is on the TV: use \"logo\": \"$NAME\" in config.json app_tiles."
  exit 0 ;;
esac
"$PY" -I -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$FILE" 2>/dev/null \
  || { echo "$FILE is not valid JSON" >&2; exit 1; }
tv --push "$FILE" "/data/local/tmp/cyt-ui-$NAME" >/dev/null
tv "content write --uri $URI/$NAME < /data/local/tmp/cyt-ui-$NAME && rm -f /data/local/tmp/cyt-ui-$NAME && input keyevent KEYCODE_HOME && echo applied" | tail -n 1
echo "$NAME is on the TV. Remove it with: launcher-ui/config.sh --reset"
