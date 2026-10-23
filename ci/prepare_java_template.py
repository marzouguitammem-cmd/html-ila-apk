#!/usr/bin/env python3
"""Copies the built javarunner APK into the app assets as java_template.apk and writes
java_template_info.properties (package, label/activity placeholders, icon path)."""
import os, re, shutil, subprocess, sys

APK = "javarunner/build/outputs/apk/release/javarunner-release-unsigned.apk"
ASSETS = "app/src/main/assets"
PACKAGE = "com.tammem.javarunner"
LABEL = "AppMakerJavaLabel_9c21f0"
ACTIVITY = "com.tammem.javarunner.MainPlaceholder"
VERSION_NAME = "0.0.0-appmaker-java-template"


def tool(name):
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    bt = os.path.join(root, "build-tools")
    v = sorted(os.listdir(bt), key=lambda s: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", s)])
    return os.path.join(bt, v[-1], name)


def main():
    if not os.path.isfile(APK):
        sys.exit("javarunner APK not found: " + APK)
    aapt2 = tool("aapt2")
    res = subprocess.check_output([aapt2, "dump", "resources", APK], text=True)
    icon = None
    lines = res.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"\bdrawable/app_icon_bg\b", line):
            for nxt in lines[i + 1:i + 6]:
                m = re.search(r"\(file\)\s+(\S+)", nxt)
                if m:
                    icon = m.group(1); break
            break
    if not icon:
        print(res); sys.exit("icon path not found")
    manifest = subprocess.check_output([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", APK], text=True)
    for needed in (PACKAGE, LABEL, ACTIVITY):
        if needed not in manifest:
            print(manifest); sys.exit("placeholder missing: " + needed)
    os.makedirs(ASSETS, exist_ok=True)
    shutil.copyfile(APK, os.path.join(ASSETS, "java_template.apk"))
    with open(os.path.join(ASSETS, "java_template_info.properties"), "w", encoding="utf-8") as f:
        f.write("package=%s\nlabel=%s\nactivity=%s\nversionName=%s\niconBg=%s\n" % (PACKAGE, LABEL, ACTIVITY, VERSION_NAME, icon))
    print("java template ready, icon at", icon)


if __name__ == "__main__":
    main()
