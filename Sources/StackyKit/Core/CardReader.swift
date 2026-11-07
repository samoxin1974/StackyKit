import Foundation
import CoreGraphics
import CoreML

// On-device port of scripts/specialist_reader.py (+ normalize_card_color, tensor_image, color_features,
// combine_suit_probabilities). Also compiled into a macOS harness (scripts/coreml_parity) to check it
// gives the same readings as Python.

struct RGBImage {
    var w: Int, h: Int
    var px: [UInt8]   // RGB, row-major

    init(w: Int, h: Int, px: [UInt8]) { self.w = w; self.h = h; self.px = px }

    init?(_ cg: CGImage) {
        w = cg.width; h = cg.height
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        px = [UInt8](repeating: 0, count: w * h * 3)
        for i in 0..<(w * h) { px[i * 3] = rgba[i * 4]; px[i * 3 + 1] = rgba[i * 4 + 1]; px[i * 3 + 2] = rgba[i * 4 + 2] }
    }
}

enum Preprocess {
    /// normalize_card_color: bright card stock as a local white estimate.
    static func normalizeCardColor(_ im: RGBImage) -> RGBImage {
        let n = im.w * im.h
        var lum = [Float](repeating: 0, count: n)
        for i in 0..<n { lum[i] = (Float(im.px[i * 3]) / 255 + Float(im.px[i * 3 + 1]) / 255 + Float(im.px[i * 3 + 2]) / 255) / 3 }
        let sorted = lum.sorted()
        let pos = 0.8 * Double(n - 1), lo = Int(pos.rounded(.down)), hi = min(n - 1, lo + 1)
        let q = Float(Double(sorted[lo]) + (Double(sorted[hi]) - Double(sorted[lo])) * (pos - Double(lo)))
        var ch: [[Float]] = [[], [], []]
        for i in 0..<n where lum[i] >= q { for c in 0..<3 { ch[c].append(Float(im.px[i * 3 + c]) / 255) } }
        let white = ch.map { median($0) }
        guard (white[0] + white[1] + white[2]) / 3 > 0.4 else { return im }
        var out = im
        for i in 0..<n {
            for c in 0..<3 {
                let v = min(1, max(0, (Float(im.px[i * 3 + c]) / 255) / max(white[c], 0.15)))
                out.px[i * 3 + c] = UInt8((v * 255).rounded(.toNearestOrEven))
            }
        }
        return out
    }

    static func median(_ a: [Float]) -> Float {
        guard !a.isEmpty else { return 0 }
        let s = a.sorted(), m = s.count / 2
        return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
    }

    /// PIL-style bilinear resize (triangle filter, antialiased when shrinking), horizontal pass then vertical, 8-bit between passes.
    static func resize(_ im: RGBImage, w nw: Int, h nh: Int) -> RGBImage {
        func pass(_ src: [UInt8], inLen: Int, outLen: Int, lines: Int, horizontal: Bool, srcW: Int) -> [UInt8] {
            let scale = Double(inLen) / Double(outLen), fs = max(scale, 1), support = fs
            var out = [UInt8](repeating: 0, count: (horizontal ? outLen * lines : srcW * outLen) * 3)
            for o in 0..<outLen {
                let center = (Double(o) + 0.5) * scale
                let xmin = max(Int(center - support + 0.5), 0), xmax = min(Int(center + support + 0.5), inLen)
                var ws = [Double](); var total = 0.0
                for x in xmin..<xmax { let t = abs((Double(x) - center + 0.5) / fs); let wv = t < 1 ? 1 - t : 0; ws.append(wv); total += wv }
                if total > 0 { ws = ws.map { $0 / total } }
                for l in 0..<lines {
                    for c in 0..<3 {
                        var acc = 0.0
                        for (k, x) in (xmin..<xmax).enumerated() {
                            let idx = horizontal ? (l * srcW + x) * 3 + c : (x * srcW + l) * 3 + c
                            acc += ws[k] * Double(src[idx])
                        }
                        let v = UInt8(max(0, min(255, acc.rounded())))
                        if horizontal { out[(l * outLen + o) * 3 + c] = v } else { out[(o * srcW + l) * 3 + c] = v }
                    }
                }
            }
            return out
        }
        var px = im.px, w = im.w
        if nw != im.w { px = pass(px, inLen: im.w, outLen: nw, lines: im.h, horizontal: true, srcW: im.w); w = nw }
        if nh != im.h { px = pass(px, inLen: im.h, outLen: nh, lines: w, horizontal: false, srcW: w) }
        return RGBImage(w: nw, h: nh, px: px)
    }

    /// ImageOps.pad to size x size with fill 230.
    static func pad(_ im: RGBImage, size: Int = 96) -> RGBImage {
        let r = Double(im.w) / Double(im.h)
        var nw = size, nh = size
        if r > 1 { nh = Int((Double(im.h) / Double(im.w) * Double(size)).rounded(.toNearestOrEven)) }
        else if r < 1 { nw = Int((Double(im.w) / Double(im.h) * Double(size)).rounded(.toNearestOrEven)) }
        let small = resize(im, w: nw, h: nh)
        var out = RGBImage(w: size, h: size, px: [UInt8](repeating: 230, count: size * size * 3))
        let ox = (size - nw) / 2, oy = (size - nh) / 2
        for y in 0..<nh { for x in 0..<nw { for c in 0..<3 { out.px[((y + oy) * size + x + ox) * 3 + c] = small.px[(y * nw + x) * 3 + c] } } }
        return out
    }

