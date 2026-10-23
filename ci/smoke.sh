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

echo "== build a Java app on the device (offline engine) and press Install"
adb shell am force-stop com.tammem.htmlilaapk
# let the app open Android's installer (the user grants this once on a real phone)
adb shell appops set com.tammem.htmlilaapk REQUEST_INSTALL_PACKAGES allow || true
adb shell am start -W -n com.tammem.htmlilaapk/.MainActivity --ez javaselftest true
for i in $(seq 1 120); do
  adb shell "test -f $D/javaselftest.done" && break
  sleep 2
done
adb shell cat "$D/javaselftest.log" > "$OUT/javaselftest.log" 2>&1
cat "$OUT/javaselftest.log"
sleep 3
adb exec-out screencap -p > "$OUT/4a-installer.png"
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 > "$OUT/installer-top.txt"
cat "$OUT/installer-top.txt"
# The app must still be alive (it crashed here before) and the installer must be on top.
grep -q 'install=STARTED' "$OUT/javaselftest.log" || { echo "Install button did not start the installer"; fail=1; }
grep -qiE "packageinstaller|InstallStaging|PackageInstaller" "$OUT/installer-top.txt" || { echo "installer not on screen"; fail=1; }
adb shell pidof com.tammem.htmlilaapk > /dev/null || { echo "builder app died after Install"; fail=1; }
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK
if grep -q '^OK' "$OUT/javaselftest.log"; then
  adb pull "$D/javaselftest.apk" "$OUT/javaselftest.apk" || fail=1
  adb install -r "$OUT/javaselftest.apk" > "$OUT/install-java.txt" 2>&1 || fail=1
  cat "$OUT/install-java.txt"
  ensure_running com.tammem.apps.jselftest com.tammem.apps.jselftest/com.example.jst.Main 4-java-app || fail=1
  rm -f "$OUT/javaselftest.apk"
else
  echo "offline java self test failed"; fail=1
fi

# Find a node in the current screen by text or resource-id regex and print its centre "x y".
find_xy(){
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
pat = re.compile(sys.argv[2])
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    r = re.search(r' resource-id="([^"]*)"', n)
    d = re.search(r' content-desc="([^"]*)"', n)
    if (t and pat.search(t.group(1))) or (r and pat.search(r.group(1))) or (d and pat.search(d.group(1))):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}

echo "== video player (Java, built offline on the device) with videos on the phone"
adb shell mkdir -p /sdcard/Movies /sdcard/DCIM/Camera
adb push ci/fixtures/sample.mp4 /sdcard/Movies/sample_video.mp4 >/dev/null
adb push ci/fixtures/sample.mp4 /sdcard/DCIM/Camera/clip_two.mp4 >/dev/null
adb shell content call --uri content://media/external/file --method scan_volume --arg external_primary >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/sample_video.mp4 >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/clip_two.mp4 >/dev/null 2>&1 || true
DREAL=/storage/emulated/0/Android/data/com.tammem.htmlilaapk/files
adb push ci/fixtures/VideoPlayer.java "$D/VideoPlayer.java" > "$OUT/vp-push.txt" 2>&1
adb shell ls -la "$D/VideoPlayer.java" >> "$OUT/vp-push.txt" 2>&1
adb shell rm -f "$D/javaselftest.done" "$D/javaselftest.apk"
adb shell am force-stop com.tammem.htmlilaapk
adb shell am start -W -n com.tammem.htmlilaapk/.MainActivity --ez javaselftest true \
  --es code_file "$DREAL/VideoPlayer.java" --es app_pkg com.tammem.apps.vplayer --es app_name VPlayer >/dev/null
for i in $(seq 1 120); do
  adb shell "test -f $D/javaselftest.done" && break
  sleep 2
