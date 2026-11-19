plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.aikb.ime"
    compileSdk = 35
    ndkVersion = "28.0.13004108" // must match the toolchain that built third_party/prebuilt

    defaultConfig {
        applicationId = "app.aikb.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "2.5.0"
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Until a real release key exists, sign release with the debug key so it installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // One APK per ABI (arm64 for phones, x86_64 for the emulator): each carries only its own native code.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    val embedAi = (project.findProperty("embedAi")?.toString() ?: "true").toBoolean()
    sourceSets.getByName("main") {
        if (embedAi && file("src/embeddedAiAssets/models/zh-lm-builtin.gguf").exists()) {
            assets.srcDir("src/embeddedAiAssets")
        }
    }

    androidResources {
        // Dictionaries and GGUF models are read via mmap/stream; keep them uncompressed in the APK.
        noCompress += listOf("bin", "dict", "gguf")
    }
}

dependencies {
    implementation(files("../third_party/sherpa-onnx/sherpa-onnx.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
