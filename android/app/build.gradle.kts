plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "fr.smartenergy.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.smartenergy.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.2-dev"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
