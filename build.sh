#!/bin/sh
# Builds the APK without Android Studio. Needs a JDK and these tools in $TOOLS
# (aapt2_64 and android-framework.jar from apktool.jar, dx.jar from dalvik-dx, android28.jar from
# Robolectric android-all; see CLAUDE.md, "Building the APK").
set -e
cd "$(dirname "$0")"
TOOLS=${TOOLS:?set TOOLS to the tools directory}
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/res $OUT/gen $OUT/dex

"$TOOLS/aapt2_64" compile --dir res -o $OUT/res/res.zip
"$TOOLS/aapt2_64" link -o $OUT/unsigned.apk -I "$TOOLS/android-framework.jar" \
    --manifest AndroidManifest.xml --java $OUT/gen \
    --min-sdk-version 21 --target-sdk-version 28 $OUT/res/res.zip

# android28.jar (Robolectric android-all) has no java.*, so the JDK provides them via --release 8.
javac -nowarn --release 8 -cp "$TOOLS/android28.jar" \
    -d $OUT/classes $(find src $OUT/gen -name '*.java')
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=21 --output=$OUT/dex/classes.dex $OUT/classes
# Git Bash on Windows has no zip, so the JDK's jar adds the dex instead (M = no manifest).
if command -v zip >/dev/null; then
  (cd $OUT/dex && zip -q ../unsigned.apk classes.dex)
else
  (cd $OUT/dex && jar -uMf ../unsigned.apk classes.dex)
fi

# The key password is not in the repository: KEY_PASS or a release.pass file next to the key in $TOOLS.
KEY_PASS=${KEY_PASS:-$(cat "$TOOLS/release.pass" 2>/dev/null)}
[ -n "$KEY_PASS" ] || { echo "set KEY_PASS or create $TOOLS/release.pass" >&2; exit 1; }
if [ ! -f "$TOOLS/release.p12" ]; then
  keytool -genkeypair -keystore "$TOOLS/release.p12" -storetype PKCS12 -storepass "$KEY_PASS" \
    -alias lagani -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Lite Launcher" >/dev/null 2>&1
fi
# Signature (v1/JAR) with the JDK's jarsigner; targetSdk 28, so Android does not require v2.
cp $OUT/unsigned.apk LiteLauncher.apk
jarsigner -keystore "$TOOLS/release.p12" -storetype PKCS12 -storepass "$KEY_PASS" \
    -digestalg SHA-256 -sigalg SHA256withRSA LiteLauncher.apk lagani >/dev/null
jarsigner -verify LiteLauncher.apk | head -1
echo "Done: $(pwd)/LiteLauncher.apk"
