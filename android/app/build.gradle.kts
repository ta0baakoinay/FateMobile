plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fatemmo.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fatemmo.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Phase 0/1: native lib only exposes a stub version check to prove the
            // NDK/CMake toolchain is wired end-to-end. Real engine code (renderer,
            // networking, protocol) lands starting Phase 3 per docs/FATE_MMO_MOBILE_ROADMAP.md.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            buildConfigField("String", "SERVER_CONFIG_ASSET", "\"server_config_development.json\"")
            applicationIdSuffix = ".dev"
        }
        create("staging") {
            dimension = "environment"
            buildConfigField("String", "SERVER_CONFIG_ASSET", "\"server_config_staging.json\"")
            applicationIdSuffix = ".staging"
        }
        create("production") {
            dimension = "environment"
            buildConfigField("String", "SERVER_CONFIG_ASSET", "\"server_config_production.json\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            // native/ lives at the repository root (see docs/FATE_MMO_MOBILE_ARCHITECTURE.md §10),
            // shared conceptually across any future non-Android target, not nested under android/.
            path = file("../../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
