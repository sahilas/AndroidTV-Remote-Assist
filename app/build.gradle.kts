plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.sahilas.tvassist"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.sahilas.tvassist"
        // 31 = Android 12, the version both test boxes run. Anything lower is
        // untested; anything that requires higher (GLOBAL_ACTION_DPAD_*, API 34)
        // must be feature-gated rather than raising this.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-scaffold"
        // The bundled binary must be a real file on disk for exec to work; a
        // compressed-in-APK library is not executable.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging { jniLibs { useLegacyPackaging = true } }

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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
