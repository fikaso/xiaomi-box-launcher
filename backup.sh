#!/bin/sh
# Backs up an Android TV box BEFORE any change. Run from a computer (Git Bash on Windows):
#   sh backup.sh <box IP>
# Saves to backup-<model>-<date>/: device properties, all settings, app lists (including disabled
# apps and apps removed for the user), the current home screen, APKs of the apps the user installed
# and a screenshot. Data inside apps (e.g. TiviMate playlists) cannot be copied without root; back
# those up in the app itself (see CLAUDE.md / MANUAL.md).
# The folder is in .gitignore because it can contain personal data: never commit it.
set -e
export MSYS_NO_PATHCONV=1
IP=${1:?"usage: sh backup.sh <box IP>"}
ADB_BIN=${ADB_BIN:-adb}
TARGET="$IP:5555"
case "$IP" in *:*) TARGET="$IP" ;; esac
cd "$(dirname "$0")"

"$ADB_BIN" connect "$TARGET" >/dev/null
adb() { "$ADB_BIN" -s "$TARGET" "$@"; }
sh_() { adb shell "$@" | tr -d '\r'; }

MODEL=$(sh_ getprop ro.product.model | tr ' /' '__')
[ -n "$MODEL" ] || { echo "ERROR: the box at $TARGET does not respond (USB debugging? Allow on the TV?)" >&2; exit 1; }
DIR="backup-$MODEL-$(date +%Y-%m-%d-%H%M)"
mkdir -p "$DIR/apk"

sh_ getprop > "$DIR/getprop.txt"
for ns in global secure system; do sh_ settings list $ns > "$DIR/settings-$ns.txt"; done
sh_ pm list packages -f > "$DIR/packages.txt"
sh_ pm list packages -u > "$DIR/packages-including-removed.txt"
sh_ pm list packages -d > "$DIR/packages-disabled.txt" || true
sh_ pm list packages -3 > "$DIR/packages-user-installed.txt"
sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    > "$DIR/home-screen.txt" || true
adb exec-out screencap -p > "$DIR/screen.png" || true

# APKs of apps the user installed (system apps come back with cleanup/restore.sh).
for pkg in $(sed 's/^package://' "$DIR/packages-user-installed.txt"); do
  for path in $(sh_ pm path "$pkg" | sed 's/^package://'); do
    adb pull "$path" "$DIR/apk/$pkg--$(basename "$path")" >/dev/null 2>&1 || echo "warning: $pkg was not copied" >&2
  done
done

# Check: nothing important may be empty.
for f in getprop.txt settings-global.txt settings-secure.txt settings-system.txt packages.txt packages-including-removed.txt; do
  [ -s "$DIR/$f" ] || { echo "ERROR: $DIR/$f is empty, the backup is NOT complete" >&2; exit 1; }
done
APPS=$(wc -l < "$DIR/packages-user-installed.txt" | tr -d ' ')
APKS=$(ls "$DIR/apk" | wc -l | tr -d ' ')
echo "Backup OK: $DIR"
echo "  model $MODEL, $(wc -l < "$DIR/packages.txt" | tr -d ' ') packages, $APPS user-installed apps, $APKS APK files"
echo "  home screen: $(cat "$DIR/home-screen.txt" | tail -1)"
