import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing material lives outside the repo. Absent on a fresh clone, in which case
// the release variant falls back to unsigned rather than failing the build --
// a contributor should be able to compile without being handed a key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        // Universal: every Android ABI in one APK, so a single file installs on an
        // armv7 projector and an arm64 box alike. The cost is size -- each ABI
        // carries its own ~7 MB Go server -- but a per-ABI split would mean
        // picking the right file by hand at sideload time, which is worse.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }

    // Load-bearing: a library compressed inside the APK is not a file on disk and
    // cannot be executed. Measured -- without this, exec from nativeLibraryDir fails.
    buildFeatures { buildConfig = true }

    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: the AccessibilityService is instantiated by
            // the system from a manifest name, and shrinking without verified
            // keep-rules is a good way to ship an app whose service never binds.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
