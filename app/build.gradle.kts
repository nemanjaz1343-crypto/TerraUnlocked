plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.terraunlocked"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.terraunlocked"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.3.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
