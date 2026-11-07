import Foundation
import CoreGraphics

// Frame-to-frame memory for the live test. Same rules as scripts/track_memory.py (policy B); keep them in sync.
//   Link: IoU >= .3, width/height ratio <= 1.5, aligned NCC >= .5 (last box in its frame vs +-10% around the new box).
//   Split: a confident reading (>= .7) that disagrees with the track's confident history starts a new card.
//   Output: a confident frame shows its own reading; an unsure frame shows the mean over the track's recent frames.

struct GrayFrame {
    let w: Int, h: Int
    let px: [UInt8]

    /// Empty frame, for ageing tracks when nothing was found.
    init(w0: Int) { w = 0; h = 0; px = [] }

    init?(_ cg: CGImage) {
        w = cg.width; h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h)
        guard let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                  space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        px = buf
    }

    /// Pixels of a box (clipped), row-major.
    func region(_ r: CGRect) -> (w: Int, h: Int, px: [Float])? {
        let x0 = max(0, Int(r.minX.rounded())), y0 = max(0, Int(r.minY.rounded())), x1 = min(w, Int(r.maxX.rounded())), y1 = min(h, Int(r.maxY.rounded()))
        guard x1 - x0 >= 2, y1 - y0 >= 2 else { return nil }
        var out = [Float](); out.reserveCapacity((x1 - x0) * (y1 - y0))
        for y in y0..<y1 { for x in x0..<x1 { out.append(Float(px[y * w + x])) } }
        return (x1 - x0, y1 - y0, out)
    }

    /// Max normalised cross-correlation (TM_CCOEFF_NORMED) of a template searched around a centre.
    func bestNCC(template t: (w: Int, h: Int, px: [Float]), centre: CGPoint) -> Float? {
        let n = Float(t.w * t.h), mean = t.px.reduce(0, +) / n
        let tz = t.px.map { $0 - mean }, tnorm = sqrt(tz.reduce(0) { $0 + $1 * $1 })
        guard tnorm / sqrt(n) >= 1 else { return nil }   // flat template (std < 1)
        let r = max(2, Int((Float(min(t.w, t.h)) * 0.1).rounded()))
        let left = Int((centre.x - CGFloat(t.w) / 2).rounded()) - r, top = Int((centre.y - CGFloat(t.h) / 2).rounded()) - r
        let sx0 = max(0, left), sy0 = max(0, top), sx1 = min(w, left + t.w + 2 * r), sy1 = min(h, top + t.h + 2 * r)
        guard sx1 - sx0 >= t.w, sy1 - sy0 >= t.h else { return nil }
        var best: Float = -1
        for oy in sy0...(sy1 - t.h) {
            for ox in sx0...(sx1 - t.w) {
                var s: Float = 0, s2: Float = 0, st: Float = 0
                for y in 0..<t.h {
                    let row = (oy + y) * w + ox, trow = y * t.w
                    for x in 0..<t.w { let v = Float(px[row + x]); s += v; s2 += v * v; st += v * tz[trow + x] }
                }
                let varI = s2 - s * s / n
                if varI > 1e-6 { best = max(best, st / (sqrt(varI) * tnorm)) }
            }
        }
        return best
    }
}

final class FrameMemory {
    struct Track {
        let id: Int
        var box: CGRect
        var template: (w: Int, h: Int, px: [Float])?
        var missed = 0
        var rp: [[Float]] = [], sp: [[Float]] = []
    }
    static let window = 12, maxMissed = 4
    static let iouMin: CGFloat = 0.3, sizeMax: CGFloat = 1.5, nccMin: Float = 0.5, splitConf: Float = 0.7

    private(set) var tracks: [Track] = []
    private var nextID = 0

    func reset() { tracks = []; nextID = 0 }

    /// Updates tracks with this frame's corner-checked readings; returns, per input, (track id, frames, shown reading).
    func step(_ dets: [(box: CGRect, reading: CardReading)], frame: GrayFrame) -> [(id: Int, frames: Int, reading: CardReading)] {
        var pairs: [(CGFloat, Int, Int)] = []
        for (ti, t) in tracks.enumerated() { for (di, d) in dets.enumerated() { pairs.append((iou(t.box, d.box), ti, di)) } }
        pairs.sort { $0.0 > $1.0 }
        var usedT = Set<Int>(), usedD = Set<Int>(), result = [Int: (Int, Int, CardReading)]()
        for (v, ti, di) in pairs where v >= Self.iouMin && !usedT.contains(ti) && !usedD.contains(di) {
            let a = tracks[ti].box, b = dets[di].box
            if max(a.width / b.width, b.width / a.width) > Self.sizeMax || max(a.height / b.height, b.height / a.height) > Self.sizeMax { continue }
            if let t = tracks[ti].template, let n = frame.bestNCC(template: t, centre: CGPoint(x: b.midX, y: b.midY)), n < Self.nccMin { continue }
            usedT.insert(ti); usedD.insert(di)
            let r = dets[di].reading
            if r.conf >= Self.splitConf, let (card, conf) = combined(tracks[ti]), conf >= Self.splitConf, card != r.card { tracks[ti].rp = []; tracks[ti].sp = [] }
            tracks[ti].box = b; tracks[ti].template = frame.region(b); tracks[ti].missed = 0
            tracks[ti].rp = Array((tracks[ti].rp + [r.rankProbs]).suffix(Self.window)); tracks[ti].sp = Array((tracks[ti].sp + [r.suitProbs]).suffix(Self.window))
            result[di] = (tracks[ti].id, tracks[ti].rp.count, shown(r, tracks[ti]))
        }
        for ti in tracks.indices where !usedT.contains(ti) { tracks[ti].missed += 1 }
        tracks.removeAll { $0.missed > Self.maxMissed }
        for (di, d) in dets.enumerated() where !usedD.contains(di) {
            let t = Track(id: nextID, box: d.box, template: frame.region(d.box), rp: [d.reading.rankProbs], sp: [d.reading.suitProbs]); nextID += 1
            tracks.append(t); result[di] = (t.id, 1, d.reading)
        }
        return dets.indices.map { let r = result[$0]!; return (r.0, r.1, r.2) }
    }

    private func shown(_ r: CardReading, _ t: Track) -> CardReading {
        guard r.conf < Self.splitConf, t.rp.count > 1, let m = mean(t) else { return r }
        return m
    }

    private func mean(_ t: Track) -> CardReading? {
        guard let n = t.rp.first?.count, let k = t.sp.first?.count else { return nil }
        let rp = (0..<n).map { i in t.rp.reduce(0) { $0 + $1[i] } / Float(t.rp.count) }
        let sp = (0..<k).map { i in t.sp.reduce(0) { $0 + $1[i] } / Float(t.sp.count) }
        let ri = rp.indices.max { rp[$0] < rp[$1] }!, si = sp.indices.max { sp[$0] < sp[$1] }!
        return CardReading(card: CardReader.ranks[ri] + CardReader.suits[si], rankConf: rp[ri], suitConf: sp[si], rankProbs: rp, suitProbs: sp)
    }

    private func combined(_ t: Track) -> (String, Float)? { mean(t).map { ($0.card, $0.conf) } }

    private func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let i = a.intersection(b); guard !i.isNull else { return 0 }
        let inter = i.width * i.height; return inter / (a.width * a.height + b.width * b.height - inter + 1e-9)
    }
}
