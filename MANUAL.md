# Manual setup (without Claude Code)

Step by step for setting up Lite Launcher and the cleanup by hand. With Claude Code this is all
done for you, see [README.md](README.md). The launcher is in English by default; with
`language: hr` in `settings.txt` it is in Croatian (Upload files = Primi datoteke, Hidden = Skrivene).

## What's included
- **LiteLauncher.apk** (from GitHub Releases, ~60 KB): a light home screen. No recommendations,
  ads or background services; from the internet it only fetches the forecast, holidays, your
  club's next match, DHMZ weather warnings and (if you set them up) your calendar and Todoist.
  - Order: TiviMate, Stremio, SmartTube, YouTube, then the rest alphabetically.
  - **Auto TiviMate** button: when on, TiviMate opens by itself after the box starts.
  - Long press OK or the Menu key on an app opens a menu: uninstall, clear cache, hide, open on start.
  - **Hidden** button brings hidden apps back.
  - **Upload files** button: send files from a laptop or phone to the box (see below).
- **cleanup/cleanup.sh**: removes everything unneeded (recommendations, Play Movies, games,
  screensaver, Xiaomi's PatchWall and analytics...), sets animations to half speed and, if Lite
  Launcher is installed, turns off Google's home screen.
- **cleanup/restore.sh**: puts everything back.
- **backup.sh**: backs up the box from a computer before any change.

## Step 1: Back up first
Don't change anything until you have a backup.
1. In the apps you use, save their data. For example, if you have **TiviMate**: Settings → General →
   Backup data (copy the `.tmb` file to your phone or laptop; these are your playlists, never put
   them on GitHub). **SmartTube**: Settings → Backup. Write down IPTV playlists and logins that
   would be hard to enter again.
2. If you have a computer with adb (see Step 2): `sh backup.sh <box IP>`. It saves settings, app
   lists and the APKs of your apps to `backup-<model>-<date>/` and ends with "Backup OK".

## Step 2: Enable ADB on the box
1. Settings → Device Preferences (Google TV: System) → About → click **Build** 7 times
   (Google TV: "Android TV OS build").
2. Settings → Device Preferences → Developer options → turn on **USB debugging**.
3. Note the box's IP address: Settings → Network → your network.

On the first connection the box asks "Allow USB debugging?". Choose **Always allow**, then **OK**.

## Step 3: Install the launcher
With a computer: `adb connect <box IP>` and `adb install -r LiteLauncher.apk`.
Without a computer: use the **Downloader** app (by AFTVnews) or **Send Files to TV**, copy the APK
to the box and open it. If the box asks, allow "installing from unknown sources".

## Step 4: Clean up
From a computer (Windows/Mac/Linux with Android "platform-tools"):
```
adb connect <box IP>
adb push cleanup/cleanup.sh /data/local/tmp/
adb shell sh /data/local/tmp/cleanup.sh
adb reboot
```
Without a computer, from an Android phone: install **Bugjaeger** or **Remote ADB Shell**, connect to
the box's IP and paste the commands from `cleanup.sh`.

The script doesn't touch TiviMate, Stremio, SmartTube, YouTube, Netflix or Prime Video. To remove
apps you don't use (Netflix, Prime Video, YouTube, Chromecast...), open `cleanup.sh` and remove the
`#` in front of them in the "Optional" section before running it.

## Step 5: Personal settings
The launcher works without any settings (Zagreb weather, HNK Hajduk Split). For your own, put one
or two small text files on the box (templates: [settings.example.txt](settings.example.txt) and
[calendars.example.txt](calendars.example.txt)):

- `settings.txt`: `location: <place>` for the forecast and DHMZ warnings (the launcher finds the
  coordinates and county itself), `club: <club>` for the next match tile (`club: none` hides it)
  and `language: en` or `language: hr` for the interface (default English).
- `calendars.txt` (optional): the **secret iCal address** of your Google calendar (Google Calendar
  in a browser → Settings → your calendar → Integrate calendar → Secret address in iCal format)
  and/or `todoist: <API token> <project>` (Todoist → Settings → Integrations → Developer). This file
  contains secrets: never share or publish it.

Send them to the box with **Upload files** (below), or with adb:
`adb push settings.txt "/sdcard/Download/TV Box/settings.txt"`.

## Sending files from a laptop or phone to the box
1. On the TV open **Upload files**. The screen shows an address, e.g. `http://192.168.1.23:8080`.
2. On a laptop or phone on the **same network**, type that address into a browser.
3. Click the box or drag files into it. You can see the upload progress.
4. On the TV the file appears in the list:
   - **APK**: click installs it. The first time Android asks whether Lite Launcher may install
     apps; allow it.
   - Video, pictures, music: click opens them in the matching app.
   - Long press deletes the file.

Files are saved to `Download/TV Box` (if you allow the launcher storage access), so other apps can
see them too. Receiving only works while **Upload files** is open, so nobody can send anything
once you close it.

## SmartTube (YouTube without ads)
An old version stops working when YouTube changes, so you need a new one. SmartTube's signing key
was stolen and it was re-released under a new package name, so **uninstall the old version first**:
on SmartTube long press OK → Info → Uninstall.
1. Download the latest APK from the official page: https://github.com/yuliskov/SmartTube/releases
   (`SmartTube_stable_..._armeabi-v7a.apk`; if it's missing, take `..._universal.apk`).
2. Send it to the box with **Upload files** and click it.
3. In SmartTube turn on automatic updates: Settings → About → **check for updates**.
   SponsorBlock (skips sponsor segments) is on by default: Settings → SponsorBlock.

## If something goes wrong
```
adb push cleanup/restore.sh /data/local/tmp/
adb shell sh /data/local/tmp/restore.sh
adb reboot
```
Your apps' APKs are in the backup folder (`adb install-multiple` with all files of one package).
The last resort is a factory reset, after which everything is like new.
