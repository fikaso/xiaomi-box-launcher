#!/bin/sh
# Backup Android TV boxa PRIJE ikakvih promjena. Pokreće se s računala (Git Bash na Windowsu):
#   sh backup.sh <IP boxa>
# Sprema u backup-<model>-<datum>/: svojstva boxa, sve postavke, popise aplikacija (i ugašenih i
# maknutih za korisnika), trenutni početni ekran, APK-ove aplikacija koje je korisnik sam
# instalirao i snimku ekrana. Podaci unutar aplikacija (npr. TiviMate liste) se bez roota ne mogu
# kopirati; za njih napravi backup u samoj aplikaciji (vidi CLAUDE.md / UPUTE.md).
# Mapa je u .gitignoreu jer zna sadržavati osobne podatke: nikad je ne commitaj.
set -e
export MSYS_NO_PATHCONV=1
IP=${1:?"upotreba: sh backup.sh <IP boxa>"}
ADB_BIN=${ADB_BIN:-adb}
TARGET="$IP:5555"
case "$IP" in *:*) TARGET="$IP" ;; esac
cd "$(dirname "$0")"

"$ADB_BIN" connect "$TARGET" >/dev/null
adb() { "$ADB_BIN" -s "$TARGET" "$@"; }
sh_() { adb shell "$@" | tr -d '\r'; }

MODEL=$(sh_ getprop ro.product.model | tr ' /' '__')
[ -n "$MODEL" ] || { echo "GREŠKA: box na $TARGET se ne javlja (USB debugging? Allow na TV-u?)" >&2; exit 1; }
DIR="backup-$MODEL-$(date +%Y-%m-%d-%H%M)"
mkdir -p "$DIR/apk"

sh_ getprop > "$DIR/getprop.txt"
for ns in global secure system; do sh_ settings list $ns > "$DIR/settings-$ns.txt"; done
sh_ pm list packages -f > "$DIR/paketi.txt"
sh_ pm list packages -u > "$DIR/paketi-svi-ukljucujuci-maknute.txt"
sh_ pm list packages -d > "$DIR/paketi-ugaseni.txt" || true
sh_ pm list packages -3 > "$DIR/paketi-instalirani.txt"
sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    > "$DIR/pocetni-ekran.txt" || true
adb exec-out screencap -p > "$DIR/ekran.png" || true

# APK-ovi aplikacija koje je korisnik sam instalirao (sistemske se vraćaju s ciscenje/vrati.sh).
for pkg in $(sed 's/^package://' "$DIR/paketi-instalirani.txt"); do
  for path in $(sh_ pm path "$pkg" | sed 's/^package://'); do
    adb pull "$path" "$DIR/apk/$pkg--$(basename "$path")" >/dev/null 2>&1 || echo "upozorenje: $pkg nije kopiran" >&2
  done
done

# Provjera: ništa bitno ne smije biti prazno.
for f in getprop.txt settings-global.txt settings-secure.txt settings-system.txt paketi.txt paketi-svi-ukljucujuci-maknute.txt; do
  [ -s "$DIR/$f" ] || { echo "GREŠKA: $DIR/$f je prazan, backup NIJE potpun" >&2; exit 1; }
done
APPS=$(wc -l < "$DIR/paketi-instalirani.txt" | tr -d ' ')
APKS=$(ls "$DIR/apk" | wc -l | tr -d ' ')
echo "Backup OK: $DIR"
echo "  model $MODEL, $(wc -l < "$DIR/paketi.txt" | tr -d ' ') paketa, $APPS instaliranih aplikacija, $APKS APK datoteka"
echo "  početni ekran: $(cat "$DIR/pocetni-ekran.txt" | tail -1)"
