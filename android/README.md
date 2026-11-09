# StackyKit for Android (preview)

> Preview: checked against the iOS and web versions on test photos (same networks, same results), but not yet on many phones. Please open an issue with your phone model if something is off.

Playing-card recognition on Android, on the device. It runs the same three networks as StackyKit for iOS (a box-finder for card indexes, a check that each box is a real index, and a rank and suit reader), with ONNX Runtime, and the same steps after them: frame memory, merging a card's two indexes, steady display, deck scanning and the Mnemonica check.

On a test photo of a spread deck it finds the same boxes and cards as the iOS and web versions (52 of 54 indexes identical; the other two differ by a hair in how the picture is resized).

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.tdemers218.StackyKit:stackykit:0.2.0")
}
android {
    androidResources { noCompress += "onnx" }   // lets the model load straight from the APK
}
```

Then download the ONNX model folder (for example `stacky-v13-onnx`) from the [Releases](../../../releases) page and put its four files in `app/src/main/assets/stacky-v13/`.

Requirements: Android 7 (API 24) or newer. A recent mid-range phone or better for live video.

## Usage

### Load the model once

```kotlin
val model = StackyModel.fromAssets(context, "stacky-v13")   // a second or two: off the main thread
```

### Read camera frames (CameraX)

```kotlin
val reader = StackyReader(model)                     // one per camera stream

val analysis = ImageAnalysis.Builder()
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
analysis.setAnalyzer(executor) { frame ->
    frame.use {
        val cards = reader.read(it.toStackyImage())  // turned upright using the frame's rotation
        runOnUiThread { show(cards) }
    }
}
```

- **Upright and never mirrored.** `toStackyImage()` turns CameraX frames upright. With the front camera, mirror only your preview and flip the boxes to match it.
- **Threading.** Call `read` from one thread at a time; the reader keeps per-stream state. Call `reset()` when the scene changes.
- **Resolution.** 1920×1080 frames work best. Indexes should be at least about 15 px tall.
- **Photos.** `bitmap.toStackyImage()`, and a reader with `StackyReader.Options(frameMemory = false)`. For big photos (over about 2000 px) pass `tiles = 2` so small indexes keep their detail.

### StackyCard

| Property | Meaning |
|---|---|
| `code` | `"10S"`, `"QH"`, `"AC"`: rank (A 2–10 J Q K) + suit (C D H S) |
| `rank`, `suit`, `isRed`, `name`, `symbol` | the code split up, "Queen of Hearts", "Q♥" |
| `confidence` | min(rank confidence, suit confidence), 0–1 |
| `isSure` | `confidence ≥` the model's `accept` threshold (0.7) |
| `box` | the index's box, in pixels of the upright frame |
| `otherIndexBox` | the same card's other index, when both were visible and merged |
| `trackId`, `framesSeen` | the same physical corner across frames |
| `rankProbabilities`, `suitProbabilities` | full distributions (A…K; C D H S) |

### Steady display, poker hands

```kotlin
val steady = StackySteady()                         // a card shows after 3 sure reads within 0.8 s
val shown = steady.update(cards)
val hand = PokerHand.best(shown.map { it.code })    // "Full House", "Kings full of Sixes"
```

### Scan a deck order

```kotlin
val scanner = StackyStackScanner(deckSize = 52)
// every frame, while the spread is filmed slowly from one end to the other:
scanner.feed(reader.read(frame.toStackyImage()))

scanner.order        // List<Place(index, code, isSure)>
scanner.isLost       // frames stopped lining up: go back to cards already scanned
scanner.endFound     // the spread's last card was seen whole
scanner.confirm("7D", 12)
scanner.match()      // Mnemonica by default: probability + top card after a cut

// Or one photo of the whole spread:
val places = StackyStackScanner.solvePhoto(photoReader.read(bitmap.toStackyImage(), tiles = 2))
```

## Sample app

`sample/` is a small camera app with three modes: one card, a poker hand, or a whole deck. Put the model's four files in `sample/src/main/assets/stacky-v13/`, then open `android/` in Android Studio, or run `./gradlew :sample:installDebug` with a phone connected.

## Modules

| Module | What it is |
|---|---|
| `core` | The recognition in plain Kotlin on ONNX Runtime. Runs on any JVM too (add `com.microsoft.onnxruntime:onnxruntime`). |
| `stackykit` | `core` plus ONNX Runtime for Android, model loading from assets, and Bitmap / CameraX helpers. |
| `sample` | The demo app. |

## Tests

```sh
cd android
./gradlew :core:test                                   # logic tests
STACKY_MODEL=/path/to/stacky-v13-onnx STACKY_FIXTURES=/path/to/photos ./gradlew :core:test   # plus a photo of a spread deck
./gradlew :stackykit:connectedDebugAndroidTest         # on a phone or emulator; see OnDeviceTest.kt for the files it needs
```

## Privacy and licence

No network calls; frames are processed in memory and dropped. Code MIT, models as described in the main [README](../README.md#licence).
