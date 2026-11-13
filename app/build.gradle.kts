plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zlatan.gamelens"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zlatan.gamelens"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("unified") {
            storeFile = file("keystore/gamelens-unified.jks")
            storePassword = "gamelens2026"
            keyAlias = "gamelens"
            keyPassword = "gamelens2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("unified")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("unified")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libonnxruntime.so", "**/libsherpa-onnx-jni.so", "**/*.so")
        }
    }
}

dependencies {
    // Offline Speech Recognition (Sherpa-ONNX prebuilt AAR)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Archive Extraction (tar.bz2 for offline ASR model unpack)
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")

    // AndroidX & UI
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Lifecycle & Service
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ML Kit - Text Recognition (OCR) & Translation
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")

    // Room Database & Caching
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Networking (REST API for translation fallback)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