done
adb shell cat "$D/javaselftest.log" > "$OUT/vplayer-build.log" 2>&1
cat "$OUT/vplayer-build.log"
if grep -q '^OK' "$OUT/vplayer-build.log"; then
  adb pull "$D/javaselftest.apk" "$OUT/vplayer.apk" >/dev/null || fail=1
  adb install -r "$OUT/vplayer.apk" > "$OUT/install-vplayer.txt" 2>&1 || fail=1
  cat "$OUT/install-vplayer.txt"
  adb shell dumpsys package com.tammem.apps.vplayer | grep -iE "READ_MEDIA_VIDEO|READ_EXTERNAL_STORAGE|perm\.slot" > "$OUT/vplayer-perms.txt"
  cat "$OUT/vplayer-perms.txt"
  grep -q "READ_MEDIA_VIDEO" "$OUT/vplayer-perms.txt" || { echo "READ_MEDIA_VIDEO not declared"; fail=1; }

  # 1) first launch asks for the permission
  adb shell am start -n com.tammem.apps.vplayer/com.tammem.vplayer.VideoPlayer >/dev/null
  sleep 5
  adb exec-out screencap -p > "$OUT/5a-permission.png"
  adb shell dumpsys activity activities | grep -E "topResumedActivity" | head -1 > "$OUT/vplayer-top.txt"
  cat "$OUT/vplayer-top.txt"
  grep -qi "GrantPermissions" "$OUT/vplayer-top.txt" || { echo "no permission dialog"; fail=1; }
  # 2) tap "Allow all" (fallback: grant from the shell)
  xy=$(find_xy "permission_allow_all_button|^Allow all$|^Allow$|permission_allow_button")
  echo "allow at: $xy"
  if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell pm grant com.tammem.apps.vplayer android.permission.READ_MEDIA_VIDEO; fi
  sleep 5
  adb exec-out screencap -p > "$OUT/5b-library.png"
  # 3) the library lists both videos
  find_xy "sample_video" > "$OUT/vplayer-row.txt"
  grep -q "sample_video" "$OUT/ui.xml" || { echo "library does not show sample_video"; fail=1; }
  grep -q "clip_two" "$OUT/ui.xml" || { echo "library does not show clip_two"; fail=1; }
  # 4) tap a video, it plays
  xy=$(cat "$OUT/vplayer-row.txt")
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    sleep 3
    adb exec-out screencap -p > "$OUT/5c-player.png"
    sleep 3
    adb exec-out screencap -p > "$OUT/5d-playing.png"
    # uiautomator can't read a screen that keeps changing, so prove playback by
    # comparing the video area of two screenshots taken 3 s apart.
    python3 - "$OUT/5c-player.png" "$OUT/5d-playing.png" > "$OUT/vplayer-motion.txt" <<'PY2'
import sys
from PIL import Image, ImageChops, ImageStat
a, b = (Image.open(p).convert("L") for p in sys.argv[1:3])
w, h = a.size
box = (0, int(h * 0.30), w, int(h * 0.62))
diff = ImageStat.Stat(ImageChops.difference(a.crop(box), b.crop(box))).mean[0]
print("frame difference", round(diff, 2))
sys.exit(0 if diff > 2 else 1)
PY2
    cat "$OUT/vplayer-motion.txt"
    grep -q "frame difference" "$OUT/vplayer-motion.txt" && tail -1 "$OUT/vplayer-motion.txt" | python3 -c "import sys;v=float(sys.stdin.read().split()[-1]);sys.exit(0 if v>2 else 1)" || { echo "video is not playing"; fail=1; }
  else
    echo "could not find the video row"; fail=1
  fi
  adb shell pidof com.tammem.apps.vplayer > "$OUT/pid-vplayer.txt" || { echo "video player died"; fail=1; }
  rm -f "$OUT/vplayer.apk"
else
  echo "video player offline build failed"; fail=1
fi

echo "== gallery (Java, built offline on the device) with photos in several folders"
adb shell mkdir -p /sdcard/DCIM/Camera /sdcard/Pictures/Screenshots "/sdcard/Pictures/WhatsApp Images" /sdcard/Download
adb push ci/fixtures/photos/cam_1.jpg /sdcard/DCIM/Camera/cam_1.jpg >/dev/null
adb push ci/fixtures/photos/cam_2.jpg /sdcard/DCIM/Camera/cam_2.jpg >/dev/null
adb push ci/fixtures/photos/shot_1.jpg /sdcard/Pictures/Screenshots/shot_1.jpg >/dev/null
adb push ci/fixtures/photos/wa_1.jpg "/sdcard/Pictures/WhatsApp Images/wa_1.jpg" >/dev/null
adb push ci/fixtures/photos/dl_1.jpg /sdcard/Download/dl_1.jpg >/dev/null
adb shell content call --uri content://media/external/file --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 3
adb push ci/fixtures/Gallery.java "$D/Gallery.java" >/dev/null
adb shell rm -f "$D/javaselftest.done" "$D/javaselftest.apk"
adb shell am force-stop com.tammem.htmlilaapk
adb shell am start -W -n com.tammem.htmlilaapk/.MainActivity --ez javaselftest true \
  --es code_file "$DREAL/Gallery.java" --es app_pkg com.tammem.apps.gallery --es app_name Gallery >/dev/null
for i in $(seq 1 120); do
  adb shell "test -f $D/javaselftest.done" && break
  sleep 2
