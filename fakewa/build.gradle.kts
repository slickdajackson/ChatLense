plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stand-in for the WhatsApp UI, for the emulator regression. Its own package name, not a copy of the package com.whatsapp.
android {
    namespace = "com.chatlens.fakewa"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.chatlens.fakewa"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
