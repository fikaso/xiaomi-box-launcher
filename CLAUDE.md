# Instructions for Claude Code: setting up a new Xiaomi box

The user has a clone of this repository and wants Lite Launcher on their Android TV box, working
the way it does for the author. Reply in the user's language. Work step by step and say what you
are doing; the user sits in front of the TV and now and then has to confirm something with the remote.

## Rules

- **No changes without a complete backup.** Step 3 (backup) is mandatory and comes before cleanup,
  installation and settings. If `backup.sh` reports an error or the user hasn't saved the data in
  their apps yet, stop and sort it out; don't skip it even when the user is in a hurry (explain why).
- **Before any change on the box**, show the user the model (`getprop ro.product.model`), name
  (`settings get global device_name`), Android version and free memory, and wait for confirmation
  that it's their box. There may be several boxes on the network (e.g. their parents').
- Don't touch apps the user uses. Always show them the removal list first.
- `calendars.txt` contains secrets (calendar address, Todoist token): don't print them in the chat,
  don't commit them, don't put them in the repository. The same goes for IPTV playlists and
  TiviMate backups: they are the user's lists with URLs and logins. `settings.txt`,
  `calendars.txt`, `*.m3u`, `*.m3u8`, `*.tmb` and `backup-*/` are in `.gitignore` and must stay there.
- If the box is busy (`dumpsys media_session` shows `state=3`, someone is watching), don't open
  apps on it; ask the user.
- In Git Bash on Windows, protect box paths (`/sdcard/...`) before `adb push` with
  `export MSYS_NO_PATHCONV=1`, otherwise Git Bash turns them into Windows paths.

## 1. Tools on the computer

- **adb**: Android SDK Platform-Tools (developer.android.com/tools/releases/platform-tools).
- **APK**: easiest is the ready-made `LiteLauncher.apk` from this repository's GitHub Releases.
  Building from source is described in [Building the APK](#building-the-apk).

## 2. Connecting to the box

1. If the user hasn't yet: Settings → System / Device Preferences → About → click **Build** 7 times
   (Google TV: "Android TV OS build"), then Developer options → **USB debugging**.
   IP address: Settings → Network & Internet → the network.
2. `adb connect <IP>:5555`. If it says "failed to authenticate", the user has to confirm
   **Allow USB debugging** on the TV (ideally with "Always allow").
3. Show model, name, Android, RAM (`cat /proc/meminfo | head -3`) and wait for confirmation (see Rules).

## 3. Backup (mandatory, before anything else)

1. **The box:** `sh backup.sh <IP>` (on Windows in Git Bash; if `adb` is not on PATH:
   `ADB_BIN=/path/to/adb.exe sh backup.sh <IP>`). The script saves to `backup-<model>-<date>/` the
   device properties, all settings, app lists (including disabled and removed apps), the current
   home screen, APKs of the apps the user installed and a screenshot, then checks that nothing
   important is empty. It must end with "Backup OK".
2. **Data inside apps** (cannot be copied from outside without root). Ask the user which apps they
   use and what would be hard to set up again, and have them back those up in the app. Skip apps
   they don't have (`packages-user-installed.txt` in the backup shows what's installed):
   - **TiviMate** (`ar.tvplayer.tv`): Settings → General → Backup data (saves a `.tmb` file);
     find it with `adb shell find /sdcard -name "*.tmb"` and `adb pull` it into the backup folder.
     Don't open or print its contents.
   - **SmartTube**: Settings → Backup.
   - **Stremio**: add-ons and lists live in the Stremio account; check the user is signed in.
   - Anything else (IPTV playlists, logins): have the user write it down or save it their own way.
3. Show the user a summary (folder, number of apps and APKs, what was saved from apps) and how to
   restore: `cleanup/restore.sh` brings back removed system apps, APKs in `apk/` install with
   `adb install-multiple` (all files of one package together), settings from `settings-*.txt` are
   restored by hand with `settings put`.
4. The folder is in `.gitignore` (it can contain personal data); never commit it.

Only when this is done, move on to cleanup.

## 4. Cleanup

1. `pm list packages` and compare with the lists in `cleanup/cleanup.sh` (section 1 is safe to
   remove; section 2 holds optional apps such as Netflix or YouTube, all commented out).
2. Show the user what would go, especially section 2, and ask what they use. Adjust the lists to
   their answer and to the box model (the script is written for Mi Box 4/S and Xiaomi TV Box S).
3. `adb push cleanup/cleanup.sh /data/local/tmp/` → `adb shell sh /data/local/tmp/cleanup.sh` →
   `adb reboot`. Nothing is deleted permanently; `cleanup/restore.sh` puts everything back.

## 5. Launcher

1. `adb install -r LiteLauncher.apk`
2. Home screen: `adb shell cmd package set-home-activity hr.lagani.launcher/.HomeActivity`.
   On Google TV `cleanup.sh` also disables `setupwraith`, otherwise it takes over the Home button.
