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

echo "== sample app made on the build machine"
adb install -r apks/sample.apk > "$OUT/install-sample.txt" 2>&1 || fail=1
adb shell am start -W -n com.tammem.apps.citest/com.tammem.webrunner.MainActivity >> "$OUT/install-sample.txt" 2>&1
sleep 10
adb exec-out screencap -p > "$OUT/1-sample-app.png"
adb shell pidof com.tammem.apps.citest > "$OUT/pid-sample.txt" || { echo "sample app not running"; fail=1; }
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
adb shell am start -W -n com.tammem.apps.selftest/com.tammem.webrunner.MainActivity >> "$OUT/install-selftest.txt" 2>&1
sleep 10
adb exec-out screencap -p > "$OUT/3-selftest-app.png"
adb shell pidof com.tammem.apps.selftest > "$OUT/pid-selftest.txt" || { echo "on-device app not running"; fail=1; }

adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb logcat -d '*:E' | grep -iE "tammem|webrunner|AndroidRuntime|AppMaker" > "$OUT/errors.txt" 2>&1
rm -f "$OUT/selftest.apk"
echo "fail=$fail" > "$OUT/result.txt"
cat "$OUT/crash.txt"
exit $fail
