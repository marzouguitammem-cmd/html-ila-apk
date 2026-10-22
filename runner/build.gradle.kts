// The "runner" is a tiny WebView app. It is never installed as-is: it is packed
// inside صانع التطبيقات as template.apk, and every app the user makes is a copy
// of it with a new name, package, icon and web files, signed on the phone.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.tammem.webrunner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tammem.webrunner"
        minSdk = 26
        // 34 keeps the classic (non edge-to-edge) window on Android 15+, so pages
        // never slide under the status bar.
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.0-appmaker-template-version"
    }

    buildTypes {
        release {
            // No shrinking and no signing: the builder re-signs every copy.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
