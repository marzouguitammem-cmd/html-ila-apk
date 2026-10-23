// The "javarunner" is a minimal, pre-compiled Android app that only carries valid
// resources (icon, theme, app_name) and a manifest skeleton. The offline Java
// engine replaces its classes.dex with the user's compiled code and patches the
// manifest strings (package, label, launcher activity name), then re-signs.
plugins { id("com.android.application") }
android {
    namespace = "com.tammem.javarunner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tammem.javarunner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.0-appmaker-java-template"
    }
    buildTypes { release { isMinifyEnabled = false; isShrinkResources = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
