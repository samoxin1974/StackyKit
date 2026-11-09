package com.stacky

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Mnemonica (Juan Tamariz), top to bottom. */
val MNEMONICA: List<String> = ("4C 2H 7D 3C 4H 6D AS 5H 9S 2S QH 3D QC 8H 6S 5S 9H KC 2D JH 3S 8S 6H 10C 5D KD 2C 3H " +
    "8D 5C KS JD 8C 10S KH JC 7S 10H AD 4S 7H 4D AC 9C JS QD 7C QS 10D 6C AH 9D").split(" ")

internal fun cardIndex(code: String) = SUITS.indexOf(code.takeLast(1)) * 13 + RANKS.indexOf(code.dropLast(1))
internal fun cardCode(k: Int) = RANKS[k % 13] + SUITS[k / 13]

internal data class Pt(val x: Double, val y: Double)

/** The principal-axis line through the corners, and which lie near it. */
internal fun straightLine(pts: List<Pt>, h: Double): Pair<Pt, List<Int>> {
    val n = pts.size
    val mx = pts.sumOf { it.x } / n; val my = pts.sumOf { it.y } / n
    var sxx = 0.0; var syy = 0.0; var sxy = 0.0
    for (p in pts) { sxx += (p.x - mx) * (p.x - mx); syy += (p.y - my) * (p.y - my); sxy += (p.x - mx) * (p.y - my) }
    val ang = 0.5 * atan2(2 * sxy, sxx - syy)
    var v = Pt(cos(ang), sin(ang))
    if (if (abs(v.x) >= 0.5) v.x < 0 else v.y < 0) v = Pt(-v.x, -v.y)
    val near = pts.indices.filter { abs((pts[it].x - mx) * -v.y + (pts[it].y - my) * v.x) <= 1.3 * h }
    return v to near
}

/** Orders corners along a curved spread by following them neighbour to neighbour. */
internal fun chainOrder(pts: List<Pt>, h: Double): List<Int> {
    val n = pts.size
    if (n <= 2) {
        if (n == 2 && (if (abs(pts[1].x - pts[0].x) >= abs(pts[1].y - pts[0].y)) pts[1].x < pts[0].x else pts[1].y < pts[0].y)) return listOf(1, 0)
        return pts.indices.toList()
    }
    fun d(i: Int, j: Int) = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
    val group = IntArray(n) { -1 }; val comps = ArrayList<MutableList<Int>>(); var best: MutableList<Int> = ArrayList()
    for (s in 0 until n) {
        if (group[s] >= 0) continue
        val comp = mutableListOf(s); group[s] = s
        var k = 0
        while (k < comp.size) { for (j in 0 until n) if (group[j] < 0 && d(comp[k], j) <= 2.5 * h) { group[j] = s; comp.add(j) }; k++ }
        comps.add(comp); if (comp.size > best.size) best = comp
    }
    // A missed card leaves a double gap: join groups of 3+ within 4.5 index heights (one missing card).
    val joined = HashSet<Int>()
    val main = best
    var grew = true
    var all = ArrayList(best)
    while (grew) {
        grew = false
        for ((ci, c) in comps.withIndex()) if (c !== main && c.size >= 3 && ci !in joined && c.any { i -> all.any { j -> d(i, j) <= 4.5 * h } }) {
            all = ArrayList(all + c); joined.add(ci); grew = true
        }
    }
    val members = all
    fun walk(start: Int): Pair<MutableList<Int>, Double> {
        val path = mutableListOf(start); val left = LinkedHashSet(members); left.remove(start); var total = 0.0
        while (left.isNotEmpty()) {
            var bj = -1; var bd = Double.POSITIVE_INFINITY
            for (j in left) { val dd = d(path.last(), j); if (dd < bd) { bd = dd; bj = j } }
            total += bd; path.add(bj); left.remove(bj)
        }
        return path to total
    }
    val cx = members.sumOf { pts[it].x } / members.size; val cy = members.sumOf { pts[it].y } / members.size
    var e1 = members[0]; for (i in members) if (hypot(pts[i].x - cx, pts[i].y - cy) > hypot(pts[e1].x - cx, pts[e1].y - cy)) e1 = i
    var e2 = members[0]; for (i in members) if (d(e1, i) > d(e1, e2)) e2 = i
    val w1 = walk(e1); val w2 = walk(e2)
    var path: List<Int> = if (w1.second <= w2.second) w1.first else w2.first
    fun len(p: List<Int>): Double { var s = 0.0; for (k in 1 until p.size) s += d(p[k - 1], p[k]); return s }
    var cur = len(path); var improved = true; var rounds = 0
    while (improved && rounds < 20) {
        improved = false; rounds++
        var i = 0
        while (i < path.size && !improved) {
            val p = path.toMutableList(); val x = p.removeAt(i)
            for (j in 0..p.size) {
                if (j == i) continue
                val q = p.toMutableList(); q.add(j, x); val l = len(q)
                if (l < cur - 0.5) { path = q; cur = l; improved = true; break }
            }
            i++
        }
        if (improved) continue
        outer@ for (a in 0 until path.size - 1) for (b in a + 1 until path.size) {
            val q = path.subList(0, a) + path.subList(a, b + 1).reversed() + path.subList(b + 1, path.size); val l = len(q)
            if (l < cur - 0.5) { path = q; cur = l; improved = true; break@outer }
        }
    }
    val a = pts[path.first()]; val b = pts[path.last()]
    if (if (abs(b.x - a.x) >= 0.5 * hypot(b.x - a.x, b.y - a.y)) b.x < a.x else b.y < a.y) path = path.reversed()
    return path
}

