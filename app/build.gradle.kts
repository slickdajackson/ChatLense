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
        // Zielgeraet Xiaomi 15 Ultra ist arm64; spart APK-Groesse (LiteRT-LM, ML Kit Native-Libs)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Zwei Ausgaben: "full" (Direktvertrieb, mit API-Modus) und "play" (ohne API-Modus, ohne Klartext-Verkehr). Siehe PLAN.md Abschnitt 28.
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
        // LiteRT-LM und ML Kit liefern native Libs; sie muessen entpackt/ausgerichtet bleiben
        jniLibs.useLegacyPackaging = false
        // sherpa-onnx: die JNI-Bibliothek haengt nur von libonnxruntime ab (readelf NEEDED) und laedt nur "sherpa-onnx-jni"; C- und C++-API werden nicht gebraucht (spart 4,9 MB)
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Linux-Bibliothek von sherpa-onnx nur fuer JVM-Tests (ONNX-Laufzeit und JNI), wird nicht in die APK gepackt
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

    // OCR fuer Text in Bildern (gebuendelt, Latin-Skript, offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Lokales LLM (Qwen3-0.6B als .litertlm)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    // Spracherkennung fuer Sprachnachrichten: sherpa-onnx (Parakeet TDT 0.6B v3 INT8), nur arm64 durch den ABI-Filter oben
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    // Opus-Dekodierung in reinem Java (BSD-3-Clause), Rueckfall zu MediaExtractor/MediaCodec
    implementation("io.github.jaredmdobson:concentus:1.0.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Render der Oberflaeche ohne Emulator (Robolectric, nativer Grafikmodus)
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
