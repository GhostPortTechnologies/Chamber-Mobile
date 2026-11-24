plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Push needs a Firebase config that knows the debug app id (….chamber.dev). Without one the app builds and works,
// with push off. The config is a GitHub secret, never in the repo.
val firebaseConfig = file("google-services.json")
if (firebaseConfig.exists() && firebaseConfig.readText().contains("\"com.ghostporttechnologies.chamber.dev\"")) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("No Firebase config for com.ghostporttechnologies.chamber.dev: this build has push off.")
}

android {
    namespace = "com.ghostporttechnologies.chamber"
    compileSdk = 36

    defaultConfig {
        // The same id as the store app, so the native app will replace the Capacitor one when it ships.
        applicationId = "com.ghostporttechnologies.chamber"
        minSdk = 26
        targetSdk = 36
        // CI numbers every build so each test APK installs as an update over the last one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2.0-native"
    }

    signingConfigs {
        // One stable test key from GitHub secrets (never in the repo). Without it, Gradle's own debug key.
        create("chamberTest") {
            System.getenv("ANDROID_TEST_KEYSTORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_TEST_KEYSTORE_PASSWORD")
                keyAlias = "chamber-test"
                keyPassword = System.getenv("ANDROID_TEST_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the Capacitor stopgap app instead of replacing it while v1 is being built.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            if (System.getenv("ANDROID_TEST_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("chamberTest")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0") // activity detection for driving mode (T-0321)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
