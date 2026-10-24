plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ember for Galaxy Watch: remote controls for the phone app. It shares the phone app's
// package name and signing key, which is what lets the two talk over Wear OS.
android {
    namespace = "app.ember.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ember.audiobooks"
        minSdk = 30
        targetSdk = 34
        // Release numbers carry on from the old private repo (Ember-19), so phones accept the update.
        val run = (System.getenv("EMBER_BUILD") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.0.$run"
    }

    signingConfigs {
        create("release") {
            // The key and its passwords come from the repo's GitHub secrets (never in the code).
            storeFile = file(System.getenv("EMBER_KEYSTORE_PATH") ?: "../ember-release.jks")
            storePassword = System.getenv("EMBER_STORE_PASSWORD") ?: ""
            keyAlias = "ember"
            keyPassword = System.getenv("EMBER_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