done
adb shell cat "$D/javaselftest.log" > "$OUT/gallery-build.log" 2>&1
cat "$OUT/gallery-build.log"
if grep -q '^OK' "$OUT/gallery-build.log"; then
  adb pull "$D/javaselftest.apk" "$OUT/gallery.apk" >/dev/null || fail=1
  adb install -r "$OUT/gallery.apk" > "$OUT/install-gallery.txt" 2>&1 || fail=1
  adb shell dumpsys package com.tammem.apps.gallery | grep -iE "READ_MEDIA_IMAGES" > "$OUT/gallery-perms.txt"
  grep -q "READ_MEDIA_IMAGES" "$OUT/gallery-perms.txt" || { echo "READ_MEDIA_IMAGES not declared"; fail=1; }

  adb shell am start -n com.tammem.apps.gallery/com.tammem.gallery.Gallery >/dev/null
  sleep 5
  adb exec-out screencap -p > "$OUT/6a-gallery-permission.png"
  adb shell dumpsys activity activities | grep -E "topResumedActivity" | head -1 > "$OUT/gallery-top.txt"
  grep -qi "GrantPermissions" "$OUT/gallery-top.txt" || { echo "gallery: no permission dialog"; fail=1; }
  xy=$(find_xy "permission_allow_all_button|^Allow all$|^Allow$|permission_allow_button")
  if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell pm grant com.tammem.apps.gallery android.permission.READ_MEDIA_IMAGES; fi
  sleep 5
  adb exec-out screencap -p > "$OUT/6b-albums.png"
  find_xy "^Camera$" > "$OUT/gallery-camera.txt"
  cp "$OUT/ui.xml" "$OUT/ui-albums.xml"
  for a in Camera Screenshots "WhatsApp Images" Download; do
    grep -q "$a" "$OUT/ui-albums.xml" || { echo "gallery: album $a missing"; fail=1; }
  done
  xy=$(cat "$OUT/gallery-camera.txt")
  if [ -n "$xy" ]; then
    adb shell input tap $xy
    sleep 3
    adb exec-out screencap -p > "$OUT/6c-album-grid.png"
    xy=$(find_xy "^photo cam_")
    cp "$OUT/ui.xml" "$OUT/ui-grid.xml"
    # uiautomator sometimes drops a grid cell from its dump; the swipe below proves both photos.
    grep -q "photo cam_" "$OUT/ui-grid.xml" && grep -q "2 صورة" "$OUT/ui-grid.xml" || { echo "gallery: Camera grid missing"; fail=1; }
    if [ -n "$xy" ]; then
      adb shell input tap $xy
      sleep 3
      adb exec-out screencap -p > "$OUT/6d-viewer.png"
      find_xy "^cam_" > /dev/null; cp "$OUT/ui.xml" "$OUT/ui-viewer.xml"
      first=$(grep -o 'text="cam_[12].jpg"' "$OUT/ui-viewer.xml" | head -1)
      echo "viewer shows: $first"
      [ -n "$first" ] || { echo "gallery: viewer title missing"; fail=1; }
      # swipe toward the other photo: from 1/2 go next (left), from 2/2 go back (right)
      if grep -q 'text="1 / 2' "$OUT/ui-viewer.xml"; then adb shell input swipe 950 1200 120 1200 120
      else adb shell input swipe 120 1200 950 1200 120; fi
      sleep 2
      find_xy "^cam_" > /dev/null; cp "$OUT/ui.xml" "$OUT/ui-viewer2.xml"
      second=$(grep -o 'text="cam_[12].jpg"' "$OUT/ui-viewer2.xml" | head -1)
      echo "after swipe: $second"
      [ -n "$second" ] && [ "$second" != "$first" ] || { echo "gallery: swipe did not change photo"; fail=1; }
      grep -q 'text="cam_1.jpg"' "$OUT/ui-viewer.xml" "$OUT/ui-viewer2.xml" && grep -q 'text="cam_2.jpg"' "$OUT/ui-viewer.xml" "$OUT/ui-viewer2.xml" || { echo "gallery: both Camera photos not seen"; fail=1; }
      adb exec-out screencap -p > "$OUT/6e-viewer-swiped.png"
    else
      echo "gallery: no photo cell"; fail=1
    fi
  else
    echo "gallery: Camera album not found"; fail=1
  fi
  adb shell pidof com.tammem.apps.gallery > "$OUT/pid-gallery.txt" || { echo "gallery died"; fail=1; }
  rm -f "$OUT/gallery.apk"
else
  echo "gallery offline build failed"; fail=1
fi

adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb logcat -d '*:E' | grep -iE "tammem|webrunner|AndroidRuntime|AppMaker" > "$OUT/errors.txt" 2>&1
rm -f "$OUT/selftest.apk"
echo "fail=$fail" > "$OUT/result.txt"
cat "$OUT/crash.txt"
exit $fail
