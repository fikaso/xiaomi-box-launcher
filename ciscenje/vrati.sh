#!/system/bin/sh
# Vraća sve što je ocisti.sh maknuo.
#   adb push vrati.sh /data/local/tmp/ && adb shell sh /data/local/tmp/vrati.sh
for p in \
  com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx com.google.android.tvlauncher \
  com.google.android.tvrecommendations com.google.android.leanbacklauncher.recommendations \
  com.google.android.backdrop com.google.android.videos com.google.android.music \
  com.google.android.play.games com.google.android.youtube.tvmusic com.google.android.youtube.tvkids \
  com.google.android.tvtutorials com.google.android.feedback com.google.android.marvin.talkback \
  com.google.android.tv com.android.printspooler com.android.dreams.basic com.android.dreams.phototable \
  com.mitv.videoplayer com.mitv.gallery com.xiaomi.mitv.calendar \
  com.google.android.tv.bugreportsender com.miui.tv.analytics tv.alphonso.alphonso_eula \
  android.autoinstalls.config.xioami.mibox3 com.mitv.tvhome.atv com.mitv.tvhome.michannel \
  com.mitv.tvhome.mitvplus com.mitv.tvhome.oemtab com.google.android.apps.tv.dreamx com.xiaomi.mimusic2 \
  com.google.android.tungsten.setupwraith \
  com.netflix.ninja com.amazon.amazonvideo.livingroom com.google.android.youtube.tv \
  com.google.android.apps.mediashell com.google.android.katniss com.google.android.speech.pumpkin
do
  cmd package install-existing "$p" >/dev/null 2>&1 && echo "  vraćeno: $p"
  pm enable "$p" >/dev/null 2>&1
done
settings put global window_animation_scale 1
settings put global transition_animation_scale 1
settings put global animator_duration_scale 1
echo "Gotovo. Restartaj box: adb reboot"
