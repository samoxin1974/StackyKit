# StackyKit

Playing-card recognition for iOS, macOS and Android. Point a camera at cards and get each card's rank, suit, confidence and position, frame after frame. It reads a fanned or spread deck, a poker hand, a single card, cards dealt on a table, and indexes as small as a few millimetres on screen.

Everything runs on the device: Core ML (Neural Engine) on Apple devices, ONNX Runtime on Android. No network and no account, and frames never leave the phone. Both versions run the same networks and the same steps, so they read the same cards. **Android:** see [android/README.md](android/README.md).

```swift
import StackyKit

let model  = try await StackyModel.load(from: modelFolder)   // a model folder from the Releases page
let reader = try StackyReader(model: model)

// In your AVCaptureVideoDataOutput delegate:
let cards = reader.read(pixelBuffer, orientation: .right)   // [StackyCard]; unrotated portrait buffer
for c in cards where c.isSure {
    print(c.code, c.confidence, c.box)                       // "QH" 0.98 (412, 233, 38, 61)
}
```

## What it does

- **Reads card indexes.** It finds every index in the frame and reads its rank and suit. The model was trained on thousands of real phone and webcam frames labelled by a card magician: hands, spreads, tables, low light and motion blur.
- **Says how sure it is.** Each card has a confidence and `isSure`. On the test set, sure readings are right about 97% of the time. Show unsure readings differently, or ask for a better look.
- **Steadies readings across frames.** Frame memory follows each corner from frame to frame and settles its reading, so the output doesn't flicker.
- **Shows one card once.** When a card shows both indexes (one upright, one upside down), the two are merged. The index facing like the other cards is shown, and the second only adds confidence.
- **Scans a whole deck.** `StackyStackScanner` builds the order of a spread deck from many frames. It uses each card's position along the spread, not the order in which cards were seen. It knows a deck has each card once, says which sections need another look, and can tell whether the order is a (cut) known stack such as Mnemonica.

## Install

Swift Package Manager: **File → Add Package Dependencies…** and enter this repository's URL. Or add it to `Package.swift`:

```swift
.package(url: "https://github.com/tdemers218/StackyKit", from: "0.1.0")
```

Then get a model folder (for example `stacky-v11`) from the [Releases](../../releases) page and add it to your app. The first public model is coming soon: see Licence.

**Add the model folder as a folder reference** (blue folder in Xcode, "Create folder references"). If Xcode sees the `.mlmodelc` files as group members, it tries to treat them as its own models.

Requirements: iOS 16+ or macOS 13+, Xcode 15+. iPhone 12 or newer recommended; about 20–40 ms per frame on an iPhone 15 Pro.

## Usage

### Load a model once

```swift
let url = Bundle.main.url(forResource: "stacky-v11", withExtension: nil)!
let model = try await StackyModel.load(from: url)   // a few ms with .mlmodelc; call off the main thread
```

A model folder holds `detector`, `reader` and `check` (Core ML), `color.json` and `manifest.json`. The thresholds live in the manifest, so **updating the model means replacing the folder**, with no code change. If a model needs a newer StackyKit, `load` throws `StackyError.unsupportedModel` instead of reading wrongly.

### Read frames

```swift
let reader = try StackyReader(model: model)   // one per camera stream

func captureOutput(_ o: AVCaptureOutput, didOutput sb: CMSampleBuffer, from c: AVCaptureConnection) {
    guard let px = CMSampleBufferGetImageBuffer(sb) else { return }
    let cards = reader.read(px, orientation: .right)   // back camera, portrait
    DispatchQueue.main.async { self.show(cards) }
}
```

- **Orientation: upright and never mirrored.** The model must see indexes as printed. Simplest: set `videoRotationAngle = 90` and `isVideoMirrored = false` on the output's connection and pass `.up`. Unrotated portrait buffers from either camera: `.right`. Mirror only your preview, and flip the boxes to match it. Boxes come back in the upright image's pixels (origin top-left).
- **Threading.** Call `read` from one queue at a time; the reader keeps per-stream state.
- **Reset.** Call `reset()` when the scene changes: camera flipped, new trick, model swapped.
- **Resolution.** 1920×1080 frames work best. Indexes should be at least about 15 px tall.
- **Images.** `read(_ image: CGImage)` takes upright images, such as photos or video frames.

