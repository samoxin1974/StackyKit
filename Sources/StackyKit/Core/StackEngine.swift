import Foundation
import Combine
import CoreGraphics

/// Stack scanner engine: places, not sightings.
/// Every frame gives a short run of cards in order along the spread (corners on the spread's line, sorted along it).
/// That run is lined up against the stack so far (like stitching a panorama), with a small dynamic program:
///   match a corner to a place (score: does its reading agree with what that place already looks like),
///   skip a place (a corner the detector missed this frame), or add a new place (cheap at the ends, costly in the
///   middle; never past the deck size, never past a known end of the spread).
/// The run is also tried reversed, in case the spread direction flipped. A frame is used only if it agrees with
/// the stack on enough places; otherwise it is ignored (nothing is added from frames that do not line up).
/// Each place adds up evidence from every frame that saw it (tempered), so showing cards again simply makes
/// those places surer. Cards are then given to places all at once: each card once in the deck (Hungarian
/// assignment on the places' probabilities), so a misread is fixed when the right card turns up elsewhere.
/// End of the spread: the last card is fully visible, both indexes (one upright, one upside down). When a run
/// ends on such a card (paired by CornerPairing) at the last place, nothing more is added after it.
final class StackScan: ObservableObject {
    struct Entry: Identifiable, Equatable {
        let id: UUID
        var card: Card
        var sure: Bool
        var checked = false   // set by hand ("Use")
    }
    enum State { case good, verify, missing, notYet }
    struct Section: Identifiable, Equatable {
        var start: Int, end: Int
        var before: Card?, after: Card?
        var id: Int { start }
    }

    @Published private(set) var entries: [Entry] = []
    @Published private(set) var visible: Set<Int> = []     // places seen in the current frame
    @Published private(set) var endLocked = false
    @Published private(set) var startLocked = false
    @Published var deckSize = 52 { didSet { assign() } }
    @Published var entriesVersion = 0
    @Published private(set) var lost = false   // frames keep not lining up: go back to cards already scanned
    private var rejected = 0
    private var fullViews = 0   // frames that showed (nearly) the whole deck at once
    nonisolated(unsafe) static var curvedOrder = true    // follow curved fans (chainOrder); false = straight line only
    nonisolated(unsafe) static var fullViewSolve = true  // solve a whole-deck frame directly
    var fullDeck: Bool { deckSize == 52 }

    // One place = summed tempered log-probabilities over the 52 cards.
    private var ids: [UUID] = []
    private var logp: [[Float]] = []
    private var seen: [Int] = []
    private var miss: [Int] = []     // frames the place was in view (inside the run) but not matched
    private var pinned: [Card?] = []
    private var axis = CGVector(dx: 1, dy: 0)

    static let temper: Float = 0.5

    func reset() { apHits = [:]; apFirst = [:]; apOrder = []; apSure = [:]; apEver = [:]; apLate = []; sequenceMode = false; fullViews = 0; rejected = 0; lost = false; ids = []; logp = []; seen = []; miss = []; pinned = []; endLocked = false; startLocked = false; visible = []; assign() }
    func undo() {
        if sequenceMode { guard let c = apOrder.popLast() else { return }; apFirst[c] = nil; apHits[c] = nil; apSure[c] = nil; apLate.remove(c); assign(); return }
        guard !ids.isEmpty else { return }
        ids.removeLast(); logp.removeLast(); seen.removeLast(); miss.removeLast(); pinned.removeLast(); endLocked = false; assign()
    }

    // MARK: frame input

    /// rel: how much this corner's reading and order are trusted. Tristan's rule (2026-11-07): cards in the middle
    /// of the visible spread are reliable; at its sides, neighbouring indexes may be hidden, so the order seen there
    /// (and the reading) is less sure.
    private struct Item { var w: [Float]; var paired: Bool; var accepted: Bool; var rel: Float = 1 }
    static let edgeRel: [Float] = [0.4, 0.7]   // outermost card, next one in (runs of 4+ cards)

