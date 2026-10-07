plugins { id("com.android.application") }
android {
    namespace = "hu.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "hu.probe"
        minSdk = 14
        targetSdk = 15
        versionCode = 1
        versionName = "m0b"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    lint { abortOnError = false }
}
