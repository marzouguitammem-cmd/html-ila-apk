plugins { id("com.android.application") }
android {
    namespace = "com.tammem.offlineprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tammem.offlineprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    androidResources { noCompress += listOf("jar", "dex") }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