    // MARK: first-appearance order (2026-11-09)
    // Dealing one at a time, or spreading hand to hand as you go: there is no spread to line up yet, so the order is
    // WHEN each card first shows. A card "appears" once read sure in `apNeed` frames within `apWindow` seconds.
    // The scanner keeps both orders and shows the first-appearance one when the spatial order holds < 60% of the
    // cards that appeared (back to spatial at 80%). Check-up on the 24 scan clips: 741 -> 884 cards in order
    // (docs/STACK_SCANNING.md, scripts/scan_mode_choice.py).
    @Published private(set) var sequenceMode = false
    nonisolated(unsafe) static var appearanceOrder = true
    static let apNeed = 2, apWindow: TimeInterval = 1.0, apSureGap: TimeInterval = 0.5
    private var apHits: [Card: [TimeInterval]] = [:]
    private var apFirst: [Card: TimeInterval] = [:]
    private var apOrder: [Card] = []
    private var apSure: [Card: Int] = [:]          // frames read sure, for "sure" in this mode
    private var apIDs: [Card: UUID] = [:]
    // Late appearers (2026-11-09, Tristan's deal test): a card read sure once when dealt, then confirmed much later when
    // it peeks out from under the pile, lands at the END of the order, which is wrong. On the deal/knee clips 13 of 29
    // such cards were misplaced (runs/scan_checkup2/late_cards4.py); no timing rule places them right more often
    // than appending does, so they stay where they land but are never "sure", and the app says they appeared late.
    private var apEver: [Card: TimeInterval] = [:]  // first sure read ever
    private var apLate: Set<Card> = []
    private var clock: TimeInterval = 0
    /// Cards in first-appearance order (whatever the mode).
    var appearanceCards: [Card] { apOrder }
    /// Cards that were read long before they were confirmed: their place in the shown order may be too late.
    var appearedLate: [Card] { apOrder.filter { apLate.contains($0) } }

    /// Feed one frame. `at`: the frame's time in seconds (video time in replays); default: now.
    func feed(_ cards: [SeenCard], at t: TimeInterval? = nil) {
        let now = t ?? ProcessInfo.processInfo.systemUptime
        clock = now
        var thisFrame: Set<Card> = []
        for c in cards where c.isCorner && c.accepted {
            guard let card = Card(c.reading.card), !thisFrame.contains(card) else { continue }
            thisFrame.insert(card)
            apSure[card, default: 0] += 1
            if apEver[card] == nil { apEver[card] = now }
            let h = (apHits[card] ?? []).filter { now - $0 <= Self.apWindow } + [now]
            apHits[card] = h
            if apFirst[card] == nil, h.count >= Self.apNeed, apOrder.count < deckSize {
                apFirst[card] = h[0]; apOrder.append(card)
                if h[0] - (apEver[card] ?? h[0]) > Self.apWindow { apLate.insert(card) }
            }
        }
        feedSpatial(cards)
        guard Self.appearanceOrder else { return }
        let was = sequenceMode
        let a = Double(apOrder.count), sp = Double(ids.count)
        if !sequenceMode, apOrder.count >= 4, sp < 0.6 * a { sequenceMode = true }
        else if sequenceMode, sp >= 0.8 * a { sequenceMode = false }
        if sequenceMode || was != sequenceMode { assign() }
        if sequenceMode { visible = Set(apOrder.indices.filter { thisFrame.contains(apOrder[$0]) }) }
    }

    private func feedSpatial(_ cards: [SeenCard]) {
        let run = window(cards)
        guard !run.isEmpty else { if !visible.isEmpty { visible = [] }; return }
        if fullView(run) { return }
        let n = ids.count
        let a = align(run)
        let aStrong = a.ops.filter { if case .match(_, _, let s) = $0 { return s >= 1.0 }; return false }.count
        let b = n > 0 && run.count > 2 && aStrong < 2 ? align(run.reversed()) : nil   // spread direction flipped?
        let best = (b != nil && b!.score > a.score) ? b! : a
        let items = (b != nil && b!.score > a.score) ? Array(run.reversed()) : run
        // Use the frame only if it lines up: enough strong matches (or a first / single new card).
        let strong = best.ops.filter { if case .match(_, _, let s) = $0 { return s >= 1.0 }; return false }.count
        let inserts = best.ops.filter { if case .insert = $0 { return true }; return false }.count
        if n == 0 {
            guard items.contains(where: \.accepted) else { return }
        } else if strong < min(items.count > 3 ? 2 : 1, n) {   // never ask for more matches than the stack has places
            // a single sure card that matches nothing: a new card dealt on top
            guard items.count == 1, items[0].accepted, inserts == 1, !endLocked, n < deckSize else {
                visible = []; rejected += 1; if rejected >= 12, !lost { lost = true }
                // A tiny start (1-2 places, e.g. one card glimpsed before the spread came into view) that nothing lines
                // up with: start again from this frame instead of rejecting the whole scan.
                if n <= 2, rejected >= 12, run.count >= 3, run.contains(where: \.accepted) {
                    reset(); apply(align(run).ops, run)
                }
                return
            }
        }
        rejected = 0; if lost { lost = false }
        apply(best.ops, items)
    }

