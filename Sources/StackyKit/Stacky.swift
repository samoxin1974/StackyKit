import Foundation
import CoreGraphics
import CoreML
import CoreImage
import CoreVideo
import ImageIO

// StackyKit public API. Everything else in this package (Core/) is internal and may change between versions;
// apps only use what is in this file.
//
//   let model   = try await StackyModel.load(from: folderURL)
//   let reader  = try StackyReader(model: model)
//   let cards   = reader.read(pixelBuffer, orientation: .right)     // one camera frame -> [StackyCard]
//   let scanner = StackyStackScanner(); scanner.feed(cards)         // optional: build a deck order

public enum StackyError: Error, CustomStringConvertible {
    case missingFile(String)
    case unsupportedModel(String)

    public var description: String {
        switch self {
        case .missingFile(let f): "Stacky model folder is missing \(f)"
        case .unsupportedModel(let why): "This Stacky model needs a newer StackyKit: \(why)"
        }
    }
}

// MARK: model

/// A model folder: detector, reader, corner check and their settings (manifest.json, or info.json from Stacky Trainer).
/// Updating the model = replacing this folder. Thresholds travel with the model, not with the app.
public struct StackyModel: Sendable {
    /// What the folder's manifest says. Field names match the JSON.
    public struct Manifest: Codable, Sendable {
        public var format: Int?            // manifest format; nil = Stacky Trainer info.json (same fields)
        public var name: String            // "v12"
        public var exported: String        // export time; also the compile-cache key
        public var preprocess: String?     // how crops are prepared; nil = "swift-v1"
        public var detector_conf: Double   // detector box threshold
        public var accept: Double          // a reading at or above this is "sure"
        public var check: String?          // corner-check model name (nil = none)
        public var check_threshold: Double?
        public var imgsz: Int?
        public var pad: Double?
        public var detector_kind: String?  // nil = "yolo"; "heatmap-v1" = Stacky box-finder
    }

    /// Preprocessing versions this StackyKit can run. A model asking for another one throws `unsupportedModel`
    /// instead of reading wrongly, so a model that needs new code can never be swapped in by mistake.
    public static let supportedPreprocess: Set<String> = ["swift-v1"]
    public static let supportedFormat = 1

    public let manifest: Manifest
    public let folder: URL
    let detector: URL, reader: URL, check: URL?, color: URL

    public var name: String { manifest.name }

    /// Loads a model folder. `.mlmodelc` files are used as they are; `.mlpackage` files are compiled once and
    /// cached (first launch only, a few seconds). Call off the main thread.
    public static func load(from folder: URL, cacheDirectory: URL? = nil) async throws -> StackyModel {
        let fm = FileManager.default
        let mURL = [folder.appendingPathComponent("manifest.json"), folder.appendingPathComponent("info.json")].first { fm.fileExists(atPath: $0.path) }
        guard let mURL else { throw StackyError.missingFile("manifest.json") }
        let m = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: mURL))
        if let f = m.format, f > supportedFormat { throw StackyError.unsupportedModel("manifest format \(f)") }
        let pre = m.preprocess ?? "swift-v1"
        guard supportedPreprocess.contains(pre) else { throw StackyError.unsupportedModel("preprocess \(pre)") }
        if let k = m.detector_kind, !["yolo", "heatmap-v1"].contains(k) { throw StackyError.unsupportedModel("detector \(k)") }
        let color = folder.appendingPathComponent("color.json")
        guard fm.fileExists(atPath: color.path) else { throw StackyError.missingFile("color.json") }

        let cache = (cacheDirectory ?? fm.urls(for: .cachesDirectory, in: .userDomainMask)[0])
            .appendingPathComponent("StackyKit/\(m.name)-\(m.exported)")
        func compiled(_ part: String) async throws -> URL {
            let c = folder.appendingPathComponent("\(part).mlmodelc")
            if fm.fileExists(atPath: c.path) { return c }
            let pkg = folder.appendingPathComponent("\(part).mlpackage")
            guard fm.fileExists(atPath: pkg.path) else { throw StackyError.missingFile("\(part).mlmodelc or \(part).mlpackage") }
            let dest = cache.appendingPathComponent("\(part).mlmodelc")
            if fm.fileExists(atPath: dest.path) { return dest }
            try fm.createDirectory(at: cache, withIntermediateDirectories: true)
            let tmp = try await MLModel.compileModel(at: pkg)
            try? fm.removeItem(at: dest); try fm.moveItem(at: tmp, to: dest)
            return dest
        }
        let d = try await compiled("detector"), r = try await compiled("reader")
        let c = m.check != nil ? try await compiled("check") : nil
        return StackyModel(manifest: m, folder: folder, detector: d, reader: r, check: c, color: color)
    }
}

