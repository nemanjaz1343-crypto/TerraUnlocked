plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.terraunlocked"
    compileSdk = 35
    signingConfigs {
        create("stableRelease") {
            val storePath = System.getenv("TERRA_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("TERRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TERRA_KEY_ALIAS")
                keyPassword = System.getenv("TERRA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("TERRA_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
            isMinifyEnabled = false
        }
    }
    defaultConfig {
        applicationId = "com.terraunlocked"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.4.5"
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
