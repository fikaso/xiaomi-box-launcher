#!/system/bin/sh
# Cleans up a Xiaomi Mi Box (Android TV) for watching IPTV through TiviMate.
#
# Run from a computer (adb):
#   adb push cleanup.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/cleanup.sh
#
# Nothing is deleted permanently: apps are only removed for the user
# ("pm uninstall --user 0") and come back with restore.sh or a factory reset.

# ---- 1. Safe to remove (recommendations, ads, things running in the background) ----
SAFE="
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
# From bugreportsender on: Mi Box 4 / S and Xiaomi TV Box S (Google TV). Bug report sending,
# Xiaomi analytics, Alphonso (tracks what you watch for ads), automatic installation of partner
# apps, PatchWall, Mi TV+ and the Xiaomi tab (recommendations with ads), the Google TV
# screensaver and Xiaomi music.

# ---- 2. Optional: uncomment (remove the # in front of) the apps you do NOT use ----
OPTIONAL="
# com.netflix.ninja                       # Netflix
# com.amazon.amazonvideo.livingroom       # Prime Video
# com.google.android.youtube.tv           # YouTube
# com.google.android.apps.mediashell      # Chromecast (casting from a phone to the TV)
# com.google.android.katniss              # Google voice search / Assistant
# com.google.android.speech.pumpkin       # offline speech recognition
"

# ---- 3. Animations at half speed (the box feels faster) ----
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5

remove() {
    pkg="$1"
    if pm list packages | grep -qx "package:$pkg"; then
        am force-stop "$pkg" 2>/dev/null
        if pm uninstall -k --user 0 "$pkg" >/dev/null 2>&1; then
            echo "  removed:    $pkg"
        elif pm disable-user --user 0 "$pkg" >/dev/null 2>&1; then
            echo "  disabled:   $pkg"
        else
            echo "  CAN'T:      $pkg"
        fi
    fi
}

echo "Removing unneeded apps..."
for p in $SAFE; do remove "$p"; done
echo "$OPTIONAL" | sed -e 's/#.*//' | while read -r p; do
    [ -n "$p" ] && remove "$p"
done

# ---- 4. If Lagani Launcher is installed, make it the home screen and turn off Google's ----
if pm list packages | grep -qx "package:hr.lagani.launcher"; then
    cmd package set-home-activity hr.lagani.launcher/.HomeActivity >/dev/null 2>&1
    for p in com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.google.android.tvlauncher; do
        remove "$p"
    done
    # Google TV: without its home screen the Home button is taken over by the setup recovery
    # screen (setupwraith), which has priority over all launchers. Disable it (restore.sh enables it).
    if pm list packages | grep -qx "package:com.google.android.tungsten.setupwraith" \
            && ! pm list packages | grep -qx "package:com.google.android.apps.tv.launcherx"; then
        pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1
        cmd package set-home-activity hr.lagani.launcher/.HomeActivity >/dev/null 2>&1
    fi
    echo "Lagani Launcher set as the home screen."
else
    echo "Lagani Launcher is not installed: Google's home screen stays."
fi

echo
echo "Done. Restart the box:  adb reboot"
