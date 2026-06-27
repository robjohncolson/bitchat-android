plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 35
        versionName = "1.7.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            // Separate package id so the debug build installs alongside a release-signed
            // com.bitchat.droid (e.g. a Play Store install) instead of failing on signature mismatch.
            applicationIdSuffix = ".debug"
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

configurations.all {
    // bitcoinj 0.14.7 needs guava 18, which BUNDLES com.google.common.util.concurrent.ListenableFuture;
    // some Google libs also pull the standalone com.google.guava:listenablefuture, which defines the SAME
    // class -> duplicate-class build error. guava 18 already provides it, so drop the standalone artifact.
    exclude(group = "com.google.guava", module = "listenablefuture")
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)

    // Dogecoin SPV backend (bitcoinj 0.14.7 + libdohj). See docs/dogecoin-spv-integration-plan.md.
    // 0.14.7 uses com.madgag.spongycastle (org.spongycastle.*), a SEPARATE namespace from the app's
    // org.bouncycastle:1.70 — so bitcoinj's crypto is isolated and the money-path signer
    // (DogecoinTransactionBuilder, on the app's AUDITED bcprov 1.70) is untouched; bitcoinj NEVER signs
    // (Option B). The DogecoinSignerRegressionTest still guards the app signer byte-for-byte.
    implementation(libs.libdohj)
    // bitcoinj exposes Guava (ListenableFuture via waitForPeers) in its public API but declares it
    // `implementation`; pin the version bitcoinj 0.14.7 uses.
    implementation(libs.guava)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // Arti (Tor in Rust) Android bridge - custom build from latest source
    // Built with rustls, 16KB page size support, and onio//un service client
    // Native libraries are in src/tor/jniLibs/ (extracted from arti-custom.aar)
    // Only included in tor flavor to reduce APK size for standard builds
    // Note: AAR is kept in libs/ for reference, but libraries loaded from jniLibs/

    // Google Play Services Location
    implementation(libs.gms.location)

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // EXIF orientation handling for images
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
