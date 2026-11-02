#!/bin/bash
# Installiert F4SE + Address Library + ThorPipBoy.dll per adb ins Fallout-4-Verzeichnis (DroiDeck, SD-Karte).
# Aufruf: tools/install_thor.sh <F4SE-Ordner (entpackt)> <version-1-11-240-0.bin> [ThorPipBoy.dll]
# Vorhandene Dateien werden vorher nach <Spiel>/_thorpipboy_backup/<Zeit>/ gesichert.
set -euo pipefail
F4SE_DIR=${1:?F4SE-Ordner}; ADDR=${2:?Address-Library-.bin}; DLL=${3:-$(dirname "$0")/../out/ThorPipBoy.dll}
G=${FO4_DIR:-/storage/XXXX-XXXX/Android/data/com.droiddeck.launcher/files/steam/steamapps/common/Fallout 4}
TMP=/data/local/tmp/thorpipboy
BK="$G/_thorpipboy_backup/$(date +%Y%m%d-%H%M)"
adb shell "rm -rf $TMP; mkdir -p $TMP"

put() { # lokale Datei -> relativer Zielpfad im Spielordner
  local src=$1 rel=$2 dst="$G/$2"
  adb push -q "$src" "$TMP/f" >/dev/null </dev/null
  adb shell "mkdir -p \"\$(dirname \"$dst\")\"; if [ -e \"$dst\" ]; then mkdir -p \"\$(dirname \"$BK/$rel\")\"; cp \"$dst\" \"$BK/$rel\"; fi; cat $TMP/f > \"$dst\"; chmod 666 \"$dst\" 2>/dev/null; true" </dev/null
  echo "  $rel"
}

echo "F4SE:"
for f in f4se_loader.exe f4se_1_11_240.dll; do
  [ -f "$F4SE_DIR/$f" ] || { echo "fehlt: $F4SE_DIR/$f"; exit 1; }
  put "$F4SE_DIR/$f" "$f"
done
[ -f "$F4SE_DIR/f4se_steam_loader.dll" ] && put "$F4SE_DIR/f4se_steam_loader.dll" f4se_steam_loader.dll
if [ -d "$F4SE_DIR/Data/Scripts" ]; then
  (cd "$F4SE_DIR" && find Data/Scripts -type f) | while read -r rel; do put "$F4SE_DIR/$rel" "$rel"; done
fi
echo "Address Library + Plugin:"
put "$ADDR" "Data/F4SE/Plugins/$(basename "$ADDR")"
put "$DLL" "Data/F4SE/Plugins/ThorPipBoy.dll"
put "$(dirname "$0")/../ThorPipBoy.ini" "Data/F4SE/Plugins/ThorPipBoy.ini"
adb shell "rm -rf $TMP"
echo "fertig (Sicherungen, falls vorhanden: $BK)"