    /// One frame shows (nearly) the whole deck: solve it from that frame (2026-11-08). One reading per place, each card
    /// once (assign), so a few misreads are fixed by elimination. A first or more complete full view replaces the places;
    /// a full view with the same number of places adds its evidence place by place (direction checked). Returns false
    /// to fall back to stitching (fewer corners than places we already have).
    private func fullView(_ run: [Item]) -> Bool {
        guard Self.fullViewSolve, run.count >= Int((Float(deckSize) * 0.9).rounded(.up)), run.count <= deckSize else { return false }
        let t = Self.temper
        func ev(_ it: Item) -> [Float] { it.w.map { t * log($0 + 1e-5) } }
        if fullViews > 0, run.count == ids.count {
            // same places: which direction lines up with what we have?
            func fit(_ r: [Item]) -> Float { zip(0..<r.count, r).map { i, it in log(zip(posterior(i), it.w).map(*).reduce(0, +) + 1e-6) }.reduce(0, +) }
            let items = fit(run) >= fit(run.reversed()) ? run : Array(run.reversed())
            for (i, it) in items.enumerated() { logp[i] = zip(logp[i], ev(it)).map(+); seen[i] += 1 }
        } else if fullViews == 0 || run.count > ids.count {
            ids = run.map { _ in UUID() }; logp = run.map(ev); seen = run.map { _ in 1 }; miss = run.map { _ in 0 }; pinned = run.map { _ in nil }
        } else { return false }
        fullViews += 1; rejected = 0; lost = false
        if ids.count == deckSize { endLocked = true; startLocked = true }
        visible = Set(0..<ids.count)
        assign()
        return true
    }

