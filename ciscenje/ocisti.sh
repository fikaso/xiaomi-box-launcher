#!/system/bin/sh
# Čišćenje Xiaomi Mi Boxa (Android TV) za gledanje IPTV-a preko TiviMatea.
#
# Pokretanje s računala (adb):
#   adb push ocisti.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/ocisti.sh
#
# Ništa se ne briše trajno: aplikacije se samo uklone za korisnika
# ("pm uninstall --user 0"), a vrate se skriptom vrati.sh ili tvorničkim resetom.

# ---- 1. Sigurno za maknuti (preporuke, reklame, stvari koje rade u pozadini) ----
SIGURNO="
com.google.android.tvrecommendations
com.google.android.leanbacklauncher.recommendations
com.google.android.backdrop
com.google.android.videos
com.google.android.music
com.google.android.play.games
com.google.android.youtube.tvmusic
com.google.android.youtube.tvkids
com.google.android.tvtutorials
com.google.android.feedback
com.google.android.marvin.talkback
com.google.android.tv
com.android.printspooler
com.android.dreams.basic
com.android.dreams.phototable
com.mitv.videoplayer
com.mitv.gallery
com.xiaomi.mitv.calendar
com.google.android.tv.bugreportsender
com.miui.tv.analytics
tv.alphonso.alphonso_eula
android.autoinstalls.config.xioami.mibox3
com.mitv.tvhome.atv
com.mitv.tvhome.michannel
com.mitv.tvhome.mitvplus
com.mitv.tvhome.oemtab
com.google.android.apps.tv.dreamx
com.xiaomi.mimusic2
"
# Od bugreportsendera nadalje: Mi Box 4 / S i Xiaomi TV Box S (Google TV). Slanje bug
# reportova, Xiaomijeva analitika, Alphonso (prati što gledaš za reklame), automatska
# instalacija partnerskih aplikacija, PatchWall, Mi TV+ i Xiaomijev tab (preporuke s
# reklamama), Google TV čuvar zaslona i Xiaomijeva glazba.

# ---- 2. Po želji: odkomentiraj (makni # ispred) ono što NE koristiš ----
PO_ZELJI="
com.netflix.ninja                         # Netflix
com.amazon.amazonvideo.livingroom         # Prime Video
# com.google.android.youtube.tv           # YouTube
# com.google.android.apps.mediashell      # Chromecast (slanje s mobitela na TV)
# com.google.android.katniss              # Google glasovno pretraživanje / Asistent
# com.google.android.speech.pumpkin       # offline prepoznavanje govora
"

# ---- 3. Animacije na pola (box djeluje brže) ----
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5

ukloni() {
    pkg="$1"
    if pm list packages | grep -qx "package:$pkg"; then
        am force-stop "$pkg" 2>/dev/null
        if pm uninstall -k --user 0 "$pkg" >/dev/null 2>&1; then
            echo "  uklonjeno:  $pkg"
        elif pm disable-user --user 0 "$pkg" >/dev/null 2>&1; then
            echo "  ugašeno:    $pkg"
        else
            echo "  NE MOGU:    $pkg"
        fi
    fi
}

echo "Uklanjam nepotrebne aplikacije..."
for p in $SIGURNO; do ukloni "$p"; done
echo "$PO_ZELJI" | sed -e 's/#.*//' | while read -r p; do
    [ -n "$p" ] && ukloni "$p"
done

# ---- 4. Ako je instaliran Lagani Launcher, postavi ga i ugasi Googleov početni ekran ----
if pm list packages | grep -qx "package:hr.lagani.launcher"; then
    cmd package set-home-activity hr.lagani.launcher/.HomeActivity >/dev/null 2>&1
    for p in com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do
        ukloni "$p"
    done
    # Google TV: bez njegovog početnog ekrana tipku Home preuzme ekran za oporavak postave
    # (setupwraith), koji ima prednost pred svim launcherima. Ugasi ga (vrati.sh ga pali).
    if pm list packages | grep -qx "package:com.google.android.tungsten.setupwraith" \
            && ! pm list packages | grep -qx "package:com.google.android.apps.tv.launcherx"; then
        pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1
        cmd package set-home-activity hr.lagani.launcher/.HomeActivity >/dev/null 2>&1
    fi
    echo "Lagani Launcher postavljen kao početni ekran."
else
    echo "Lagani Launcher nije instaliran: Googleov početni ekran ostaje."
fi

echo
echo "Gotovo. Restartaj box:  adb reboot"