/** Min-cost assignment (rows <= cols): the column for each row. */
internal fun hungarian(a: List<DoubleArray>): IntArray {
    val n = a.size; val m = a[0].size; val inf = Double.POSITIVE_INFINITY
    val u = DoubleArray(n + 1); val v = DoubleArray(m + 1); val p = IntArray(m + 1); val way = IntArray(m + 1)
    for (i in 1..n) {
        p[0] = i; var j0 = 0
        val minv = DoubleArray(m + 1) { inf }; val used = BooleanArray(m + 1)
        do {
            used[j0] = true; val i0 = p[j0]; var delta = inf; var j1 = 0
            for (j in 1..m) if (!used[j]) {
                val c = a[i0 - 1][j - 1] - u[i0] - v[j]
                if (c < minv[j]) { minv[j] = c; way[j] = j0 }
                if (minv[j] < delta) { delta = minv[j]; j1 = j }
            }
            for (j in 0..m) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
            j0 = j1
        } while (p[j0] != 0)
        do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
    }
    val ans = IntArray(n)
    for (j in 1..m) if (p[j] != 0) ans[p[j] - 1] = j - 1
    return ans
}

/** A card's two indexes (upright + upside down) read alike, 1.5-10 index heights apart, merged into one; evidence multiplied. */
internal fun pairsOf(cards: List<Seen>): List<Pair<Int, Int>> {
    val cand = ArrayList<Triple<Double, Int, Int>>()
    for (i in cards.indices) for (j in i + 1 until cards.size) {
        val a = cards[i]; val b = cards[j]
        if (a.code != b.code) continue
        val ha = a.rect.size; val hb = b.rect.size
        if (maxOf(ha / hb, hb / ha) > 1.7) continue
        val d = hypot(a.rect.cx - b.rect.cx, a.rect.cy - b.rect.cy)
        if (d >= 1.5 * maxOf(ha, hb) && d <= 10 * maxOf(ha, hb)) cand.add(Triple(d, i, j))
    }
    val used = HashSet<Int>(); val pairs = ArrayList<Pair<Int, Int>>()
    for ((_, i, j) in cand.sortedBy { it.first }) if (i !in used && j !in used) { used.add(i); used.add(j); pairs.add(i to j) }
    return pairs
}

internal fun mul(x: DoubleArray, y: DoubleArray): DoubleArray {
    val q = DoubleArray(x.size) { maxOf(x[it] * y[it], 1e-12) }; val t = q.sum()
    return DoubleArray(q.size) { q[it] / t }
}

