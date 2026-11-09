# StackyKit for Android

> **Preview.** The Android version matches the iOS and web versions on our test photos: on a photo of a spread deck, 52 of 54 indexes are read identically, and the other two differ only in how the picture is resized. It hasn't been tried on many phones yet. If something is off, please open an issue with your phone model.

This page covers what is specific to Android. Installation, the model download, the API and deck scanning are in the main [README](../README.md).

## CameraX setup

```kotlin
val model  = StackyModel.fromAssets(context, "stacky-v13")   // a second or two: off the main thread
val reader = StackyReader(model)

val analysis = ImageAnalysis.Builder()
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
analysis.setAnalyzer(executor) { frame ->
    frame.use {
        val cards = reader.read(it.toStackyImage())   // turned upright using the frame's rotation
        runOnUiThread { show(cards) }
    }
}
```

- Use `RGBA_8888` output; `toStackyImage()` reads that format.
- Keep `STRATEGY_KEEP_ONLY_LATEST` so a slow phone skips frames instead of falling behind.
- `StackyModel.fromAssets(context, folder, fast = true)` uses XNNPACK on the CPU. That is the default and the fastest option on most phones.
- With `PreviewView` in `FILL_CENTER`, the preview is cropped, so scale and offset the boxes to the view. The sample app's `MainActivity` shows how.

## Modules

| Module | What it is |
|---|---|
| `core` | The recognition in plain Kotlin on ONNX Runtime. It also runs on any JVM if you add `com.microsoft.onnxruntime:onnxruntime`. |
| `stackykit` | `core`, plus ONNX Runtime for Android, model loading from assets, and Bitmap and CameraX helpers. This is the one to depend on. |
| `sample` | The demo app. |

## Sample app

`sample/` is a camera app with three modes: Card, Hand and Deck. Put the model's four files in `sample/src/main/assets/stacky-v13/`, then open `android/` in Android Studio, or run this with a phone connected:

```sh
./gradlew :sample:installDebug
```

## Tests

```sh
./gradlew :core:test                                   # logic tests, on any JVM
STACKY_MODEL=/path/to/stacky-v13-onnx STACKY_FIXTURES=/path/to/photos ./gradlew :core:test   # also reads a spread photo
./gradlew :stackykit:connectedDebugAndroidTest         # on a phone or emulator; see OnDeviceTest.kt for the files it needs
```

## Building from source

JDK 17, Android SDK 36. From `android/`, run `./gradlew build`. JitPack builds tagged versions with `jitpack.yml` at the repository root.
