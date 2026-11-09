// A small camera app: point it at cards. Put a model folder in src/main/assets/stacky-v13/ first (see README).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stacky.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.stacky.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Phones, and the emulator on Apple-silicon Macs and most PCs.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The .onnx files are already compressed weights; storing them as-is lets them load faster.
    androidResources { noCompress += "onnx" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":stackykit"))
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
