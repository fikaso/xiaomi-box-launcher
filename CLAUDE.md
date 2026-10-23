# Upute za Claude Code: postavljanje na novi Xiaomi box

Korisnik ima klon ovog repozitorija i želi Lagani Launcher na svom Android TV boxu, da radi
kao kod autora. Odgovaraj na jeziku korisnika. Radi korak po korak i javljaj što radiš;
korisnik sjedi pred TV-om i povremeno mora nešto potvrditi daljinskim.

## Pravila

- **Bez potpunog backupa nema promjena.** Korak 3 (backup) je obavezan i ide prije čišćenja,
  instalacije i postavki. Ako `backup.sh` javi grešku ili korisnik još nije spremio podatke iz
  aplikacija, stani i riješi to; ne preskači ga ni kad korisnik žuri (objasni zašto).
- **Prije bilo kakve promjene na boxu** pokaži korisniku model (`getprop ro.product.model`), ime
  (`settings get global device_name`), verziju Androida i slobodnu memoriju, i čekaj potvrdu da je
  to njegov box. Na mreži zna biti više boxova (npr. roditeljski).
- Ne diraj aplikacije koje korisnik koristi. Popis za brisanje uvijek mu prvo pokaži.
- `kalendari.txt` sadrži tajne (adresu kalendara, Todoist ključ): ne ispisuj ih u chat, ne
  commitaj, ne stavljaj u repozitorij. `postavke.txt`, `kalendari.txt`, `*.m3u` i `backup-*/` su
  u `.gitignore` i tako treba ostati.
- Ako je box zauzet (`dumpsys media_session` pokazuje `state=3`, netko gleda), ne otvaraj
  aplikacije na njemu; pitaj korisnika.
- U Git Bashu na Windowsu putanje na boxu (`/sdcard/...`) prije `adb push` zaštiti s
  `export MSYS_NO_PATHCONV=1`, inače ih Git Bash pretvori u Windows putanje.

## 1. Alati na računalu

