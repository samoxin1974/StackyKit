import Foundation
import CoreGraphics
import CoreML
import Vision

/// One camera frame -> what the model sees: detector -> 8% padded crop -> reader + corner check -> frame memory
/// -> (demos) corner pairing. Shared by the app (LiveReader) and the Mac stack benchmark (scripts/stack_bench),
/// so the benchmark runs exactly what the phone runs.
final class FramePipeline {
    let detector: VNCoreMLModel
    let reader: CardReader
    let accept: Float, checkThreshold: Float
    var useMemory = true
    var pairCorners = false
    let memory = FrameMemory()
    let pairing = CornerPairing()

    init(detector: VNCoreMLModel, reader: CardReader, accept: Float, checkThreshold: Float) {
        self.detector = detector; self.reader = reader; self.accept = accept; self.checkThreshold = checkThreshold
    }

    /// Loads compiled models (.mlmodelc) the way the app does.
    static func load(detector d: URL, reader r: URL, check: URL?, colorJSON: URL, detectorConf: Double, accept: Double, checkThreshold: Double?) throws -> FramePipeline {
        let cfg = MLModelConfiguration(); cfg.computeUnits = .all
        let vm = try VNCoreMLModel(for: MLModel(contentsOf: d, configuration: cfg))
        vm.featureProvider = try MLDictionaryFeatureProvider(dictionary: ["iouThreshold": 0.7, "confidenceThreshold": detectorConf])
        let reader = try CardReader(compiledReader: r, colorJSON: colorJSON, compiledCheck: check)
        return FramePipeline(detector: vm, reader: reader, accept: Float(accept), checkThreshold: Float(checkThreshold ?? 0.5))
    }

    func reset() { memory.reset(); pairing.reset() }

    func process(_ frame: CGImage) -> [SeenCard] {
        let req = VNCoreMLRequest(model: detector)
        req.imageCropAndScaleOption = .scaleFit
        try? VNImageRequestHandler(cgImage: frame, orientation: .up).perform([req])
        let W = Double(frame.width), H = Double(frame.height)
        var found: [SeenCard] = [], corners: [(box: CGRect, reading: CardReading)] = []
        for case let o as VNRecognizedObjectObservation in req.results ?? [] {
            let b = o.boundingBox
            let x0 = b.minX * W, x1 = b.maxX * W, y0 = (1 - b.maxY) * H, y1 = (1 - b.minY) * H
            guard let crop = paddedCrop(frame, x0: x0, y0: y0, x1: x1, y1: y1), let img = RGBImage(crop), let r = try? reader.read(img) else { continue }
            let rect = CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
            if r.corner >= checkThreshold { corners.append((rect, r)) }
            else { found.append(SeenCard(rect: rect, reading: r, accepted: false, isCorner: false)) }
        }
        if useMemory, !corners.isEmpty, let gray = GrayFrame(frame) {
            for (c, m) in zip(corners, memory.step(corners, frame: gray)) {
                found.append(SeenCard(rect: c.box, reading: m.reading, accepted: m.reading.conf >= accept, isCorner: true, frames: m.frames, track: m.id))
            }
        } else {
            if useMemory { memory.step([], frame: GrayFrame(w0: 1)) }
            found += corners.map { SeenCard(rect: $0.box, reading: $0.reading, accepted: $0.reading.conf >= accept, isCorner: true) }
        }
        if pairCorners { found = pairing.merge(found, accept: accept) }
        return found
    }
}
