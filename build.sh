#!/usr/bin/env bash
# Build XMB Home with JDK 11+ and Android SDK platform 34 / build-tools 36.1.0.
# Default signing uses this checkout's ignored development key. For release signing,
# set HOMESTEAD_KEYSTORE, HOMESTEAD_KEYPASS and optionally HOMESTEAD_KEYALIAS.
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "Error: $*" >&2; exit 1; }
if [[ ${1:-} != '' && ${1:-} != install ]]; then fail 'Usage: ./build.sh [install]'; fi
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
for command in java javac keytool; do
  command -v "$command" >/dev/null || fail "$command not found. Install a JDK 11+ or set JAVA_HOME."
done
java_major=$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*$/\1/')
[[ $java_major =~ ^[0-9]+$ && $java_major -ge 11 ]] || fail 'A JDK 11 or later is required.'

DEFAULT_SDK=''
for directory in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/AppData/Local/Android/Sdk"; do
  if [[ -d $directory ]]; then DEFAULT_SDK=$directory; break; fi
done
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$DEFAULT_SDK}}
[[ -n $SDK ]] || fail 'Set ANDROID_SDK_ROOT to your Android SDK directory.'
BT=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.1.0}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}
tool() { if [[ -e $1.bat ]]; then echo "$1.bat"; elif [[ -e $1.exe ]]; then echo "$1.exe"; else echo "$1"; fi; }
for name in aapt aapt2 d8 zipalign apksigner; do
  [[ -f $(tool "$BT/$name") ]] || fail "Missing $name in $BT. Install Android build-tools 36.1.0."
done
[[ -f $ANDROID_JAR ]] || fail "Missing $ANDROID_JAR. Install Android SDK platform 34."
ADB=${ADB:-$(tool "$SDK/platform-tools/adb")}
if [[ ${1:-} == install ]]; then
  [[ -n ${ADB_TARGET:-} ]] || fail 'Set ADB_TARGET=<tv-ip>:5555 for install.'
  [[ -f $ADB ]] || fail "ADB not found at $ADB. Install Android platform-tools or set ADB."
fi

if [[ -n ${HOMESTEAD_KEYSTORE:-} ]]; then
  KEYSTORE=$HOMESTEAD_KEYSTORE
  KEYALIAS=${HOMESTEAD_KEYALIAS:-homestead}
  [[ -f $KEYSTORE ]] || fail "Signing keystore not found: $KEYSTORE"
  [[ -n ${HOMESTEAD_KEYPASS:-} ]] || fail 'Set HOMESTEAD_KEYPASS for your external signing key.'
  export XMB_SIGNING_PASSWORD=$HOMESTEAD_KEYPASS
  echo '==> using external signing key'
else
  KEYSTORE=$PWD/.local/signing/development.jks
  KEYALIAS=xmbhome
  export XMB_SIGNING_PASSWORD=android
  if [[ ! -f $KEYSTORE ]]; then
    echo '==> creating local development signing key'
    (
      umask 077
      mkdir -p .local/signing
      keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$KEYSTORE" \
        -storepass:env XMB_SIGNING_PASSWORD -keypass:env XMB_SIGNING_PASSWORD \
        -alias "$KEYALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=XMB Home Development' >/dev/null
    )
  fi
  echo '==> development signing (not the release certificate)'
fi

MIN_SDK=22
TARGET_SDK=28
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
echo '==> clean'
rm -rf classes dex gen compiled.flata app-unsigned.apk app-with-dex.apk app-aligned.apk
mkdir -p classes dex res/drawable-nodpi

echo '==> artwork'
java tools/MakeArt.java res/drawable-nodpi >/dev/null
java tools/MakeLandscapes.java assets >/dev/null

echo '==> resources'
"$BT/aapt2" compile --dir res -o compiled.flata
"$BT/aapt2" link -o app-unsigned.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml -R compiled.flata \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  -A assets -0 mp4 -0 wav --java gen --auto-add-overlay

echo '==> Java'
find src gen -name '*.java' > sources.txt
javac --release 11 -nowarn -encoding UTF-8 \
  -classpath "$(win "$ANDROID_JAR")" -d classes @sources.txt
rm sources.txt
find classes -name '*.class' > classes.txt
"$(tool "$BT/d8")" --lib "$(win "$ANDROID_JAR")" --min-api "$MIN_SDK" --release \
  --output "$(win "$PWD/dex")" @classes.txt
rm classes.txt

echo '==> package and sign'
cp app-unsigned.apk app-with-dex.apk
( cd dex && "$BT/aapt" add ../app-with-dex.apk classes.dex >/dev/null )
"$BT/zipalign" -f -p 4 app-with-dex.apk app-aligned.apk
"$(tool "$BT/apksigner")" sign --ks "$(win "$KEYSTORE")" --ks-key-alias "$KEYALIAS" \
  --ks-pass env:XMB_SIGNING_PASSWORD --key-pass env:XMB_SIGNING_PASSWORD \
  --min-sdk-version "$MIN_SDK" --out xmb-home.apk app-aligned.apk
"$(tool "$BT/apksigner")" verify --min-sdk-version "$MIN_SDK" xmb-home.apk
unset XMB_SIGNING_PASSWORD

echo "==> xmb-home.apk ($(wc -c < xmb-home.apk | tr -d ' ') bytes)"
if [[ ${1:-} == install ]]; then "$ADB" -s "$ADB_TARGET" install -r xmb-home.apk; fi
