// The recognition itself, in plain Kotlin on ONNX Runtime: runs on Android (through :stackykit) and on any JVM.
plugins { id("org.jetbrains.kotlin.jvm"); `maven-publish` }

group = "com.github.tdemers218.StackyKit"
version = "0.2.0"

val ort = "1.20.0"

kotlin { jvmToolchain(17) }
java { withSourcesJar() }

publishing {
    publications { create<MavenPublication>("release") { artifactId = "core"; from(components["java"]) } }
}

dependencies {
    // Android apps get ONNX Runtime from :stackykit (onnxruntime-android); JVM apps add onnxruntime themselves.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ort")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ort")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Tests that need a model look here: a folder with detector.onnx, reader.onnx, check.onnx and model.json.
    systemProperty("stacky.model", System.getenv("STACKY_MODEL") ?: "")
    systemProperty("stacky.fixtures", System.getenv("STACKY_FIXTURES") ?: "")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
