#!/bin/bash
# Builds one job: scaffold -> gradle -> re-sign with the shared key -> release.
# Usage: cloud/ci/build_job.sh <jobId>    (run from the repo root)
set -u
ID="$1"
CLOUD="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$CLOUD/.." && pwd)"
JOB="$REPO/jobs/$ID"
BUILD="$REPO/work/$ID"
OUT="$REPO/out"
mkdir -p "$OUT" "$REPO/work"
LOG="$REPO/work/$ID.log"
status="error"; apkName="app.apk"; versionName=""

finish(){
  tail -c 3500 "$LOG" > "$REPO/work/$ID.tail" 2>/dev/null || true
  python3 - "$JOB/result.json" "$status" "$ID" "$versionName" "$REPO/work/$ID.tail" <<'PY'
import json,sys
path,status,jid,vname,tailf=sys.argv[1:6]
tail=""
try: tail=open(tailf,encoding="utf-8",errors="ignore").read()
except Exception: pass
json.dump({"status":status,"job":jid,"release":"job-"+jid,"apk":"app.apk",
           "versionName":vname,"log":tail[-3000:]}, open(path,"w",encoding="utf-8"), ensure_ascii=False)
print("result.json written:",status)
PY
}
trap finish EXIT

{
  echo "=== scaffold job $ID ==="
  python3 "$CLOUD/ci/scaffold.py" "$JOB" "$BUILD" || exit 1
  cd "$BUILD" || exit 1
  chmod +x gradlew 2>/dev/null
  echo "=== gradle build ==="
  if ./gradlew --no-daemon --stacktrace assembleRelease ; then
    MODE=release
  else
    echo "=== release failed, trying debug ==="
    ./gradlew --no-daemon --stacktrace assembleDebug || exit 1
    MODE=debug
  fi
  APK=$(find . -path "*/outputs/apk/*" -name "*.apk" | grep -i "$MODE" | head -1)
  [ -z "$APK" ] && APK=$(find . -path "*/outputs/apk/*" -name "*.apk" | head -1)
  [ -z "$APK" ] && { echo "no APK produced"; exit 1; }
  echo "built: $APK"

  BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
  "$BT/zipalign" -f -p 4 "$APK" "$OUT/aligned.apk" || cp "$APK" "$OUT/aligned.apk"
  "$BT/apksigner" sign --ks "$CLOUD/keystore/appmaker.jks" --ks-pass pass:appmaker2026 \
      --ks-key-alias appmaker --key-pass pass:appmaker2026 \
      --out "$OUT/$apkName" "$OUT/aligned.apk" || exit 1
  rm -f "$OUT/aligned.apk"
  "$BT/apksigner" verify --print-certs "$OUT/$apkName" | head -3
  versionName=$("$BT/aapt2" dump badging "$OUT/$apkName" 2>/dev/null | sed -n "s/.*versionName='\\([^']*\\)'.*/\\1/p" | head -1)
  status="ok"
  echo "=== OK $apkName $versionName ==="
} >> "$LOG" 2>&1
cat "$LOG"
[ "$status" = "ok" ]