// MARK: results

/// One card index read in a frame.
public struct StackyCard: Sendable, Hashable, Identifiable {
    public let id = UUID()
    /// "10S", "QH", "AC": rank (A 2-10 J Q K) + suit (C D H S).
    public let code: String
    public var rank: String { String(code.dropLast()) }
    public var suit: String { String(code.suffix(1)) }
    public var isRed: Bool { suit == "H" || suit == "D" }
    /// min(rank confidence, suit confidence), 0-1.
    public let confidence: Float
    public let rankConfidence: Float, suitConfidence: Float
    /// confidence >= the model's `accept` threshold. Sure readings are right about 97% of the time on the
    /// test set; show unsure ones differently (or not at all).
    public let isSure: Bool
    /// The index's box in pixels of the upright frame (origin top-left, y down).
    public let box: CGRect
    /// The same card's other index (the upside-down one), when both are visible and were merged.
    public let otherIndexBox: CGRect?
    /// Same physical corner across frames (frame memory); nil when frame memory is off.
    public let trackID: Int?
    /// How many recent frames this track has.
    public let framesSeen: Int
    /// Probabilities over ranks A,2..10,J,Q,K and suits C,D,H,S.
    public let rankProbabilities: [Float], suitProbabilities: [Float]

    let seen: SeenCard

    init(_ s: SeenCard) {
        seen = s
        code = s.reading.card; confidence = s.reading.conf
        rankConfidence = s.reading.rankConf; suitConfidence = s.reading.suitConf
        isSure = s.accepted; box = s.rect; otherIndexBox = s.pair; trackID = s.track; framesSeen = s.frames
        rankProbabilities = s.reading.rankProbs; suitProbabilities = s.reading.suitProbs
    }

    public static func == (a: StackyCard, b: StackyCard) -> Bool { a.id == b.id }
    public func hash(into h: inout Hasher) { h.combine(id) }
}

extension SeenCard: @unchecked Sendable {}

// MARK: reader

/// Reads camera frames. Keeps state between frames (frame memory, corner pairing), so use one reader per
/// camera stream, call it from one thread at a time (your video-output queue), and `reset()` when the scene
/// changes. About 20-40 ms per frame on an iPhone 15 Pro.
public final class StackyReader {
    public struct Options: Sendable {
        /// Remember each corner across frames: steadier readings, track ids. Recommended on.
        public var frameMemory = true
        /// Merge a card's two indexes into one card (the one facing like the other cards is shown).
        public var pairCorners = true
        public init(frameMemory: Bool = true, pairCorners: Bool = true) { self.frameMemory = frameMemory; self.pairCorners = pairCorners }
    }

    public let model: StackyModel
    private let pipeline: FramePipeline
    private let ci = CIContext(options: [.cacheIntermediates: false])

    public init(model: StackyModel, options: Options = Options()) throws {
        self.model = model
        let m = model.manifest
        pipeline = try FramePipeline.load(detector: model.detector, reader: model.reader, check: model.check, colorJSON: model.color,
                                          detectorConf: m.detector_conf, accept: m.accept, checkThreshold: m.check_threshold,
                                          detectorKind: m.detector_kind)
        pipeline.useMemory = options.frameMemory
        pipeline.pairCorners = options.pairCorners
    }

    /// Reads an upright image. Returns only boxes that passed the corner check (real card indexes).
    public func read(_ image: CGImage) -> [StackyCard] {
        pipeline.process(image).filter(\.isCorner).map(StackyCard.init)
    }

