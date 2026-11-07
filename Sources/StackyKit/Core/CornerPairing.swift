import CoreGraphics

/// Both indexes of one card (top-left and the upside-down bottom-right) often show at once.
/// Demos merge them into one card:
///  - pair = two corners with the same top reading, similar size, within reach of each other on the card;
///  - the corner shown is the one facing the same way as the other cards (the direction from shown to hidden
///    corner agrees with the frame's majority, kept steady from frame to frame); with no clear direction,
///    the corner shown last frame stays;
///  - the hidden corner only adds evidence: rank and suit probabilities are multiplied and renormalised.
final class CornerPairing {
    private var direction = CGVector(dx: 1, dy: 1)   // shown -> hidden corner, image coordinates (y down)
    private var lastShown: [String: CGPoint] = [:]

    func reset() { direction = CGVector(dx: 1, dy: 1); lastShown = [:] }

    func merge(_ cards: [SeenCard], accept: Float) -> [SeenCard] {
        let corners = cards.filter(\.isCorner), others = cards.filter { !$0.isCorner }
        var cand: [(CGFloat, Int, Int)] = []
        for i in corners.indices { for j in corners.indices where j > i {
            let a = corners[i], b = corners[j]
            guard a.reading.card == b.reading.card else { continue }
            let ha = max(a.rect.width, a.rect.height), hb = max(b.rect.width, b.rect.height)
            guard max(ha / hb, hb / ha) <= 1.7 else { continue }
            let d = hypot(a.rect.midX - b.rect.midX, a.rect.midY - b.rect.midY)
            guard d >= 1.5 * max(ha, hb), d <= 10 * max(ha, hb) else { continue }   // not the same box, still on one card
            cand.append((d, i, j))
        } }
        var used = Set<Int>(), pairs: [(Int, Int)] = []
        for (_, i, j) in cand.sorted(by: { $0.0 < $1.0 }) where !used.contains(i) && !used.contains(j) { used.insert(i); used.insert(j); pairs.append((i, j)) }

        // Majority direction this frame (each pair's vector flipped to agree with the running direction).
        var sum = CGVector.zero
        for (i, j) in pairs {
            var v = unit(corners[i].rect, corners[j].rect)
            if v.dx * direction.dx + v.dy * direction.dy < 0 { v = CGVector(dx: -v.dx, dy: -v.dy) }
            sum.dx += v.dx; sum.dy += v.dy
        }
        if hypot(sum.dx, sum.dy) > 0.5 { let n = hypot(sum.dx, sum.dy); direction = CGVector(dx: 0.7 * direction.dx + 0.3 * sum.dx / n, dy: 0.7 * direction.dy + 0.3 * sum.dy / n) }

        var out = others + corners.indices.filter { !used.contains($0) }.map { corners[$0] }
        var shownNow: [String: CGPoint] = [:]
        for (i, j) in pairs {
            let v = unit(corners[i].rect, corners[j].rect), cos = (v.dx * direction.dx + v.dy * direction.dy) / max(1e-6, hypot(direction.dx, direction.dy))
            var (p, s) = cos >= 0 ? (corners[i], corners[j]) : (corners[j], corners[i])
            if abs(cos) < 0.3, let last = lastShown[p.reading.card],
               hypot(s.rect.midX - last.x, s.rect.midY - last.y) < hypot(p.rect.midX - last.x, p.rect.midY - last.y) { swap(&p, &s) }
            let r = combine(p.reading, s.reading)
            var m = SeenCard(rect: p.rect, reading: r, accepted: r.conf >= accept, isCorner: true, frames: max(p.frames, s.frames), track: p.track)
            m.pair = s.rect
            out.append(m); shownNow[r.card] = CGPoint(x: p.rect.midX, y: p.rect.midY)
        }
        lastShown = shownNow.merging(lastShown.filter { k, _ in !shownNow.keys.contains(k) }) { a, _ in a }
        return out
    }

    private func unit(_ a: CGRect, _ b: CGRect) -> CGVector {
        let dx = b.midX - a.midX, dy = b.midY - a.midY, n = max(1e-6, hypot(dx, dy)); return CGVector(dx: dx / n, dy: dy / n)
    }

    private func combine(_ a: CardReading, _ b: CardReading) -> CardReading {
        func mul(_ x: [Float], _ y: [Float]) -> [Float] {
            guard x.count == y.count, !x.isEmpty else { return x }
            let p = zip(x, y).map { max($0 * $1, 1e-12) }, s = p.reduce(0, +); return p.map { $0 / s }
        }
        let rp = mul(a.rankProbs, b.rankProbs), sp = mul(a.suitProbs, b.suitProbs)
        guard !rp.isEmpty, !sp.isEmpty else { return a.conf >= b.conf ? a : b }
        let ri = rp.indices.max { rp[$0] < rp[$1] }!, si = sp.indices.max { sp[$0] < sp[$1] }!
        return CardReading(card: CardReader.ranks[ri] + CardReader.suits[si], rankConf: rp[ri], suitConf: sp[si], corner: max(a.corner, b.corner), rankProbs: rp, suitProbs: sp)
    }
}