    /// Corners on the spread's line, sorted along it, as 52-card probability vectors.
    private func window(_ cards: [SeenCard]) -> [Item] {
        let cs = cards.filter { $0.isCorner && $0.reading.rankProbs.count == 13 && $0.reading.suitProbs.count == 4 }
        guard !cs.isEmpty else { return [] }
        let pts = cs.map { CGPoint(x: $0.rect.midX, y: $0.rect.midY) }
        let h = cs.map { max($0.rect.width, $0.rect.height) }.sorted()[cs.count / 2]
        var order = Array(cs.indices)
        var straight = true
        if pts.count >= 3 {
            // Straight spread: corners near one line, sorted along it (other row / upside-down corners left out).
            let mx = pts.map(\.x).reduce(0, +) / CGFloat(pts.count), my = pts.map(\.y).reduce(0, +) / CGFloat(pts.count)
            var sxx: CGFloat = 0, syy: CGFloat = 0, sxy: CGFloat = 0
            for p in pts { sxx += (p.x - mx) * (p.x - mx); syy += (p.y - my) * (p.y - my); sxy += (p.x - mx) * (p.y - my) }
            let ang = 0.5 * atan2(2 * sxy, sxx - syy)
            var v = CGVector(dx: cos(ang), dy: sin(ang))
            if abs(v.dx) >= 0.5 ? v.dx < 0 : v.dy < 0 { v = CGVector(dx: -v.dx, dy: -v.dy) }
            let near = cs.indices.filter { abs((pts[$0].x - mx) * -v.dy + (pts[$0].y - my) * v.dx) <= 1.3 * h }
            // Curved fan (many corners off the line): follow the corners neighbour to neighbour instead.
            let chain = Self.curvedOrder && near.count < Int(0.85 * Double(cs.count)) ? Self.chainOrder(pts, h: h) : []
            if chain.count > near.count {
                order = chain; straight = false
                let a = pts[chain[0]], b = pts[chain[chain.count - 1]], len = max(1, hypot(b.x - a.x, b.y - a.y))
                axis = CGVector(dx: (b.x - a.x) / len, dy: (b.y - a.y) / len)
            } else { axis = v; order = near }
        }
        if straight { let along = { (i: Int) in pts[i].x * self.axis.dx + pts[i].y * self.axis.dy }; order.sort { along($0) < along($1) } }
        var items = order.map { i in
            let r = cs[i].reading
            var w = [Float](repeating: 0, count: 52)
            for s in 0..<4 { for k in 0..<13 { w[s * 13 + k] = r.suitProbs[s] * r.rankProbs[k] } }
            let t = w.reduce(0, +); if t > 0 { w = w.map { $0 / t } }
            return Item(w: w, paired: cs[i].pair != nil, accepted: cs[i].accepted)
        }
        if items.count >= 4 {
            for (d, r) in Self.edgeRel.enumerated() where d < items.count / 2 {
                items[d].rel = min(items[d].rel, r); items[items.count - 1 - d].rel = min(items[items.count - 1 - d].rel, r)
            }
            // A paired card (both indexes visible) at the very end is the spread's last card: fully visible, trusted.
            if items[0].paired { items[0].rel = 1 }
            if items[items.count - 1].paired { items[items.count - 1].rel = 1 }
        }
        return items
    }