- **adb**: Android SDK Platform-Tools (developer.android.com/tools/releases/platform-tools).
- **APK**: najjednostavnije gotov `LaganiLauncher.apk` iz GitHub Releases ovog repozitorija.
  Gradnja iz koda je u odjeljku [Gradnja APK-a](#gradnja-apk-a).

## 2. Spajanje na box

1. Ako korisnik još nije: Postavke → Sustav / Device Preferences → O uređaju → 7× klik na
   **Build** (Google TV: "Android TV OS build"), pa Opcije za razvojne programere → **USB debugging**.
   IP adresa: Postavke → Mreža i internet → mreža.
2. `adb connect <IP>:5555`. Ako javi "failed to authenticate", korisnik na TV-u mora potvrditi
   **Allow USB debugging** (najbolje uz "Always allow").
3. Pokaži model, ime, Android, RAM (`cat /proc/meminfo | head -3`) i čekaj potvrdu (vidi Pravila).

## 3. Backup (obavezno, prije svega ostalog)

1. **Box:** `sh backup.sh <IP>` (na Windowsu u Git Bashu; ako `adb` nije u PATH-u:
   `ADB_BIN=/put/do/adb.exe sh backup.sh <IP>`). Skripta sprema u `backup-<model>-<datum>/`
   svojstva boxa, sve postavke, popise aplikacija (i ugašenih i maknutih), trenutni početni
   ekran, APK-ove aplikacija koje je korisnik sam instalirao i snimku ekrana, pa provjeri da
   ništa bitno nije prazno. Mora završiti s "Backup OK".
2. **Podaci u aplikacijama** (bez roota se ne mogu kopirati izvana). Pitaj korisnika koje
   aplikacije koristi i što bi mu bilo teško ponovno namjestiti, pa neka u njima napravi backup:
   - **TiviMate**: Postavke → Općenito → Sigurnosna kopija podataka (sprema `.tmb` datoteku);
     nađi je s `adb shell find /sdcard -name "*.tmb"` i `adb pull` u backup mapu.
   - **SmartTube**: Postavke → Sigurnosno kopiranje.
   - **Stremio**: addoni i popisi su na Stremio računu; provjeri da je korisnik prijavljen.
   - Ostalo (IPTV liste, prijave): neka zapiše ili spremi na svoj način.
3. Korisniku pokaži sažetak (mapa, broj aplikacija i APK-ova, što je spremljeno iz aplikacija) i
   reci kako se vraća: `ciscenje/vrati.sh` vraća maknute sistemske aplikacije, APK-ovi iz
   `apk/` se instaliraju s `adb install-multiple` (sve datoteke istog paketa zajedno), postavke
   iz `settings-*.txt` se vraćaju ručno s `settings put`.
4. Mapa je u `.gitignore` (zna sadržavati osobne podatke); nikad je ne commitaj.

Tek kad je ovo gotovo, idi na čišćenje.

## 4. Čišćenje

1. `pm list packages` i usporedi s popisom u `ciscenje/ocisti.sh` (odjeljak 1 je sigurno za
   maknuti; odjeljak 2 su "po želji" aplikacije poput Netflixa ili YouTubea).
2. Pokaži korisniku što bi otišlo, posebno ono iz odjeljka 2, i pitaj što koristi. Popis
   prilagodi njegovom odgovoru i modelu boxa (skripta je pisana za Mi Box 4/S i Xiaomi TV Box S).
3. `adb push ciscenje/ocisti.sh /data/local/tmp/` → `adb shell sh /data/local/tmp/ocisti.sh` →
   `adb reboot`. Ništa se ne briše trajno; `ciscenje/vrati.sh` sve vraća.

## 5. Launcher

1. `adb install -r LaganiLauncher.apk`
2. Početni ekran: `adb shell cmd package set-home-activity hr.lagani.launcher/.HomeActivity`.
   Na Google TV-u `ocisti.sh` ugasi i `setupwraith`, inače on preuzme tipku Home.
3. Dozvola za mapu s postavkama (bez nje launcher čita iz svoje privatne mape):
   `adb shell pm grant hr.lagani.launcher android.permission.WRITE_EXTERNAL_STORAGE`
4. Provjera: `adb shell input keyevent KEYCODE_HOME`, pa `adb exec-out screencap -p > s.png` i
   pogledaj sliku.

## 6. Osobne postavke (pitaj korisnika, jedno po jedno)

Datoteke napravi lokalno (u .gitignoreu su) i pošalji ih u mapu za primanje:
`adb push postavke.txt "/sdcard/Download/TV Box/postavke.txt"` (isto za `kalendari.txt`).
Launcher ih pročita pri sljedećem osvježavanju; brže je `adb shell am force-stop hr.lagani.launcher`
pa tipka Home. Predložak je u `postavke.primjer.txt` i `kalendari.primjer.txt`.

1. **Mjesto** ("Za koje mjesto da pokazujem prognozu?") → `mjesto: <ime>` u `postavke.txt`.
   Launcher sam nađe koordinate i županiju za DHMZ (Open-Meteo geokodiranje, samo Hrvatska).
2. **Klub.** Pitaj točno ovako, s osmijehom:

   > ⚽ Još jedna formalnost: potvrdi da navijaš za najveći hrvatski klub, **Hajduk Split**!
   > (Ako ipak ne, reci za koji klub navijaš, ili "nijedan" ako te nogomet ne zanima.)

   Potvrdi li, upiši `klub: Hajduk`. Za drugi klub upiši ime (`klub: Rijeka`; launcher ga potraži
   na FotMobu, a provjeri i sam da `https://www.fotmob.com/api/data/search/suggest?term=<ime>`
   vraća pravi klub). Za "nijedan" upiši `klub: nijedan`.
3. **Kalendar** (po želji). Treba tajna iCal adresa: calendar.google.com u pregledniku →
   Postavke → odaberi kalendar → "Integriraj kalendar" → **Tajna adresa u iCal formatu**.
   Korisnik je može zalijepiti u chat (ti je samo zapišeš u `kalendari.txt`, ne ponavljaš) ili
   sam napraviti `kalendari.txt` i poslati ga kroz **Primi datoteke**. Googleova sinkronizacija
   kalendara na Android TV-u uglavnom ne radi, zato iCal.
4. **Todoist** (po želji): Todoist → Postavke → Integracije → Za programere → API token.
   Redak `todoist: <token> <ime projekta>` (bez imena projekta: "TV"). Token daje pristup
   cijelom Todoist računu: isto kao kalendar, ne ponavljaj ga.

Bez `kalendari.txt` nema ploče DOGAĐAJI | TODO; ostalo radi.

## 7. SmartTube (YouTube bez reklama)

Ako korisnik želi: najnoviji `SmartTube_stable_..._armeabi-v7a.apk` s
https://github.com/yuliskov/SmartTube/releases (stari paketi `com.teamsmart.videomanager.tv` i
`com.liskovsoft.smarttubetv.beta` imaju ukradeni ključ, prvo ih deinstaliraj). SponsorBlock je u
njegovim postavkama uključen sam od sebe; provjeri (Postavke → SponsorBlock → Enable).

## 8. Kraj

- Snimka početnog ekrana korisniku.
- Pitaj treba li **isključiti USB debugging** (`adb shell settings put global adb_enabled 0`);
  za kasnije promjene ga korisnik opet uključuje.

## Gradnja APK-a

`TOOLS=<mapa s alatima> sh build.sh` (radi i u Git Bashu na Windowsu). Treba JDK 11+ i u `$TOOLS`:

| Datoteka | Odakle |
|---|---|
| `aapt2_64` (`aapt2_64.exe` na Windowsu) | iz `apktool.jar` (github.com/iBotPeaches/Apktool/releases): `prebuilt/<os>/aapt2`, preimenuj |
| `android-framework.jar` | iz istog `apktool.jar`: `prebuilt/android-framework.jar` |
| `dx.jar` | Maven Central `com.jakewharton.android.repackaged:dalvik-dx:9.0.0_r3` |
| `android28.jar` | Maven Central `org.robolectric:android-all:9-robolectric-4913185-2` |
| `release.p12` + `release.pass` | ključ za potpis; ako ga nema, `build.sh` ga napravi uz lozinku iz `release.pass` (ili `KEY_PASS`) |

APK potpisan drugim ključem ne može se instalirati preko verzije iz Releases: prvo
`adb uninstall hr.lagani.launcher` (gube se skrivene aplikacije i postavka Auto TiviMate).

## Kad nešto ne radi

- Nema prognoze: `adb logcat -d | grep LaganiLauncher` (greške dohvaćanja se tu zapisuju).
- Nema utakmice: FotMob bez User-Agenta vraća 403 (launcher ga šalje); kanal se objavljuje
  ~10 dana unaprijed, dotad piše "TV kanal još nije objavljen".
- DHMZ pločica se vidi samo dok postoji žuto, narančasto ili crveno upozorenje za županiju.