    /// tensor_image: [gray, R, G, B] / 127.5 - 1.
    static func tensor(_ im: RGBImage) -> MLMultiArray {
        let a = try! MLMultiArray(shape: [1, 4, 96, 96], dataType: .float32)
        let p = a.dataPointer.bindMemory(to: Float32.self, capacity: 4 * 96 * 96), plane = 96 * 96
        for i in 0..<plane {
            let r = Int(im.px[i * 3]), g = Int(im.px[i * 3 + 1]), b = Int(im.px[i * 3 + 2])
            let gray = (r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16
            p[i] = Float32(gray) / 127.5 - 1
            p[plane + i] = Float32(r) / 127.5 - 1; p[2 * plane + i] = Float32(g) / 127.5 - 1; p[3 * plane + i] = Float32(b) / 127.5 - 1
        }
        return a
    }

    /// color_features: 12x12 histogram of (R-G, R-B) over non-white pixels of the normalised crop.
    static func colorFeatures(_ im: RGBImage) -> [Float] {
        var hist = [Float](repeating: 0, count: 144); var count = 0
        for i in 0..<(im.w * im.h) {
            let r = Float(im.px[i * 3]) / 255, g = Float(im.px[i * 3 + 1]) / 255, b = Float(im.px[i * 3 + 2]) / 255
            guard (r + g + b) / 3 < 0.85 else { continue }
            count += 1
            func bin(_ v: Float) -> Int { min(11, max(0, Int(((v + 1) / 2 * 12).rounded(.down)))) }
            hist[bin(r - g) * 12 + bin(r - b)] += 1
        }
        let n = Float(max(1, count))
        return hist.map { $0 / n }
    }
}

struct ColorHead: Codable {
    var weight: [[Float]], bias: [Float], mean: [Float], std: [Float]
    /// false (v11+): skip this head; the 4-way suit net decides colour itself. Missing = true (v4-v10 packages).
    var use: Bool?
    /// [black, red] probabilities.
    func probs(_ f: [Float]) -> [Float] {
        let z = zip(zip(f, mean), std).map { ($0.0 - $0.1) / $1 }
        let logits = (0..<2).map { k in zip(weight[k], z).reduce(bias[k]) { $0 + $1.0 * $1.1 } }
        return softmax(logits)
    }
}

func softmax(_ v: [Float]) -> [Float] { let m = v.max() ?? 0; let e = v.map { exp($0 - m) }; let s = e.reduce(0, +); return e.map { $0 / s } }

struct CardReading {
    let card: String; let rankConf: Float; let suitConf: Float; var corner: Float = 1
    var rankProbs: [Float] = [], suitProbs: [Float] = []   // 13 ranks A..K, 4 suits C D H S (for frame memory)
    var conf: Float { min(rankConf, suitConf) }
}

final class CardReader {
    static let ranks = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"]
    static let suits = ["C", "D", "H", "S"]
    let model: MLModel, color: ColorHead, check: MLModel?

    /// `compiledCheck`: optional corner check (is this box really a card index?), same 96px input.
    init(compiledReader: URL, colorJSON: URL, compiledCheck: URL? = nil) throws {
        let cfg = MLModelConfiguration(); cfg.computeUnits = .cpuAndNeuralEngine
        model = try MLModel(contentsOf: compiledReader, configuration: cfg)
        check = try compiledCheck.map { try MLModel(contentsOf: $0, configuration: cfg) }
        color = try JSONDecoder().decode(ColorHead.self, from: Data(contentsOf: colorJSON))
    }

    func read(_ crop: RGBImage) throws -> CardReading {
        let norm = Preprocess.normalizeCardColor(crop)
        let input = try MLDictionaryFeatureProvider(dictionary: ["x": MLFeatureValue(multiArray: Preprocess.tensor(Preprocess.pad(norm)))])
        let out = try model.prediction(from: input)
        var corner: Float = 1
        if let check { corner = try check.prediction(from: input).featureValue(for: "corner")!.multiArrayValue![0].floatValue }
        let rp = floats(out.featureValue(for: "rank")!.multiArrayValue!), sl = floats(out.featureValue(for: "suit_logits")!.multiArrayValue!)
        let sp: [Float]   // C D H S
        if color.use == false { sp = softmax(sl) } else {
            let col = color.probs(Preprocess.colorFeatures(norm))
            let black = softmax([sl[0], sl[3]]), red = softmax([sl[1], sl[2]])
            sp = [black[0] * col[0], red[0] * col[1], red[1] * col[1], black[1] * col[0]]
        }
        let ri = rp.indices.max { rp[$0] < rp[$1] }!, si = sp.indices.max { sp[$0] < sp[$1] }!
        return CardReading(card: Self.ranks[ri] + Self.suits[si], rankConf: rp[ri], suitConf: sp[si], corner: corner, rankProbs: rp, suitProbs: sp)
    }

    private func floats(_ a: MLMultiArray) -> [Float] { (0..<a.count).map { a[$0].floatValue } }
}

/// The 8% padded crop the pipeline reads, from a box in pixel coordinates.
func paddedCrop(_ frame: CGImage, x0: Double, y0: Double, x1: Double, y1: Double) -> CGImage? {
    let p = max(x1 - x0, y1 - y0) * 0.08
    let a = max(0, Int(x0 - p)), b = max(0, Int(y0 - p)), c = min(frame.width, Int(x1 + p + 1)), d = min(frame.height, Int(y1 + p + 1))
    guard c > a, d > b else { return nil }
    return frame.cropping(to: CGRect(x: a, y: b, width: c - a, height: d - b))
}