internal fun merged(p: Seen, s: Seen, accept: Double): Seen {
    val rp = mul(p.rankProbs, s.rankProbs); val sp = mul(p.suitProbs, s.suitProbs)
    val (code, conf, _) = reading(rp, sp)
    return Seen(code, conf, rp, sp, p.corner, p.rect, conf >= accept, maxOf(p.frames, s.frames), p.track, s.rect)
}

/** One-shot pairing for a single photo: the shown corner faces like the other cards. */
internal fun pairCorners(cards: List<Seen>, accept: Double): List<Seen> {
    val pairs = pairsOf(cards); val used = pairs.flatMap { listOf(it.first, it.second) }.toSet()
    var dx = 1.0; var dy = 1.0; var sx = 0.0; var sy = 0.0
    for ((i, j) in pairs) {
        var vx = cards[j].rect.cx - cards[i].rect.cx; var vy = cards[j].rect.cy - cards[i].rect.cy
        val n = hypot(vx, vy).takeIf { it > 0 } ?: 1.0; vx /= n; vy /= n
        if (vx * dx + vy * dy < 0) { vx = -vx; vy = -vy }
        sx += vx; sy += vy
    }
    if (hypot(sx, sy) > 0.5) { dx = sx; dy = sy }
    val out = cards.filterIndexed { k, _ -> k !in used }.toMutableList()
    for ((i, j) in pairs) {
        val vx = cards[j].rect.cx - cards[i].rect.cx; val vy = cards[j].rect.cy - cards[i].rect.cy
        val (p, s) = if (vx * dx + vy * dy >= 0) cards[i] to cards[j] else cards[j] to cards[i]
        out.add(merged(p, s, accept))
    }
    return out
}

/** Order the corners along the spread: a straight line when most sit on one, else follow them neighbour to neighbour. */
internal fun spreadOrder(cards: List<Seen>): List<Int> {
    val pts = cards.map { Pt(it.rect.cx, it.rect.cy) }; val n = pts.size
    if (n < 3) return cards.indices.sortedBy { pts[it].x }
    val h = cards.map { it.rect.size }.sorted()[n shr 1]
    val (v, near) = straightLine(pts, h)
    val chain = if (near.size < (0.85 * n).toInt()) chainOrder(pts, h) else emptyList()
    if (chain.size > near.size) return chain
    return near.sortedBy { pts[it].x * v.x + pts[it].y * v.y }
}

/** Whether an order is (part of) a cut stack. */
data class StackMatch(
    /** How likely it is the stack, 0-1. */
    val probability: Double,
    /** The card on top after the cut, and its place in the stack (0-based). */
    val topCard: String,
    val cut: Int,
    /** Neighbouring pairs that follow the stack, out of all neighbouring pairs. */
    val follows: Int,
    val pairs: Int,
)

/** Same rule as the iOS app: follows vs pairs, about 10% misreads allowed. */
internal fun matchStack(codes: List<String>, stack: List<String>): StackMatch? {
    val pos = codes.map { stack.indexOf(it) }.filter { it >= 0 }
    if (pos.size < 3) return null
    var follows = 0
    for (k in 1 until pos.size) if (pos[k] == (pos[k - 1] + 1) % stack.size) follows++
    val pairs = pos.size - 1; val pm = 0.9; val pr = 1.0 / 51
    val z = follows * Math.log(pm / pr) + (pairs - follows) * Math.log((1 - pm) / (1 - pr)) + Math.log(0.2 / 0.8)
    val probability = if (z > 30) 1.0 else 1 / (1 + Math.exp(-z))
    val off = LinkedHashMap<Int, Int>()
    pos.forEachIndexed { i, p -> val o = ((p - i) % stack.size + stack.size) % stack.size; off[o] = (off[o] ?: 0) + 1 }
    var cut = 0; var bc = -1
    for ((o, c) in off) if (c > bc) { bc = c; cut = o }
    return StackMatch(probability, stack[cut], cut, follows, pairs)
}
