#!/bin/bash
# Assembles the offline Java toolchain into $1 (an assets/toolchain dir):
#   ecj.dex.jar   - ECJ compiler (+ JDK8 java.compiler API), dexed, with resources
#   d8.dex.jar    - D8 dexer, dexed
#   android.jar   - compile-time Android + Java stubs
#   lambdastub.jar- java.lang.invoke.* so ECJ can compile lambdas
# Proven to run on-device (Android ART). Must run on a machine with the Android SDK + JDK 17 + JDK 8.
set -e
OUT="$1"
[ -n "$OUT" ] || { echo "usage: make_java_toolchain.sh <out-dir>"; exit 2; }
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"   # absolute, so zip inside subshells works
WS="$(pwd)"

BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
D8="$BT/lib/d8.jar"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
[ -f "$AJ" ] || AJ=$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)

TMP="$(mktemp -d)"
cd "$TMP"

# Newest ECJ whose classes never reference java.lang.Runtime$Version (absent on ART).
pick=""
for V in 3.33.0 3.32.0 3.31.0 3.30.0 3.29.0 3.28.0 3.27.0 3.26.0 3.24.0 3.21.0 3.18.0 3.14.0; do
  curl -fsSL "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$V/ecj-$V.jar" -o cand.jar || continue
  if unzip -p cand.jar '*.class' 2>/dev/null | grep -qaF 'Runtime$Version'; then continue; fi
  cp cand.jar ecj.jar; pick=$V; break
done
[ -n "$pick" ] || { echo "no suitable ECJ found"; exit 1; }
echo "ECJ $pick"

# JDK 8 java.compiler API (its SourceVersion doesn't call Runtime.version()).
RT8="${JAVA_HOME_8_X64:-/nope}/jre/lib/rt.jar"
[ -f "$RT8" ] || RT8=$(ls /usr/lib/jvm/*8*/jre/lib/rt.jar 2>/dev/null | head -1)
[ -f "$RT8" ] || { echo "JDK 8 rt.jar not found"; exit 1; }
mkdir -p jc && unzip -q -o "$RT8" 'javax/lang/model/*' 'javax/annotation/processing/*' 'javax/tools/*' -d jc
( cd jc && jar cf "$TMP/javacompiler.jar" . )

dex() { local o="$1"; shift; java -cp "$D8" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJ" --output "$o" "$@"; }
mkdir -p ecj_dex d8_dex
dex ecj_dex ecj.jar javacompiler.jar
dex d8_dex "$D8"

# dex-jar = tool resources (minus .class) + the dex.
mk() { cp "$1" "$3"; zip -q -d "$3" '*.class' >/dev/null 2>&1 || true; ( cd "$2" && zip -q -g "$3" classes*.dex ); }
mk ecj.jar ecj_dex "$OUT/ecj.dex.jar"
mk "$D8" d8_dex "$OUT/d8.dex.jar"
cp "$AJ" "$OUT/android.jar"
mkdir -p ls && unzip -q -o "$RT8" 'java/lang/invoke/*' -d ls
( cd ls && jar cf "$OUT/lambdastub.jar" . )

cd "$WS"; rm -rf "$TMP"
echo "toolchain ready:"; du -m "$OUT"/* | sort -n
