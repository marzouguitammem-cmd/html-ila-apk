#!/bin/bash
# Runs on an Android emulator in GitHub Actions:
#  1. installs and opens the app made by the unit test,
#  2. installs and opens صانع التطبيقات,
#  3. asks صانع التطبيقات to build an app ON the device, installs that app and opens it.
# Screenshots and logs go to smoke-out/ (pushed to the ci-results branch).
set -u
OUT=smoke-out
mkdir -p "$OUT"
fail=0
adb wait-for-device
adb logcat -c || true

# Launch a package and confirm it is actually running, tolerating a slow emulator:
# retry pidof for ~30s and re-launch once before giving up.
ensure_running(){
  local pkg="$1" comp="$2" shot="$3"
  for attempt in 1 2; do
    adb shell am start -W -n "$comp" >/dev/null 2>&1
    for i in $(seq 1 12); do
      sleep 2
      pid=$(adb shell pidof "$pkg" 2>/dev/null | tr -d '\r')
      if [ -n "$pid" ]; then
        echo "$pid" > "$OUT/pid-$shot.txt"
        sleep 2
        adb exec-out screencap -p > "$OUT/$shot.png"
        return 0
      fi
    done
  done
  echo "$pkg not running after retries"
  adb exec-out screencap -p > "$OUT/$shot.png"
  adb logcat -d | grep -iE "$pkg|AndroidRuntime" | tail -40 > "$OUT/$shot-logcat.txt"
  return 1
}

echo "== sample app made on the build machine"
adb install -r apks/sample.apk > "$OUT/install-sample.txt" 2>&1 || fail=1
ensure_running com.tammem.apps.citest com.tammem.apps.citest/com.tammem.webrunner.MainActivity 1-sample-app || fail=1
adb shell dumpsys package com.tammem.apps.citest | grep -E "versionCode|versionName|signatures" > "$OUT/pkg-sample.txt"

echo "== builder app"
adb install -r apks/html-ila-apk.apk > "$OUT/install-builder.txt" 2>&1 || fail=1
adb shell am start -W -n com.tammem.htmlilaapk/.MainActivity >> "$OUT/install-builder.txt" 2>&1
sleep 8
adb exec-out screencap -p > "$OUT/2-builder.png"
adb shell input swipe 540 1800 540 600 400
sleep 2
adb exec-out screencap -p > "$OUT/2b-builder-scrolled.png"

echo "== build an app on the device"
D=/sdcard/Android/data/com.tammem.htmlilaapk/files
adb shell am force-stop com.tammem.htmlilaapk
adb shell am start -W -n com.tammem.htmlilaapk/.MainActivity --ez selftest true
for i in $(seq 1 90); do
  adb shell "test -f $D/selftest.done" && break
  sleep 2
done
adb shell cat "$D/selftest.log" > "$OUT/selftest.log" 2>&1
cat "$OUT/selftest.log"
grep -q '^OK' "$OUT/selftest.log" || { echo "self test failed"; fail=1; }
adb pull "$D/selftest.apk" "$OUT/selftest.apk" || fail=1
adb install -r "$OUT/selftest.apk" > "$OUT/install-selftest.txt" 2>&1 || fail=1
cat "$OUT/install-selftest.txt"
ensure_running com.tammem.apps.selftest com.tammem.apps.selftest/com.tammem.webrunner.MainActivity 3-selftest-app || fail=1

echo "== build a Java app on the device (offline engine)"
adb shell am force-stop com.tammem.htmlilaapk
adb shell am start -W -n com.tammem.htmlilaapk/.MainActivity --ez javaselftest true
for i in $(seq 1 120); do
  adb shell "test -f $D/javaselftest.done" && break
  sleep 2
done
adb shell cat "$D/javaselftest.log" > "$OUT/javaselftest.log" 2>&1
cat "$OUT/javaselftest.log"
if grep -q '^OK' "$OUT/javaselftest.log"; then
  adb pull "$D/javaselftest.apk" "$OUT/javaselftest.apk" || fail=1
  adb install -r "$OUT/javaselftest.apk" > "$OUT/install-java.txt" 2>&1 || fail=1
  cat "$OUT/install-java.txt"
  ensure_running com.tammem.apps.jselftest com.tammem.apps.jselftest/com.example.jst.Main 4-java-app || fail=1
  rm -f "$OUT/javaselftest.apk"
else
  echo "offline java self test failed"; fail=1
fi

adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb logcat -d '*:E' | grep -iE "tammem|webrunner|AndroidRuntime|AppMaker" > "$OUT/errors.txt" 2>&1
rm -f "$OUT/selftest.apk"
echo "fail=$fail" > "$OUT/result.txt"
cat "$OUT/crash.txt"
exit $fail