### StackyCard

| Property | Meaning |
|---|---|
| `code` | `"10S"`, `"QH"`, `"AC"`: rank (A 2–10 J Q K) + suit (C D H S) |
| `rank`, `suit`, `isRed` | the code split up |
| `confidence` | min(rank confidence, suit confidence), 0–1 |
| `isSure` | `confidence ≥` the model's `accept` threshold (0.7) |
| `box` | the index's box, in pixels of the upright frame |
| `otherIndexBox` | the same card's other index, when both were visible and merged |
| `trackID`, `framesSeen` | the same physical corner across frames |
| `rankProbabilities`, `suitProbabilities` | full distributions (A…K; C D H S) |

### Scan a deck order

```swift
let scanner = StackyStackScanner(deckSize: 52)
// every frame, while the spread is filmed slowly from one end to the other:
scanner.feed(reader.read(px, orientation: .right))

scanner.order              // [Place(index, code, isSure)]
scanner.sectionsToRescan   // runs of unsure places, with sure neighbours to point the user to
scanner.isLost             // frames stopped lining up: ask the user to go back to cards already scanned
scanner.endFound           // the spread's last card was seen whole
scanner.confirm("7D", at: 12)
scanner.match()            // Mnemonica by default: probability + top card after a cut
```

Tips for users:
- Spread the cards so every index shows, and film along the spread at a steady pace.
- Go back over sections the scanner marks. Showing cards again only makes those places surer, it never duplicates them.

## Android (preview)

The same API in Kotlin, from JitPack. It reads the same cards as the iOS and web versions on our test photos; it hasn't been tried on many phones yet, so reports are welcome.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
// app/build.gradle.kts
implementation("com.github.tdemers218.StackyKit:stackykit:0.2.0")
```

```kotlin
val model  = StackyModel.fromAssets(context, "stacky-v13")   // off the main thread, once
val reader = StackyReader(model)

// In your CameraX ImageAnalysis.Analyzer (output format RGBA_8888):
val cards = reader.read(imageProxy.toStackyImage())          // turned upright for you
for (c in cards) if (c.isSure) Log.i("Stacky", "${c.code} ${c.confidence} ${c.box}")
```

`StackyStackScanner`, `StackySteady` and poker hands (`PokerHand.best`) work as on iOS. Android 7 (API 24) or newer. The [sample app](android/sample) shows a card, a poker hand and a whole deck live. Details: [android/README.md](android/README.md).

## Command-line check (macOS)

```sh
swift run -c release stacky-read path/to/stacky-v11 photo.jpg clip.mov --fps 6
swift run -c release stacky-read path/to/stacky-v11 spread.mov --scan     # deck order + Mnemonica match
```

## Limits

- **Decks:** trained on standard red/black indexes (Bicycle-style). Unusual faces, jumbo indexes and four-colour decks are untested.
- **Light:** dim light is weaker than daylight. Keep the indexes lit.
- **Blur:** very fast motion blurs indexes and lowers confidence. The reader then says "unsure"; it doesn't guess.
- **Pairing:** a card's two indexes are merged only when both are in frame and read alike.

## Models and versions

Free models are published on the Releases page: a Core ML folder for iOS and macOS, and an ONNX folder (`detector.onnx`, `reader.onnx`, `check.onnx`, `model.json`) for Android and other platforms. Each new release replaces the previous free model with a newer one. The manifest says which model you have (`model.name`, `model.manifest.exported`).

## Privacy

StackyKit makes no network calls. Frames are processed in memory and dropped.

## Licence

- **Code:** MIT (see `LICENSE`).
- **Models:** the free model is the one before the newest. Since v13, every network in a model folder is Stacky's own and MIT: the box-finder (detector), the corner check and the reader. They were trained on Stacky's own labelled footage; the box-finder starts from torchvision's ImageNet MobileNetV3 weights (BSD-3-Clause), and no YOLO or other AGPL code or weights are used. Older model folders with a YOLO detector still load (`detector_kind` "yolo"), but they aren't published.