    /// Corners in order along the spread, for straight spreads and curved fans alike (2026-11-08; the old straight-line
    /// fit dropped most corners of a fan). Corners within 2.5 index heights of each other are linked; the biggest linked
    /// group is the spread (the other row of upside-down corners is about a card length away, so it is left out). The
    /// group is walked nearest neighbour to nearest neighbour from one end, then pointed left-to-right (or top-to-bottom).
    static func chainOrder(_ pts: [CGPoint], h: CGFloat) -> [Int] {
        let n = pts.count
        guard n > 2 else {
            return n == 2 && (abs(pts[1].x - pts[0].x) >= abs(pts[1].y - pts[0].y) ? pts[1].x < pts[0].x : pts[1].y < pts[0].y) ? [1, 0] : Array(0..<n)
        }
        func d(_ i: Int, _ j: Int) -> CGFloat { hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y) }
        // biggest linked group
        var group = [Int](repeating: -1, count: n), best: [Int] = [], comps: [[Int]] = []
        for s in 0..<n where group[s] < 0 {
            var comp = [s], k = 0; group[s] = s
            while k < comp.count { let i = comp[k]; k += 1; for j in 0..<n where group[j] < 0 && d(i, j) <= 2.5 * h { group[j] = s; comp.append(j) } }
            comps.append(comp); if comp.count > best.count { best = comp }
        }
        // a missed card leaves a double gap: join groups of 3+ within 4.5 index heights (one missing card)
        var joined = Set<Int>(), grew = true
        while grew {
            grew = false
            for (ci, c) in comps.enumerated() where c != best && c.count >= 3 && !joined.contains(ci) && c.contains(where: { i in best.contains { d(i, $0) <= 4.5 * h } }) {
                best += c; joined.insert(ci); grew = true
            }
        }
        // walk from an end: the point farthest from the group's middle, and the point farthest from that; keep the shorter walk
        func walk(_ start: Int) -> ([Int], CGFloat) {
            var path = [start], left = Set(best).subtracting([start]), total: CGFloat = 0
            while let j = left.min(by: { d(path.last!, $0) < d(path.last!, $1) }) { total += d(path.last!, j); path.append(j); left.remove(j) }
            return (path, total)
        }
        let cx = best.map { pts[$0].x }.reduce(0, +) / CGFloat(best.count), cy = best.map { pts[$0].y }.reduce(0, +) / CGFloat(best.count)
        let e1 = best.max { hypot(pts[$0].x - cx, pts[$0].y - cy) < hypot(pts[$1].x - cx, pts[$1].y - cy) }!
        let e2 = best.max { d(e1, $0) < d(e1, $1) }!
        var path = [walk(e1), walk(e2)].min { $0.1 < $1.1 }!.0
        // Tidy the walk (it can skip a corner in a crowded spot and pick it up later): move single corners and reverse
        // stretches while that makes the path shorter (or-opt + 2-opt on an open path).
        func len(_ p: [Int]) -> CGFloat { zip(p, p.dropFirst()).map { d($0, $1) }.reduce(0, +) }
        var cur = len(path), improved = true, rounds = 0
        while improved, rounds < 20 {
            improved = false; rounds += 1
            for i in 0..<path.count {
                var p = path; let x = p.remove(at: i)
                for j in 0...p.count where j != i {
                    var q = p; q.insert(x, at: j); let l = len(q)
                    if l < cur - 0.5 { path = q; cur = l; improved = true; break }
                }
                if improved { break }
            }
            if improved { continue }
            outer: for i in 0..<(path.count - 1) {
                for j in (i + 1)..<path.count {
                    var q = path; q[i...j].reverse(); let l = len(q)
                    if l < cur - 0.5 { path = q; cur = l; improved = true; break outer }
                }
            }
        }
        let a = pts[path[0]], b = pts[path[path.count - 1]]
        if abs(b.x - a.x) >= 0.5 * hypot(b.x - a.x, b.y - a.y) ? b.x < a.x : b.y < a.y { path.reverse() }
        return path
    }

    private enum Op { case match(Int, Int, Float), skip, insert(Int) }   // match(item, place, score), insert(item)

    private func posterior(_ p: Int) -> [Float] {
        let l = logp[p], m = l.max() ?? 0
        let e = l.map { exp($0 - m) }, s = e.reduce(0, +); return e.map { $0 / s }
    }

    private func align(_ run: [Item]) -> (score: Float, ops: [Op]) {
        let n = ids.count, m = run.count, NEG: Float = -1e9
        let Q = (0..<n).map(posterior)
        func sim(_ j: Int, _ p: Int) -> Float {
            var d: Float = 0; for c in 0..<52 { d += run[j].w[c] * Q[p][c] }
            return run[j].rel * max(-0.8, min(4, log(d + 1e-6) + log(52)))   // a misread still costs less than a new place
        }
        func insCost(_ p: Int) -> Float {
            if n >= deckSize { return (p == n && !endLocked) || (p == 0 && !startLocked) ? -3 : NEG }   // full: only at an end, and a weak place makes room
            if p == n && n > 0 { return endLocked ? NEG : -1.2 }   // new card at an end (more than a misread match: -0.8)
            if p == 0 && n > 0 { return startLocked ? NEG : -1.2 }
            return n == 0 ? 0 : -4.0
        }
        var dp = [[Float]](repeating: [Float](repeating: NEG, count: n + 1), count: m + 1)
        var back = [[(Int, Int, Op)?]](repeating: [(Int, Int, Op)?](repeating: nil, count: n + 1), count: m + 1)
        dp[0][0] = 0
        for j in 0...m { for p in 0...n where dp[j][p] > NEG / 2 {
            let base = dp[j][p]
            if j < m, p < n { let s = sim(j, p); if base + s > dp[j + 1][p + 1] { dp[j + 1][p + 1] = base + s; back[j + 1][p + 1] = (j, p, .match(j, p, s)) } }
            // Skipping a place = a card not seen this frame. Free outside the run; cheap next to the run's edge cards
            // (their neighbours' indexes are often hidden); costly in the middle.
            if p < n { let c: Float = (j == 0 || j == m) ? 0 : (m >= 4 && (j == 1 || j == m - 1)) ? -0.25 : -0.7
                if base + c > dp[j][p + 1] { dp[j][p + 1] = base + c; back[j][p + 1] = (j, p, .skip) } }
            if j < m { let c = insCost(p); if c > NEG / 2, base + c > dp[j + 1][p] { dp[j + 1][p] = base + c; back[j + 1][p] = (j, p, .insert(j)) } }
        } }
        var ops: [Op] = [], j = m, p = n
        while let b = back[j][p] { ops.append(b.2); j = b.0; p = b.1 }
        return (dp[m][n], ops.reversed())
    }

    private func apply(_ ops: [Op], _ run: [Item]) {
        var nid: [UUID] = [], nl: [[Float]] = [], ns: [Int] = [], nm: [Int] = [], np: [Card?] = []
        let firstUsed = ops.firstIndex { if case .skip = $0 { return false }; return true } ?? 0
        let lastUsed = ops.lastIndex { if case .skip = $0 { return false }; return true } ?? 0
        var p = 0, vis = Set<Int>(), lastItemPlace: (item: Int, place: Int)?, firstItemPlace: (item: Int, place: Int)?
        let t = Self.temper
        for (k, op) in ops.enumerated() {
            switch op {
            case .match(let j, let q, _):
                nid.append(ids[q]); nl.append(zip(logp[q], run[j].w).map { $0 + t * run[j].rel * log($1 + 1e-5) }); ns.append(seen[q] + 1); nm.append(miss[q]); np.append(pinned[q]); p = q + 1
            case .skip:
                let inside = k > firstUsed && k < lastUsed   // in view this frame, yet not matched
                nid.append(ids[p]); nl.append(logp[p]); ns.append(seen[p]); nm.append(miss[p] + (inside ? 1 : 0)); np.append(pinned[p]); p += 1
                continue
            case .insert(let j):
                nid.append(UUID()); nl.append(run[j].w.map { t * run[j].rel * log($0 + 1e-5) }); ns.append(1); nm.append(0); np.append(nil)
                Haptic.tap()
            }
            vis.insert(nid.count - 1)
            if case .match(let j, _, _) = op { if firstItemPlace == nil { firstItemPlace = (j, nid.count - 1) }; lastItemPlace = (j, nid.count - 1) }
            if case .insert(let j) = op { if firstItemPlace == nil { firstItemPlace = (j, nid.count - 1) }; lastItemPlace = (j, nid.count - 1) }
        }
        // A place mostly skipped while in view was a phantom (a misread added as a new card): drop it.
        var keep = nid.indices.filter { !(nm[$0] >= 2 && nm[$0] > ns[$0] && np[$0] == nil) }
        // More places than cards: drop the least supported place out of view (a phantom at the far edge).
        while keep.count > deckSize, let weakest = keep.filter({ !vis.contains($0) && np[$0] == nil }).min(by: { ns[$0] - 2 * nm[$0] < ns[$1] - 2 * nm[$1] }) {
            keep.removeAll { $0 == weakest }
        }
        if keep.count < nid.count { vis = Set(vis.compactMap { keep.firstIndex(of: $0) }) }
        ids = keep.map { nid[$0] }; logp = keep.map { nl[$0] }; seen = keep.map { ns[$0] }; miss = keep.map { nm[$0] }; pinned = keep.map { np[$0] }
        if let l = lastItemPlace { lastItemPlace = keep.firstIndex(of: l.place).map { (l.item, $0) } }
        if let f = firstItemPlace { firstItemPlace = keep.firstIndex(of: f.place).map { (f.item, $0) } }
        // End of the spread: the run's outermost card shows both indexes and sits at the stack's end.
        if let l = lastItemPlace, l.item == run.count - 1, run[l.item].paired, l.place == ids.count - 1 { endLocked = true }
        if let f = firstItemPlace, f.item == 0, run[f.item].paired, f.place == 0, run.count > 1 { startLocked = true }
        visible = vis
        assign()
    }

    // MARK: cards for places (each card once)

    private func assign() {
        if sequenceMode {
            // Sure = read clearly (3+ frames) AND nothing else appeared within 0.5 s of it: cards that come out
            // together have no reliable order between them (check-up: sure-but-wrong 48 -> 15 of the deal/knee places).
            let t = apOrder.map { apFirst[$0] ?? 0 }
            entries = apOrder.enumerated().map { i, c in
                let id = apIDs[c] ?? UUID(); apIDs[c] = id
                let gap = [i - 1, i + 1].filter { t.indices.contains($0) }.map { abs(t[i] - t[$0]) }.min() ?? 9
                return Entry(id: id, card: c, sure: (apSure[c] ?? 0) >= 99 || ((apSure[c] ?? 0) >= 3 && gap >= Self.apSureGap && !apLate.contains(c)))
            }
            entriesVersion += 1; return
        }
        let n = ids.count
        guard n > 0 else { entries = []; entriesVersion += 1; return }
        let Q = (0..<n).map(posterior)
        var cols: [Int]
        if n <= 52 {
            let cost: [[Double]] = (0..<n).map { i in
                if let c = pinned[i], let k = Self.index(c) { return (0..<52).map { $0 == k ? 0 : 1e6 } }
                return Q[i].map { -log(Double($0) + 1e-9) }
            }
            cols = Self.hungarian(cost)
        } else {
            cols = Q.map { q in q.indices.max { q[$0] < q[$1] }! }
        }
        let old = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        entries = (0..<n).map { i in
            let c = cols[i], card = Self.card(c)
            let best = Q[i].indices.max { Q[i][$0] < Q[i][$1] }!
            let sure = pinned[i] != nil || (seen[i] >= 2 && Q[i][c] >= 0.9 && best == c)
            var e = old[ids[i]] ?? Entry(id: ids[i], card: card, sure: sure)
            e.card = card; e.sure = sure; e.checked = pinned[i] != nil
            return e
        }
        entriesVersion += 1
    }

    static func index(_ c: Card) -> Int? {
        guard let s = CardReader.suits.firstIndex(of: c.suit), let r = CardReader.ranks.firstIndex(of: c.rank) else { return nil }
        return s * 13 + r
    }
    static func card(_ k: Int) -> Card { Card(CardReader.ranks[k % 13] + CardReader.suits[k / 13])! }

    /// Min-cost assignment of n rows to distinct columns (n <= m). Returns the column of each row.
    static func hungarian(_ a: [[Double]]) -> [Int] {
        let n = a.count, m = a[0].count
        var u = [Double](repeating: 0, count: n + 1), v = [Double](repeating: 0, count: m + 1)
        var p = [Int](repeating: 0, count: m + 1), way = [Int](repeating: 0, count: m + 1)
        for i in 1...n {
            p[0] = i; var j0 = 0
            var minv = [Double](repeating: .infinity, count: m + 1), used = [Bool](repeating: false, count: m + 1)
            repeat {
                used[j0] = true; let i0 = p[j0]; var delta = Double.infinity, j1 = 0
                for j in 1...m where !used[j] {
                    let cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                    if cur < minv[j] { minv[j] = cur; way[j] = j0 }
                    if minv[j] < delta { delta = minv[j]; j1 = j }
                }
                for j in 0...m { if used[j] { u[p[j]] += delta; v[j] -= delta } else { minv[j] -= delta } }
                j0 = j1
            } while p[j0] != 0
            repeat { let j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while j0 != 0
        }
        var ans = [Int](repeating: 0, count: n)
        for j in 1...m where p[j] != 0 { ans[p[j] - 1] = j - 1 }
        return ans
    }

    // MARK: results

    func state(_ i: Int) -> State { entries[i].sure ? .good : .verify }
    var toVerify: Int { entries.filter { !$0.sure }.count }
    var complete: Bool { entries.count == deckSize && toVerify == 0 }

    var sections: [Section] {
        var out: [Section] = [], i = 0
        while i < entries.count {
            if entries[i].sure { i += 1; continue }
            var j = i; while j + 1 < entries.count, !entries[j + 1].sure { j += 1 }
            out.append(Section(start: i, end: j, before: i > 0 ? entries[i - 1].card : nil, after: j + 1 < entries.count ? entries[j + 1].card : nil))
            i = j + 1
        }
        return out
    }

    var missing: [Card] {
        guard fullDeck else { return [] }
        let have = Set(entries.map(\.card)); return Card.deck.filter { !have.contains($0) }
    }

    /// Only Mnemonica gives a suggestion now (the assignment already uses "each card once").
    func suggestion(_ i: Int) -> Card? {
        guard let x = expected(i, mnemonica), x != entries[i].card else { return nil }
        return x
    }

    func accept(_ c: Card, at i: Int) {
        if sequenceMode {
            guard apOrder.indices.contains(i) else { return }
            let old = apOrder[i]
            if old != c { apSure[old] = nil; apFirst[old] = nil; apHits[old] = nil; apLate.remove(old) }
            if let j = apOrder.firstIndex(of: c), j != i { apOrder.remove(at: j); apOrder[j < i ? i - 1 : i] = c } else { apOrder[i] = c }
            apSure[c] = 99; apLate.remove(c); assign(); Haptic.tap(); return
        }
        guard pinned.indices.contains(i) else { return }
        for k in pinned.indices where pinned[k] == c { pinned[k] = nil }
        pinned[i] = c; assign(); Haptic.tap()
    }

    func compare(_ stack: [Card]) -> (cutAt: Card, wrong: Set<Int>)? {
        guard let i = entries.indices.first(where: { entries[$0].sure }), let k = stack.firstIndex(of: entries[i].card) else { return nil }
        let offset = k - i; var wrong = Set<Int>()
        for (n, e) in entries.enumerated() where stack[((n + offset) % 52 + 52) % 52] != e.card { wrong.insert(n) }
        return (stack[((offset % 52) + 52) % 52], wrong)
    }

    /// Is this a known stack (cut or not)? Each neighbouring pair either follows the stack (a -> next card) or not.
    /// A random deck follows by chance 1 time in 51; a real stack nearly always, allowing ~10% misreads.
    struct StackMatch { let probability: Double; let cut: Int; let top: Card; let follows: Int; let pairs: Int }
    func match(_ stack: [Card]) -> StackMatch? {
        let pos = entries.compactMap { stack.firstIndex(of: $0.card) }
        guard pos.count >= 3 else { return nil }
        var follows = 0
        for k in 1..<pos.count where pos[k] == (pos[k - 1] + 1) % stack.count { follows += 1 }
        let pairs = pos.count - 1, pm = 0.9, pr = 1.0 / 51
        let llr = Double(follows) * log(pm / pr) + Double(pairs - follows) * log((1 - pm) / (1 - pr))
        let z = llr + log(0.2 / 0.8)
        let probability = z > 30 ? 1 : 1 / (1 + exp(-z))
        var offsets: [Int: Int] = [:]
        for (i, p) in pos.enumerated() { offsets[((p - i) % stack.count + stack.count) % stack.count, default: 0] += 1 }
        let cut = offsets.max { $0.value < $1.value }!.key
        return StackMatch(probability: probability, cut: cut, top: stack[cut], follows: follows, pairs: pairs)
    }

    func expected(_ i: Int, _ stack: [Card]) -> Card? {
        guard let m = match(stack), m.probability >= 0.95 else { return nil }
        return stack[(m.cut + i) % stack.count]
    }

    var text: String { entries.indices.map { "\($0 + 1). \(entries[$0].card.pretty)\(entries[$0].sure ? "" : " (verify)")" }.joined(separator: "\n") }

    #if DEBUG
    func previewFill() {
        for (k, c) in mnemonica.prefix(40).enumerated() {
            var l = [Float](repeating: -9, count: 52); l[Self.index(c)!] = k % 9 == 4 ? -8.5 : 0
            ids.append(UUID()); logp.append(l); seen.append(3); miss.append(0); pinned.append(nil)
        }
        visible = [30, 31, 32, 33, 34]; assign()
    }
    #endif
}

/// Mnemonica (Juan Tamariz), top to bottom. Confirmed by Tristan (2026-11-04).
let mnemonica: [Card] = ["4C", "2H", "7D", "3C", "4H", "6D", "AS", "5H", "9S", "2S", "QH", "3D", "QC", "8H", "6S", "5S", "9H", "KC", "2D", "JH", "3S", "8S", "6H", "10C", "5D", "KD", "2C", "3H", "8D", "5C", "KS", "JD", "8C", "10S", "KH", "JC", "7S", "10H", "AD", "4S", "7H", "4D", "AC", "9C", "JS", "QD", "7C", "QS", "10D", "6C", "AH", "9D"].compactMap { Card($0) }

