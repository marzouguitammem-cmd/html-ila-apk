#!/bin/bash
# Runs inside the emulator job. Installs the probe, launches it, waits for the
# on-device compile to finish, and copies the result + logcat into smoke-out/.
W="$GITHUB_WORKSPACE"
OUT="$W/probe-out"
mkdir -p "$OUT"
adb wait-for-device
adb logcat -c 2>/dev/null || true
APK=$(ls "$W"/apk/*.apk 2>/dev/null | head -1)
echo "apk=$APK" > "$OUT/install.txt"
adb install -r "$APK" >> "$OUT/install.txt" 2>&1 || true
adb shell am start -n com.tammem.offlineprobe/.MainActivity >> "$OUT/install.txt" 2>&1 || true
D=/sdcard/Android/data/com.tammem.offlineprobe/files
ok=unknown
for i in $(seq 1 120); do
  adb shell "test -f $D/PASS" 2>/dev/null && { ok=1; break; }
  adb shell "test -f $D/FAIL" 2>/dev/null && { ok=0; break; }
  sleep 3
done
adb pull "$D/probe.log" "$OUT/probe.log" 2>/dev/null || echo "(no probe.log)" > "$OUT/probe.log"
echo "ok=$ok" >> "$OUT/probe.log"
adb logcat -d 2>/dev/null | grep -iE "offlineprobe|AndroidRuntime|OfflineCompiler|OutOfMemory|FATAL|art " | tail -120 > "$OUT/logcat.txt" 2>/dev/null
[ -s "$OUT/logcat.txt" ] || echo "(logcat empty)" > "$OUT/logcat.txt"
echo "===== probe.log ====="; cat "$OUT/probe.log"
echo "===== logcat ====="; cat "$OUT/logcat.txt"
exit 0