    /// Reads a camera buffer. `orientation` turns the buffer upright and must never mirror it: the model reads
    /// indexes as printed (portrait, unrotated buffer from either camera: `.right`; connection already rotated: `.up`).
    /// Boxes are in the upright image's pixels; mirror them yourself for a mirrored front-camera preview.
    public func read(_ buffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> [StackyCard] {
        let img = CIImage(cvPixelBuffer: buffer).oriented(orientation)
        guard let cg = ci.createCGImage(img, from: img.extent) else { return [] }
        return read(cg)
    }

    /// Forget previous frames (new scene, camera flipped, model swapped).
    public func reset() { pipeline.reset() }
}

// MARK: stack scanner

/// Builds the order of a spread deck from many frames: each frame's cards are lined up along the spread
/// and stitched onto the order so far; each card appears once (52-card deck unless `deckSize` says otherwise).
/// Feed it the reader's output for every frame while the spectator's (or magician's) spread is filmed.
/// Not thread-safe: use it from one thread (main is simplest; it is an ObservableObject inside).
public final class StackyStackScanner {
    public struct Place: Sendable, Hashable {
        public let index: Int          // 0 = first card of the spread
        public let code: String
        /// Seen often enough and clearly enough. Not sure = ask to show that part again.
        public let isSure: Bool
    }
    /// A run of places that are not sure yet; `before`/`after` are the sure neighbours to point the user to.
    public struct Section: Sendable, Hashable { public let start: Int, end: Int; public let before: String?, after: String? }
    public struct StackMatch: Sendable { public let probability: Double; public let topCard: String; public let cut: Int }

    let engine = StackScan()

    public init(deckSize: Int = 52, haptics: Bool = false) {
        engine.deckSize = deckSize
        Haptic.enabled = haptics
    }

    public var deckSize: Int { get { engine.deckSize } set { engine.deckSize = newValue } }

    /// Feed one frame's cards (from `StackyReader.read`, with frame memory and pairing on).
    public func feed(_ cards: [StackyCard]) { engine.feed(cards.map(\.seen)) }

    public var order: [Place] { engine.entries.enumerated().map { Place(index: $0.offset, code: $0.element.card.code, isSure: $0.element.sure) } }
    public var sectionsToRescan: [Section] { engine.sections.map { Section(start: $0.start, end: $0.end, before: $0.before?.code, after: $0.after?.code) } }
    /// Places visible in the last frame (for highlighting where the camera is).
    public var visiblePlaces: Set<Int> { engine.visible }
    /// Cards of a full deck not placed yet.
    public var missing: [String] { engine.missing.map(\.code) }
    public var isComplete: Bool { engine.complete }
    /// Several frames in a row did not line up with the order: ask the user to go back to cards already scanned.
    public var isLost: Bool { engine.lost }
    /// The spread's last card was seen whole (both indexes): nothing is added after it.
    public var endFound: Bool { engine.endLocked }

    /// The user says place `index` is `code` (e.g. from a picker). Fixed for good; other places re-shuffle around it.
    public func confirm(_ code: String, at index: Int) { if let c = Card(code) { engine.accept(c, at: index) } }
    public func undoLast() { engine.undo() }
    public func reset() { engine.reset() }

    /// How likely the order is a (cut) known stack. `stack` = card codes top to bottom; default Mnemonica.
    public func match(stack: [String]? = nil) -> StackMatch? {
        let s = stack.map { $0.compactMap(Card.init) } ?? mnemonica
        return engine.match(s).map { StackMatch(probability: $0.probability, topCard: $0.top.code, cut: $0.cut) }
    }

    public static var mnemonicaOrder: [String] { mnemonica.map(\.code) }
}

// MARK: steady display

/// Turns per-frame readings into a steady on-screen list: a card appears once it was sure in `need` of the
/// recent frames (within `within` seconds) and stays through dropouts shorter than `grace` seconds.
/// This is what the Stacky demos use so nothing flickers. One per camera stream; not thread-safe.
public final class StackySteady {
    public let need: Int, within: TimeInterval, grace: TimeInterval
    private var hits: [String: [Date]] = [:], last: [String: (Date, StackyCard)] = [:], shown: Set<String> = []

    public init(need: Int = 3, within: TimeInterval = 0.8, grace: TimeInterval = 0.9) { self.need = need; self.within = within; self.grace = grace }

    /// Feed one frame's cards; returns the cards to show (latest reading of each).
    public func update(_ cards: [StackyCard], now: Date = Date()) -> [StackyCard] {
        for c in cards where c.isSure {
            hits[c.code] = ((hits[c.code] ?? []) + [now]).filter { now.timeIntervalSince($0) <= within }
            last[c.code] = (now, c)
        }
        last = last.filter { now.timeIntervalSince($0.value.0) <= grace }
        hits = hits.filter { last[$0.key] != nil }
        shown = Set(last.keys.filter { (hits[$0]?.count ?? 0) >= need || shown.contains($0) })
        return shown.compactMap { last[$0]?.1 }.sorted { $0.box.minX < $1.box.minX }
    }

    public func reset() { hits = [:]; last = [:]; shown = [] }
}
