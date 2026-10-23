#!/usr/bin/env python3
"""Turns a job (job.json [+ src.zip]) into a ready-to-build Gradle project.

The app writes a job under jobs/<id>/. Two kinds:
  type "single" : plain Java/Kotlin sources, scaffolded around a template manifest
                  whose permissions and launcher activity come from job.json.
  type "gradle" : a full Android Studio project in project.zip, built as-is.

This script prepares build-dir/ for ci/build_job.sh.
"""
import base64
import json
import os
import re
import shutil
import sys
import zipfile

# .../cloud/ci/scaffold.py  -> CLOUD = .../cloud , REPO = repo root
CLOUD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(CLOUD)
TEMPLATE = os.path.join(CLOUD, "template")
KEYSTORE = os.path.join(CLOUD, "keystore", "appmaker.jks")

PERMS = {
    "INTERNET": "android.permission.INTERNET",
    "NETWORK_STATE": "android.permission.ACCESS_NETWORK_STATE",
    "WIFI_STATE": "android.permission.ACCESS_WIFI_STATE",
    "STORAGE_READ": "android.permission.READ_EXTERNAL_STORAGE",
    "STORAGE_WRITE": "android.permission.WRITE_EXTERNAL_STORAGE",
    "MANAGE_STORAGE": "android.permission.MANAGE_EXTERNAL_STORAGE",
    "CAMERA": "android.permission.CAMERA",
    "MICROPHONE": "android.permission.RECORD_AUDIO",
    "LOCATION": "android.permission.ACCESS_FINE_LOCATION",
    "LOCATION_COARSE": "android.permission.ACCESS_COARSE_LOCATION",
    "VIBRATE": "android.permission.VIBRATE",
    "WAKE_LOCK": "android.permission.WAKE_LOCK",
    "NOTIFICATIONS": "android.permission.POST_NOTIFICATIONS",
    "INSTALL_APPS": "android.permission.REQUEST_INSTALL_PACKAGES",
    "FOREGROUND_SERVICE": "android.permission.FOREGROUND_SERVICE",
    "BLUETOOTH_CONNECT": "android.permission.BLUETOOTH_CONNECT",
}
PKG_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")


def die(msg):
    print("SCAFFOLD_ERROR: " + msg)
    sys.exit(2)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def unzip_into(zip_path, dest):
    base = os.path.realpath(dest)
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            name = info.filename.replace("\\", "/")
            if name.startswith("__MACOSX/") or name.endswith(".DS_Store"):
                continue
            target = os.path.realpath(os.path.join(dest, name))
            if not target.startswith(base):
                continue
            if info.is_dir():
                os.makedirs(target, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(info) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)


def find_gradle_root(start):
    """The shallowest folder containing settings.gradle(.kts)."""
    best = None
    for cur, dirs, files in os.walk(start):
        dirs[:] = [d for d in dirs if d not in ("__MACOSX",)]
        if any(f in ("settings.gradle", "settings.gradle.kts") for f in files):
            depth = cur[len(start):].count(os.sep)
            if best is None or depth < best[0]:
                best = (depth, cur)
    return best[1] if best else None


def guess_main_activity(java_root, package):
    """Finds a class that extends an Activity, for the launcher entry."""
    cand = None
    for cur, _dirs, files in os.walk(java_root):
        for f in files:
            if not (f.endswith(".java") or f.endswith(".kt")):
                continue
            text = open(os.path.join(cur, f), encoding="utf-8", errors="ignore").read()
            m = re.search(r"\bpackage\s+([\w.]+)", text)
            pkg = m.group(1) if m else package
            for cm in re.finditer(r"\b(?:class|object)\s+(\w+)\s*(?:\(.*?\))?\s*:?\s*[^{]*\bActivity\b", text):
                name = pkg + "." + cm.group(1)
                if re.search(r"\.MAIN\b", text) or "Main" in cm.group(1):
                    return name
                cand = cand or name
    return cand


