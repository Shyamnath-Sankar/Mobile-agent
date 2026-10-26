import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.shyamnathsankar.zeroclaw"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.shyamnathsankar.zeroclaw"
        minSdk = 26
        targetSdk = 36
        // CI passes the run number so every APK is a newer version than the last.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"

        // ZeroClaw only publishes an aarch64 Android binary.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Fixed debug key committed to the repo so every CI build can be installed over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // The agent is an executable shipped as libzeroclaw.so; it must be extracted
            // to nativeLibraryDir so it can be exec'd (also needs extractNativeLibs in the manifest).
            useLegacyPackaging = true
            keepDebugSymbols += "**/libzeroclaw.so"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
