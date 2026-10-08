plugins { id("com.android.application") }
android {
    namespace = "hu.hu15"
    compileSdk = 36
    defaultConfig {
        applicationId = "hu.hu15"
        minSdk = 15
        targetSdk = 15
        versionCode = 19
        versionName = "m4p"
        ndk { abiFilters += listOf("armeabi-v7a", "x86") } // x86 only for the emulator
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    lint { abortOnError = false }
}
dependencies {
    implementation("org.conscrypt:conscrypt-android:2.5.3") // same TLS provider HeadUnit Revived uses
}
