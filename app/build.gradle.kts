plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.chatlens"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.chatlens"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "0.3.0-mvp"
        // Target device Xiaomi 15 Ultra is arm64; this saves APK size (LiteRT-LM, ML Kit native libs)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Two outputs: "full" (direct distribution, with API mode) and "play" (without API mode, without cleartext traffic). See PLAN.md section 28.
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            buildConfigField("boolean", "API_MODE", "true")
            manifestPlaceholders["cleartext"] = "true"
        }
        create("play") {
            dimension = "dist"
            applicationIdSuffix = ""
            buildConfigField("boolean", "API_MODE", "false")
            manifestPlaceholders["cleartext"] = "false"
        }
    }
    defaultConfig.buildConfigField("String", "PRIVACY_URL", "\"${(project.findProperty("privacyUrl") as String?) ?: ""}\"")

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // LiteRT-LM and ML Kit ship native libs; they must stay unpacked and aligned
        jniLibs.useLegacyPackaging = false
        // sherpa-onnx: the JNI library depends only on libonnxruntime (readelf NEEDED) and loads only "sherpa-onnx-jni"; the C and C++ APIs are not needed (saves 4.9 MB)
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Linux library of sherpa-onnx only for JVM tests (ONNX runtime and JNI); it is not packed into the APK
val sherpaNativeTest by configurations.creating
val extractSherpaNative by tasks.registering(Copy::class) {
    from(sherpaNativeTest.elements.map { fs -> fs.map { zipTree(it.asFile) } }) {
        include("sherpa-onnx/native/linux-x64/*.so")
        eachFile { path = name }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("sherpa-native"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractSherpaNative)
    val dir = layout.buildDirectory.dir("sherpa-native").get().asFile.absolutePath
    jvmArgs("-Djava.library.path=$dir")
    environment("LD_LIBRARY_PATH", dir)
}

dependencies {
    sherpaNativeTest("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-native-lib-linux-x64:v1.13.8")
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // OCR for text in images (bundled, Latin script, offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Local LLM (Qwen3-0.6B as .litertlm)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    // Speech recognition for voice messages: sherpa-onnx (Parakeet TDT 0.6B v3 INT8), arm64 only because of the ABI filter above
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    // Opus decoding in pure Java (BSD-3-Clause), fallback to MediaExtractor/MediaCodec
    implementation("io.github.jaredmdobson:concentus:1.0.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // UI render without an emulator (Robolectric, native graphics mode)
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
