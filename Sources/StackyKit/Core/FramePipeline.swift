import Foundation
import CoreGraphics
import CoreML
import Vision

/// One camera frame -> what the model sees: detector -> 8% padded crop -> reader + corner check -> frame memory
/// -> (demos) corner pairing. Shared by the app (LiveReader) and the Mac stack benchmark (scripts/stack_bench),
/// so the benchmark runs exactly what the phone runs.
final class FramePipeline {
    /// "yolo" (Vision object detector with NMS, YOLOv8 exports) or "heatmap-v1" (Stacky box-finder: peaks + boxes maps).
    let detectorKind: String
    let detector: VNCoreMLModel?
    let heatmap: MLModel?
    let detectorConf: Float
    let reader: CardReader
    let accept: Float, checkThreshold: Float
    var useMemory = true
    var pairCorners = false
    let memory = FrameMemory()
    let pairing = CornerPairing()

    init(detector: VNCoreMLModel?, heatmap: MLModel? = nil, kind: String = "yolo", detectorConf: Float = 0.15, reader: CardReader, accept: Float, checkThreshold: Float) {
        self.detector = detector; self.heatmap = heatmap; self.detectorKind = kind; self.detectorConf = detectorConf
        self.reader = reader; self.accept = accept; self.checkThreshold = checkThreshold
    }

    /// Loads compiled models (.mlmodelc) the way the app does.
    static func load(detector d: URL, reader r: URL, check: URL?, colorJSON: URL, detectorConf: Double, accept: Double, checkThreshold: Double?,
                     detectorKind: String? = nil) throws -> FramePipeline {
        let cfg = MLModelConfiguration(); cfg.computeUnits = .all
        let reader = try CardReader(compiledReader: r, colorJSON: colorJSON, compiledCheck: check)
        let kind = detectorKind ?? "yolo"
        if kind == "heatmap-v1" {
            return FramePipeline(detector: nil, heatmap: try MLModel(contentsOf: d, configuration: cfg), kind: kind, detectorConf: Float(detectorConf),
                                 reader: reader, accept: Float(accept), checkThreshold: Float(checkThreshold ?? 0.5))
        }
        let vm = try VNCoreMLModel(for: MLModel(contentsOf: d, configuration: cfg))
        vm.featureProvider = try MLDictionaryFeatureProvider(dictionary: ["iouThreshold": 0.7, "confidenceThreshold": detectorConf])
        return FramePipeline(detector: vm, kind: kind, detectorConf: Float(detectorConf), reader: reader, accept: Float(accept), checkThreshold: Float(checkThreshold ?? 0.5))
    }

    /// Index boxes in frame pixels (x0, y0, x1, y1).
    func detect(_ frame: CGImage) -> [(Double, Double, Double, Double)] {
        let W = Double(frame.width), H = Double(frame.height)
        if let heatmap { return Self.heatmapBoxes(heatmap, frame, conf: detectorConf) }
        guard let detector else { return [] }
        let req = VNCoreMLRequest(model: detector)
        req.imageCropAndScaleOption = .scaleFit
        try? VNImageRequestHandler(cgImage: frame, orientation: .up).perform([req])
        return (req.results ?? []).compactMap { $0 as? VNRecognizedObjectObservation }.map { o in
            let b = o.boundingBox; return (b.minX * W, (1 - b.maxY) * H, b.maxX * W, (1 - b.minY) * H)
        }
    }

    /// Stacky box-finder: the frame scaled into a 960x960 square at the top-left (black padding), like training.
    static func heatmapBoxes(_ model: MLModel, _ frame: CGImage, conf: Float, size: Int = 960) -> [(Double, Double, Double, Double)] {
        let W = Double(frame.width), H = Double(frame.height), s = Double(size) / max(W, H)
        var pb: CVPixelBuffer?
        CVPixelBufferCreate(nil, size, size, kCVPixelFormatType_32BGRA, [kCVPixelBufferCGImageCompatibilityKey: true, kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary, &pb)
        guard let pb else { return [] }
        CVPixelBufferLockBaseAddress(pb, [])
        if let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: size, height: size, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
                               space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
            ctx.setFillColor(gray: 0, alpha: 1); ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
            ctx.interpolationQuality = .medium
            // CGContext origin is bottom-left: put the frame at the top-left of the square.
            ctx.draw(frame, in: CGRect(x: 0, y: Double(size) - H * s, width: W * s, height: H * s))
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        guard let out = try? model.prediction(from: MLDictionaryFeatureProvider(dictionary: ["image": MLFeatureValue(pixelBuffer: pb)])),
              let peaks = out.featureValue(for: "peaks")?.multiArrayValue, let boxes = out.featureValue(for: "boxes")?.multiArrayValue else { return [] }
        let G = peaks.shape[3].intValue, plane = G * G
        var res: [(Double, Double, Double, Double)] = []
        let pk = MLShapedArray<Float>(converting: peaks).scalars   // any dtype / strides -> contiguous Float
        let bx = MLShapedArray<Float>(converting: boxes).scalars
        for i in 0..<plane where Float(pk[i]) >= conf {
            let b = (0..<4).map { Double(bx[$0 * plane + i]) * Double(size) / s }
            if b[2] > b[0], b[3] > b[1] { res.append((max(0, b[0]), max(0, b[1]), min(W, b[2]), min(H, b[3]))) }
        }
        return res
    }

    func reset() { memory.reset(); pairing.reset() }

    func process(_ frame: CGImage) -> [SeenCard] {
        var found: [SeenCard] = [], corners: [(box: CGRect, reading: CardReading)] = []
        for (x0, y0, x1, y1) in detect(frame) {
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
