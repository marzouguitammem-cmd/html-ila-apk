#!/usr/bin/env python3
"""Copies the built runner APK into the builder's assets as template.apk and writes
template_info.properties (package, placeholders and the icon file path inside the APK)."""
import os
import re
import shutil
import subprocess
import sys

APK = "runner/build/outputs/apk/release/runner-release-unsigned.apk"
ASSETS = "app/src/main/assets"
PACKAGE = "com.tammem.webrunner"
LABEL = "AppMakerLabelPlaceholder_7f3c9a1e"
VERSION_NAME = "0.0.0-appmaker-template-version"


def tool(name):
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    bt = os.path.join(root, "build-tools")
    versions = sorted(os.listdir(bt), key=lambda v: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", v)])
    return os.path.join(bt, versions[-1], name)


def main():
    if not os.path.isfile(APK):
        sys.exit("runner APK not found: " + APK)
    aapt2 = tool("aapt2")
    res = subprocess.check_output([aapt2, "dump", "resources", APK], text=True)
    icon = None
    lines = res.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"\bdrawable/app_icon_bg\b", line):
            for nxt in lines[i + 1:i + 6]:
                m = re.search(r"\(file\)\s+(\S+)", nxt)
                if m:
                    icon = m.group(1)
                    break
            break
    if not icon:
        print(res)
        sys.exit("icon path for drawable/app_icon_bg not found")

    manifest = subprocess.check_output([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", APK], text=True)
    print(manifest)
    for needed in (PACKAGE, LABEL, VERSION_NAME):
        if needed not in manifest:
            sys.exit("placeholder missing from manifest: " + needed)
    if "permission" in manifest and "DYNAMIC_RECEIVER" in manifest:
        sys.exit("template declares a package-scoped permission; generated apps would clash")

    os.makedirs(ASSETS, exist_ok=True)
    shutil.copyfile(APK, os.path.join(ASSETS, "template.apk"))
    with open(os.path.join(ASSETS, "template_info.properties"), "w", encoding="utf-8") as f:
        f.write("package=%s\nlabel=%s\nversionName=%s\niconBg=%s\n" % (PACKAGE, LABEL, VERSION_NAME, icon))
    print("template ready, icon at", icon, "size", os.path.getsize(APK))


if __name__ == "__main__":
    main()
