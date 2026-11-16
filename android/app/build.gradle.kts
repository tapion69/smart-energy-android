plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Stable APK signing is enabled only when all four secrets are configured
// in GitHub Actions or injected by the local developer. Never commit the key.
val signingStorePath = System.getenv("SMART_ENERGY_ANDROID_KEYSTORE_FILE")
val signingStorePassword = System.getenv("SMART_ENERGY_ANDROID_STORE_PASSWORD")
val signingKeyAlias = System.getenv("SMART_ENERGY_ANDROID_KEY_ALIAS")
val signingKeyPassword = System.getenv("SMART_ENERGY_ANDROID_KEY_PASSWORD")
val hasStableSigning = !signingStorePath.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "fr.smartenergy.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.smartenergy.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 65
        versionName = "0.3.51-dev"
    }
    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            // Only release builds with the permanent signing key may upgrade the official app.
            applicationIdSuffix = ".debug"
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
