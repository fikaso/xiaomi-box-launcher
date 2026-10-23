# Xiaomi Box Launcher (Lagani Launcher)

Lagan početni ekran za Xiaomi Android TV boxove (Mi Box 4/S, Xiaomi TV Box S s Google TV-om),
u "staklenom" stilu Apple TV-a. Umjesto Xiaomijevih i Googleovih preporuka i reklama pokazuje
samo ono što treba:

- **prognozu** za tvoje mjesto (danas i 3 dana, Open-Meteo),
- **kalendar mjeseca** s današnjim danom i državnim praznicima,
- **sljedeću utakmicu** tvog kluba s TV kanalom (FotMob; zadano Hajduk),
- **DHMZ upozorenja** za tvoju županiju (vjetar, kiša, snijeg...), samo kad ih ima,
- po želji **događaje** iz Google kalendara i **zadatke** iz Todoista,
- mrežu aplikacija (TiviMate, Stremio, SmartTube, YouTube prvi), skrivanje aplikacija,
  automatsko pokretanje TiviMatea nakon paljenja i **Primi datoteke** (slanje APK-ova, videa
  i postavki na box iz preglednika na mobitelu ili laptopu).

Uz launcher je i skripta koja s boxa makne preporuke, reklame i Xiaomijevu analitiku
(`ciscenje/ocisti.sh`, vraća se s `ciscenje/vrati.sh`).

## Postavljanje

### A) S Claude Codeom (preporučeno)

Treba ti laptop (Windows, Mac ili Linux) na istoj mreži kao box i
[Claude Code](https://claude.com/claude-code). Na boxu uključi USB debugging:

1. Postavke → Sustav (ili Device Preferences) → O uređaju → 7 puta klikni na **Build**
   (na Google TV-u: "Android TV OS build").
2. Postavke → Sustav → Opcije za razvojne programere → uključi **USB debugging**.
3. Zapiši IP adresu boxa: Postavke → Mreža i internet → tvoja mreža.

Zatim kloniraj repozitorij, u njemu pokreni Claude Code i napiši, npr.:

> Postavi mi ovaj launcher na moj Xiaomi box, IP adresa je 192.168.1.50.

Claude slijedi [CLAUDE.md](CLAUDE.md):

1. pokaže ti koji je box i čeka potvrdu,
2. **napravi potpun backup** ([backup.sh](backup.sh): postavke, popisi aplikacija, APK-ovi tvojih
   aplikacija) i s tobom spremi podatke iz aplikacija poput TiviMatea; dok backup nije gotov i
   provjeren, na boxu ne mijenja ništa,
3. s tobom prođe popis aplikacija (što ostaje, što ide) i počisti box,
4. instalira launcher i SmartTube,
5. pita te za mjesto, klub, kalendar i Todoist i sam napravi i pošalje datoteke s postavkama.

Na TV-u ćeš jednom morati potvrditi "Allow USB debugging".

### B) Ručno

Gotov APK je u [Releases](../../releases). Korak po korak (instalacija, čišćenje, SmartTube,
slanje datoteka) piše u [UPUTE.md](UPUTE.md).

## Osobne postavke

Launcher radi i bez ikakvih postavki (Zagreb i Hajduk). Za svoje dodaj na box jednu ili dvije
male tekstualne datoteke. Pošalješ ih kroz **Primi datoteke** (na TV-u otvori Primi datoteke, na
mobitelu upiši adresu s ekrana i odaberi datoteku), a s Claude Codeom ih on napravi i pošalje
umjesto tebe.

| Datoteka | Što | Primjer |
|---|---|---|
| `postavke.txt` | mjesto i klub | [postavke.primjer.txt](postavke.primjer.txt) |
| `kalendari.txt` | Google kalendar (tajna iCal adresa) i Todoist | [kalendari.primjer.txt](kalendari.primjer.txt) |

`kalendari.txt` sadrži tajne (adresu kalendara i Todoist ključ). Nikad ga ne commitaj; obje
datoteke su zato u `.gitignore`.

## Gradnja

Bez Android Studija: `TOOLS=/put/do/alata sh build.sh`. Što treba u mapi s alatima i odakle se
skida, piše u [CLAUDE.md](CLAUDE.md#gradnja-apk-a). Za instalaciju dovoljan je i APK iz Releases.
