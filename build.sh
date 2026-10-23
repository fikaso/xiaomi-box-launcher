#!/bin/sh
# Gradi APK bez Android Studija. Treba: JDK, i alate u $TOOLS
# (aapt2_64 i android-framework.jar iz apktool.jar, dx.jar iz dalvik-dx, android28.jar iz Robolectric android-all).
set -e
cd "$(dirname "$0")"
TOOLS=${TOOLS:?postavi TOOLS na direktorij s alatima}
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/res $OUT/gen $OUT/dex

"$TOOLS/aapt2_64" compile --dir res -o $OUT/res/res.zip
"$TOOLS/aapt2_64" link -o $OUT/unsigned.apk -I "$TOOLS/android-framework.jar" \
    --manifest AndroidManifest.xml --java $OUT/gen \
    --min-sdk-version 21 --target-sdk-version 28 $OUT/res/res.zip

# android28.jar (Robolectric android-all) nema java.*, pa ih daje JDK preko --release 8.
javac -nowarn --release 8 -cp "$TOOLS/android28.jar" \
    -d $OUT/classes $(find src $OUT/gen -name '*.java')
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=21 --output=$OUT/dex/classes.dex $OUT/classes
# Git Bash na Windowsu nema zip, pa tada dex dodaje JDK-ov jar (M = bez manifesta).
if command -v zip >/dev/null; then
  (cd $OUT/dex && zip -q ../unsigned.apk classes.dex)
else
  (cd $OUT/dex && jar -uMf ../unsigned.apk classes.dex)
fi

# Lozinka ključa nije u repozitoriju: KEY_PASS ili datoteka release.pass uz ključ u $TOOLS.
KEY_PASS=${KEY_PASS:-$(cat "$TOOLS/release.pass" 2>/dev/null)}
[ -n "$KEY_PASS" ] || { echo "postavi KEY_PASS ili napravi $TOOLS/release.pass" >&2; exit 1; }
if [ ! -f "$TOOLS/release.p12" ]; then
  keytool -genkeypair -keystore "$TOOLS/release.p12" -storetype PKCS12 -storepass "$KEY_PASS" \
    -alias lagani -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Lagani Launcher" >/dev/null 2>&1
fi
# Potpis (v1/JAR) JDK-ovim jarsignerom; targetSdk 28 pa Android ne traži v2.
cp $OUT/unsigned.apk LaganiLauncher.apk
jarsigner -keystore "$TOOLS/release.p12" -storetype PKCS12 -storepass "$KEY_PASS" \
    -digestalg SHA-256 -sigalg SHA256withRSA LaganiLauncher.apk lagani >/dev/null
jarsigner -verify LaganiLauncher.apk | head -1
echo "Gotovo: $(pwd)/LaganiLauncher.apk"