3. Permission for the settings folder (without it the launcher reads from its private folder):
   `adb shell pm grant hr.lagani.launcher android.permission.WRITE_EXTERNAL_STORAGE`
4. Check: `adb shell input keyevent KEYCODE_HOME`, then `adb exec-out screencap -p > s.png` and
   look at the picture.

## 6. Personal settings (ask the user, one at a time)

Create the files locally (they are in .gitignore) and send them to the receive folder:
`adb push settings.txt "/sdcard/Download/TV Box/settings.txt"` (same for `calendars.txt`).
The launcher reads them on the next refresh; faster is `adb shell am force-stop hr.lagani.launcher`
and then the Home key. Templates: `settings.example.txt` and `calendars.example.txt`.

1. **Language** of the interface: English (default) or Croatian → `language: en` / `language: hr`
   in `settings.txt`. It also switches weather descriptions, DHMZ warnings and holiday names.
   Suggest the language the user is talking to you in.
2. **Place** ("Which place should I show the forecast for?") → `location: <name>` in `settings.txt`.
   The launcher finds the coordinates and the county for DHMZ itself (Open-Meteo geocoding,
   Croatia only).
3. **Club.** Ask exactly like this, with a smile (in the user's language; for Croatian users use
   the Croatian version):

   > ⚽ Još jedna formalnost: potvrdi da navijaš za najveći hrvatski klub, **HNK Hajduk Split**!
   > (Ako ipak ne, reci za koji klub navijaš, ili "nijedan" ako te nogomet ne zanima.)

   > ⚽ One more formality: please confirm that you support the greatest Croatian club,
   > **HNK Hajduk Split**! (If not, tell me which club you support, or "none" if football isn't
   > your thing.)

   If they confirm, write `club: HNK Hajduk Split`. For another club write its name
   (`club: HNK Rijeka`; the launcher looks it up on FotMob, but check yourself that
   `https://www.fotmob.com/api/data/search/suggest?term=<name>` returns the right club).
   For none write `club: none`.
4. **Calendar** (optional). Needs the secret iCal address: calendar.google.com in a browser →
   Settings → choose the calendar → "Integrate calendar" → **Secret address in iCal format**.
   The user can paste it into the chat (you only write it into `calendars.txt`, never repeat it)
   or create `calendars.txt` themselves and send it via **Upload files**. Google's calendar sync
   on Android TV mostly doesn't work, hence iCal.
5. **Todoist** (optional): Todoist → Settings → Integrations → Developer → API token.
   Line `todoist: <token> <project name>` (without a project name: "TV"). The token gives access
   to the whole Todoist account: like the calendar, never repeat it.

Without `calendars.txt` there is no EVENTS | TODO panel; everything else works.

## 7. SmartTube (YouTube without ads)

If the user wants it: the latest `SmartTube_stable_..._armeabi-v7a.apk` from
https://github.com/yuliskov/SmartTube/releases (the old packages `com.teamsmart.videomanager.tv`
and `com.liskovsoft.smarttubetv.beta` have a stolen signing key; uninstall them first). SponsorBlock
is on by default in its settings; check it (Settings → SponsorBlock → Enable).

## 8. Finish

- Send the user a screenshot of the home screen.
- Ask whether to **turn off USB debugging** (`adb shell settings put global adb_enabled 0`);
  for later changes the user turns it on again.

## Building the APK

`TOOLS=<tools folder> sh build.sh` (also works in Git Bash on Windows). Needs JDK 11+ and in `$TOOLS`:

| File | Where from |
|---|---|
| `aapt2_64` (`aapt2_64.exe` on Windows) | from `apktool.jar` (github.com/iBotPeaches/Apktool/releases): `prebuilt/<os>/aapt2`, renamed |
| `android-framework.jar` | from the same `apktool.jar`: `prebuilt/android-framework.jar` |
| `dx.jar` | Maven Central `com.jakewharton.android.repackaged:dalvik-dx:9.0.0_r3` |
| `android28.jar` | Maven Central `org.robolectric:android-all:9-robolectric-4913185-2` |
| `release.p12` + `release.pass` | signing key; if missing, `build.sh` creates it with the password from `release.pass` (or `KEY_PASS`) |

An APK signed with a different key can't be installed over the Releases version: first
`adb uninstall hr.lagani.launcher` (hidden apps and the Auto TiviMate setting are lost).

## When something doesn't work

- No forecast: `adb logcat -d | grep LiteLauncher` (fetch errors are logged there).
- No match: FotMob returns 403 without a User-Agent (the launcher sends one); the channel is
  published ~10 days ahead, until then the tile says "TV channel not announced yet".
- The DHMZ tile only shows while there is a yellow, orange or red warning for the county.