def scaffold_single(job, job_dir, build_dir):
    app_name = (job.get("appName") or "").strip() or die("appName فارغ")
    package = (job.get("packageName") or "").strip()
    if not PKG_RE.match(package):
        die("packageName غير صحيح: " + package)
    version_code = int(job.get("versionCode") or 1)
    min_sdk = int(job.get("minSdk") or 24)
    target_sdk = int(job.get("targetSdk") or 34)
    lang = job.get("lang") or "java"

    shutil.copytree(TEMPLATE, build_dir)
    app = os.path.join(build_dir, "app")
    java_root = os.path.join(app, "src", "main", "java")
    os.makedirs(java_root, exist_ok=True)

    # sources: inline files first, then an optional src.zip overlay
    for rel, content in (job.get("files") or {}).items():
        rel = rel.replace("\\", "/").lstrip("/")
        if ".." in rel:
            continue
        dest = os.path.join(app, "src", "main", rel) if rel.startswith(("res/", "assets/")) \
            else os.path.join(java_root, rel)
        write(dest, content)
    src_zip = os.path.join(job_dir, "src.zip")
    if os.path.isfile(src_zip):
        unzip_into(src_zip, os.path.join(app, "src", "main"))

    has_java = any(f.endswith((".java", ".kt")) for _r, _d, fs in os.walk(java_root) for f in fs)
    if not has_java:
        die("ما فماش حتى ملف Java/Kotlin")

    main_activity = (job.get("mainActivity") or "").strip() or guess_main_activity(java_root, package)
    if not main_activity:
        die("ما لقيتش Activity رئيسية — زيد mainActivity في job.json")
    if "." not in main_activity:
        main_activity = package + "." + main_activity

    perms = "\n".join(
        '    <uses-permission android:name="%s" />' % PERMS.get(p, p)
        for p in (job.get("permissions") or [])
    )
    manifest = TEMPLATE_MANIFEST.format(perms=perms, activity=main_activity)
    write(os.path.join(app, "src", "main", "AndroidManifest.xml"), manifest)

    extra_deps = "\n".join("    implementation(\"%s\")" % d for d in (job.get("deps") or []))
    plugins = 'id("org.jetbrains.kotlin.android")' if lang == "kotlin" else ""
    kotlin_block = KOTLIN_BLOCK if lang == "kotlin" else ""
    write(os.path.join(app, "build.gradle.kts"), APP_GRADLE.format(
        package=package, version_code=version_code, version_name="1.%d" % version_code,
        min_sdk=min_sdk, target_sdk=target_sdk, kotlin_plugin=plugins,
        kotlin_block=kotlin_block, extra_deps=extra_deps))
    write(os.path.join(build_dir, "settings.gradle.kts"), SETTINGS)
    write(os.path.join(build_dir, "build.gradle.kts"), ROOT_GRADLE.format(
        kotlin=('    id("org.jetbrains.kotlin.android") version "2.1.21" apply false' if lang == "kotlin" else "")))
    write(os.path.join(build_dir, "gradle.properties"), GRADLE_PROPS)
    write(os.path.join(app, "src", "main", "res", "values", "strings.xml"),
          '<resources><string name="app_name">%s</string></resources>' % xml_escape(app_name))
    print("scaffold single ok: %s (%s) activity=%s" % (app_name, package, main_activity))


def scaffold_gradle(job, job_dir, build_dir):
    zip_path = os.path.join(job_dir, "project.zip")
    if not os.path.isfile(zip_path):
        die("type=gradle أما ما فماش project.zip")
    unzip_into(zip_path, build_dir)
    root = find_gradle_root(build_dir)
    if not root:
        die("ما لقيتش settings.gradle في المشروع")
    if root != build_dir:
        # move the real project root up
        tmp = build_dir + "_root"
        shutil.move(root, tmp)
        shutil.rmtree(build_dir)
        shutil.move(tmp, build_dir)
    # make sure a wrapper exists
    if not os.path.isfile(os.path.join(build_dir, "gradlew")):
        for name in ("gradlew", "gradlew.bat"):
            shutil.copy(os.path.join(TEMPLATE, name), os.path.join(build_dir, name))
        os.makedirs(os.path.join(build_dir, "gradle", "wrapper"), exist_ok=True)
        for name in ("gradle-wrapper.jar", "gradle-wrapper.properties"):
            shutil.copy(os.path.join(TEMPLATE, "gradle", "wrapper", name),
                        os.path.join(build_dir, "gradle", "wrapper", name))
    print("scaffold gradle ok at", build_dir)


def xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


SETTINGS = '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "app"
include(":app")
'''

ROOT_GRADLE = '''plugins {{
    id("com.android.application") version "8.13.0" apply false
{kotlin}
}}
'''

GRADLE_PROPS = "org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\n"

KOTLIN_BLOCK = '''
kotlin { jvmToolchain(17) }'''

APP_GRADLE = '''plugins {{
    id("com.android.application")
    {kotlin_plugin}
}}
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "{version_code}").toInt()
android {{
    namespace = "{package}"
    compileSdk = 35
    defaultConfig {{
        applicationId = "{package}"
        minSdk = {min_sdk}
        targetSdk = {target_sdk}
        versionCode = {version_code}
        versionName = "{version_name}"
    }}
    signingConfigs {{
        create("release") {{
            storeFile = rootProject.file("appmaker.jks")
            storeType = "pkcs12"
            storePassword = "appmaker2026"
            keyAlias = "appmaker"
            keyPassword = "appmaker2026"
        }}
    }}
    buildTypes {{
        release {{ isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") }}
        debug {{ signingConfig = signingConfigs.getByName("release") }}
    }}
    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}
    lint {{ checkReleaseBuilds = false; abortOnError = false }}
}}{kotlin_block}
dependencies {{
{extra_deps}
}}
'''

TEMPLATE_MANIFEST = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
{perms}
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name="{activity}"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
'''


def main():
    job_dir = sys.argv[1]
    build_dir = sys.argv[2]
    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    job = json.load(open(os.path.join(job_dir, "job.json"), encoding="utf-8"))
    if job.get("type") == "gradle":
        scaffold_gradle(job, job_dir, build_dir)
    else:
        scaffold_single(job, job_dir, build_dir)
    # the signing key is copied next to the project root for both kinds
    shutil.copy(KEYSTORE, os.path.join(build_dir, "appmaker.jks"))


if __name__ == "__main__":
    main()
