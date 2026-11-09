// StackyKit for Android: the core plus ONNX Runtime for Android, and helpers for Bitmaps and CameraX frames.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "com.github.tdemers218.StackyKit"
version = "0.2.0"

android {
    namespace = "com.stacky.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    androidResources { noCompress += "onnx" }
    publishing { singleVariant("release") { withSourcesJar() } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    compileOnly("androidx.camera:camera-core:1.4.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "stackykit"
            afterEvaluate { from(components["release"]) }
        }
    }
}
