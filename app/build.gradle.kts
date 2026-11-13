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
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

val downloadSherpaOnnxAar = tasks.register("downloadSherpaOnnxAar") {
    val aarFile = file("libs/sherpa-onnx-1.13.5.aar")
    outputs.file(aarFile)
    doLast {
        if (!aarFile.exists()) {
            aarFile.parentFile.mkdirs()
            println("Downloading prebuilt sherpa-onnx-1.13.5.aar from GitHub releases...")
            val url = java.net.URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar").toURL()
            url.openStream().use { input ->
                aarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Downloaded sherpa-onnx-1.13.5.aar successfully (${aarFile.length()} bytes).")
        }
    }
}

tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("compile") }.configureEach {
    dependsOn(downloadSherpaOnnxAar)
}

dependencies {
    // Offline Speech Recognition (Sherpa-ONNX prebuilt AAR)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

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
