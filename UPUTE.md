# Lagani Launcher i čišćenje za Xiaomi Mi Box

## Što je u paketu
- **LaganiLauncher.apk**: lagan početni ekran (~60 KB, iz GitHub Releases). Nema preporuka ni reklama ni pozadinskih servisa; s interneta dohvaća samo prognozu, praznike, utakmicu kluba, DHMZ upozorenja i (ako ih postaviš) kalendar i Todoist.
  - Redoslijed: TiviMate, Stremio, SmartTube, YouTube, a zatim ostale abecedno.
  - Gumb **Auto TiviMate**: kad je uključen, TiviMate se otvori sam nakon paljenja boxa.
  - Dugi pritisak na OK ili tipka Menu na aplikaciji otvara izbornik: deinstaliraj, očisti cache, sakrij, otvaraj nakon paljenja.
  - Gumb **Skrivene** vraća sakrivene aplikacije.
  - Gumb **Primi datoteke**: slanje datoteka s laptopa ili mobitela na box (vidi niže).
- **ciscenje/ocisti.sh**: miče sve nepotrebno (preporuke, Play Movies, igre, screensaver, Live Channels itd.), stavlja animacije na pola i, ako je Lagani Launcher instaliran, gasi Googleov početni ekran.
- **ciscenje/vrati.sh**: vraća sve kako je bilo.
- **backup.sh**: backup boxa s računala prije ikakvih promjena.

## Prije svega: backup
Ništa ne mijenjaj dok nemaš backup.
1. U aplikacijama koje koristiš spremi njihove podatke, npr. ako imaš **TiviMate**: Postavke → Općenito →
   Sigurnosna kopija podataka (kopiraj `.tmb` datoteku na mobitel ili laptop; to su tvoje liste, ne stavljaj ih na GitHub), **SmartTube** →
   Postavke → Sigurnosno kopiranje. Zapiši IPTV liste i prijave koje bi ti bilo teško ponovno upisati.
2. Ako imaš računalo s adb-om (vidi Korak 2): `sh backup.sh <IP boxa>`. Spremi postavke, popise
   aplikacija i APK-ove tvojih aplikacija u mapu `backup-<model>-<datum>/` i na kraju javi "Backup OK".

## Korak 1: Instaliraj launcher na box
Najlakše je preko aplikacije **Downloader** (by AFTVnews) ili **Send Files to TV**. Kopiraj APK na box i otvori ga.
Ako box pita, dopusti "instalaciju iz nepoznatih izvora".

## Slanje datoteka s laptopa ili mobitela na box
1. Na TV-u otvori **Primi datoteke**. Na ekranu piše adresa, npr. `http://192.168.1.23:8080`.
2. Na laptopu ili mobitelu na **istoj mreži** upiši tu adresu u preglednik (Chrome, Edge…).
3. Klikni na okvir ili povuci datoteke u njega. Vidi se napredak slanja.
4. Na TV-u se datoteka pojavi na popisu:
   - **APK**: klik ga instalira. Prvi put Android pita smije li Lagani Launcher instalirati aplikacije; dopusti.
   - Video, slike, glazba: klik ih otvori u odgovarajućoj aplikaciji.
   - Dugi pritisak briše datoteku.

Datoteke se spremaju u `Download/TV Box` (ako launcheru dopustiš pristup pohrani) pa ih vide i druge aplikacije.
Primanje radi samo dok je ekran **Primi datoteke** otvoren, pa nitko ne može ništa poslati kad ga zatvoriš.

## Mjesto za prognozu i DHMZ upozorenja
Bez postavki launcher pokazuje vrijeme za Zagreb. Za drugo mjesto napravi datoteku `postavke.txt` s jednim retkom:
```
mjesto: Osijek
```
i pošalji je na box preko **Primi datoteke**. Launcher sam pronađe koordinate (Open-Meteo) i županiju za DHMZ upozorenja.

## SmartTube (YouTube bez reklama)
Stara verzija prestane raditi kad je YouTube promijeni, pa treba novu.
SmartTubeu je ukraden ključ za potpisivanje, pa je izašao pod novim imenom paketa. Zato staru verziju **obavezno prvo deinstaliraj**:
na SmartTubeu dugi pritisak OK → Informacije → Deinstaliraj.
1. Na laptopu skini najnoviji APK sa službene stranice: https://github.com/yuliskov/SmartTube/releases
   (datoteka `SmartTube_stable_..._armeabi-v7a.apk`; ako nje nema, uzmi `..._universal.apk`).
2. Pošalji ga na box preko **Primi datoteke** i klikni na njega.
3. U SmartTubeu uključi automatsko ažuriranje: Postavke → O aplikaciji / About → **provjera ažuriranja**. Tako više neće prestati raditi.

## Korak 2: Uključi ADB na boxu
1. Postavke → Device Preferences → About → klikni 7 puta na **Build**.
2. Postavke → Device Preferences → Developer options → uključi **USB debugging**.
3. Pogledaj IP adresu boxa: Postavke → Network → tvoja mreža.

## Korak 3: Pokreni čišćenje
**S računala** (Windows/Mac/Linux, s instaliranim "platform-tools"):
```
adb connect 192.168.1.XX
adb push ciscenje/ocisti.sh /data/local/tmp/
adb shell sh /data/local/tmp/ocisti.sh
adb reboot
```
Kod prvog spajanja box pita "Allow USB debugging?". Odaberi **Always allow** pa **OK**.

**Bez računala**, s Android mobitela: instaliraj aplikaciju **Bugjaeger** ili **Remote ADB Shell**, spoji se na IP boxa i zalijepi naredbe iz `ocisti.sh`.

Skripta ne dira TiviMate, Stremio, SmartTube ni YouTube. Ako koristiš Netflix i slično, a želiš ih maknuti, otvori `ocisti.sh` i makni `#` ispred njih u odjeljku "Po želji".

## Ako nešto ne valja
```
adb push ciscenje/vrati.sh /data/local/tmp/
adb shell sh /data/local/tmp/vrati.sh
adb reboot
```
Krajnja opcija je tvornički reset, nakon kojeg je sve kao novo.
