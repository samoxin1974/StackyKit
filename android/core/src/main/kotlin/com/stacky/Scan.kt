package com.stacky

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Live reading and stack scanning, ported line for line from StackyKit for iOS (FrameMemory.swift, CornerPairing.swift,
// StackEngine.swift) by way of the website's scan.js. Keep the three in step.

// ---------------- frame memory ----------------

internal class Patch(val w: Int, val h: Int, val px: FloatArray)

internal class GrayFrame(private val img: StackyImage?) {
    private val w = img?.width ?: 0
    private val h = img?.height ?: 0
    val has get() = img != null

    fun region(r: Rect): Patch? {
        val im = img ?: return null
        val x0 = max(0, r.x.roundToInt()); val y0 = max(0, r.y.roundToInt())
        val x1 = min(w, (r.x + r.w).roundToInt()); val y1 = min(h, (r.y + r.h).roundToInt())
        if (x1 - x0 < 2 || y1 - y0 < 2) return null
        val ww = x1 - x0; val hh = y1 - y0
        return Patch(ww, hh, FloatArray(ww * hh) { im.gray((y0 + it / ww) * w + x0 + it % ww) })
    }

    /** Best normalised cross-correlation of the patch near (cx, cy), searching 10% of its size around. */
    fun bestNCC(t: Patch, cx: Double, cy: Double): Double? {
        val im = img ?: return null
        val n = t.w * t.h
        val mean = t.px.sum() / n
        val tz = FloatArray(n) { t.px[it] - mean }
        val tn = sqrt(tz.sumOf { (it * it).toDouble() })
        if (tn / sqrt(n.toDouble()) < 1) return null
        val r = max(2, (min(t.w, t.h) * 0.1).roundToInt())
        val left = (cx - t.w / 2.0).roundToInt() - r; val top = (cy - t.h / 2.0).roundToInt() - r
        val sx0 = max(0, left); val sy0 = max(0, top); val sx1 = min(w, left + t.w + 2 * r); val sy1 = min(h, top + t.h + 2 * r)
        if (sx1 - sx0 < t.w || sy1 - sy0 < t.h) return null
        val aw = sx1 - sx0; val ah = sy1 - sy0
        val a = FloatArray(aw * ah) { im.gray((sy0 + it / aw) * w + sx0 + it % aw) }
        var best = -1.0
        for (oy in 0..ah - t.h) for (ox in 0..aw - t.w) {
            var s = 0.0; var s2 = 0.0; var st = 0.0
            for (y in 0 until t.h) {
                val row = (oy + y) * aw + ox; val tr = y * t.w
                for (x in 0 until t.w) { val v = a[row + x].toDouble(); s += v; s2 += v * v; st += v * tz[tr + x] }
            }
            val varI = s2 - s * s / n
            if (varI > 1e-6) best = max(best, st / (sqrt(varI) * tn))
        }
        return best
    }
}

private fun iou(a: Rect, b: Rect): Double {
    val iw = min(a.maxX, b.maxX) - max(a.x, b.x); val ih = min(a.maxY, b.maxY) - max(a.y, b.y)
    if (iw <= 0 || ih <= 0) return 0.0
    val i = iw * ih
    return i / (a.w * a.h + b.w * b.h - i + 1e-9)
}

/** Follows each corner from frame to frame and averages its reading, so the output doesn't flicker. */
internal class FrameMemory {
    private class Track(val id: Int, var box: Rect, var template: Patch?, var missed: Int, var rp: List<DoubleArray>, var sp: List<DoubleArray>)

    private val window = 12; private val maxMissed = 4; private val iouMin = 0.3; private val sizeMax = 1.5
    private val nccMin = 0.5; private val splitConf = 0.7
    private var tracks = ArrayList<Track>()
    private var nextID = 0

    fun reset() { tracks = ArrayList(); nextID = 0 }

    private fun mean(t: Track): Seen? {
        if (t.rp.isEmpty()) return null
        val rp = DoubleArray(13) { i -> t.rp.sumOf { it[i] } / t.rp.size }
        val sp = DoubleArray(4) { i -> t.sp.sumOf { it[i] } / t.sp.size }
        val (code, conf, _) = reading(rp, sp)
        return Seen(code, conf, rp, sp, 1f, t.box)
    }

    /** Returns, for each detection, its steadied reading, with its track and how many frames it has been seen. */
    fun step(dets: List<Seen>, frame: GrayFrame, accept: Double): List<Seen> {
        val pairs = ArrayList<Triple<Double, Int, Int>>()
        tracks.forEachIndexed { ti, t -> dets.forEachIndexed { di, d -> pairs.add(Triple(iou(t.box, d.rect), ti, di)) } }
        pairs.sortByDescending { it.first }
        val usedT = HashSet<Int>(); val usedD = HashSet<Int>(); val result = HashMap<Int, Seen>()
        for ((v, ti, di) in pairs) {
            if (v < iouMin || ti in usedT || di in usedD) continue
            val t = tracks[ti]; val a = t.box; val b = dets[di].rect
            if (max(a.w / b.w, b.w / a.w) > sizeMax || max(a.h / b.h, b.h / a.h) > sizeMax) continue
            val tpl = t.template
            if (tpl != null && frame.has) { val n = frame.bestNCC(tpl, b.cx, b.cy); if (n != null && n < nccMin) continue }
            usedT.add(ti); usedD.add(di)
            val r = dets[di]
            if (r.conf >= splitConf) { val m = mean(t); if (m != null && m.conf >= splitConf && m.code != r.code) { t.rp = emptyList(); t.sp = emptyList() } }
            t.box = b; t.template = frame.region(b); t.missed = 0
            t.rp = (t.rp + listOf(r.rankProbs)).takeLast(window); t.sp = (t.sp + listOf(r.suitProbs)).takeLast(window)
            val shown = if (r.conf < splitConf && t.rp.size > 1) (mean(t) ?: r) else r
            result[di] = Seen(shown.code, shown.conf, shown.rankProbs, shown.suitProbs, r.corner, r.rect, shown.conf >= accept, t.rp.size, t.id)
        }
        tracks.forEachIndexed { ti, t -> if (ti !in usedT) t.missed++ }
        tracks = ArrayList(tracks.filter { it.missed <= maxMissed })
        dets.forEachIndexed { di, d ->
            if (di in usedD) return@forEachIndexed
            val t = Track(nextID++, d.rect, frame.region(d.rect), 0, listOf(d.rankProbs), listOf(d.suitProbs))
            tracks.add(t)
            result[di] = Seen(d.code, d.conf, d.rankProbs, d.suitProbs, d.corner, d.rect, d.conf >= accept, 1, t.id)
        }
        return dets.indices.map { result[it]!! }
    }
}

// ---------------- corner pairing ----------------

/** Merges a card's two indexes across frames, keeping the direction the cards face steady. */
internal class CornerPairing {
    private var dir = Pt(1.0, 1.0)
    private var lastShown = HashMap<String, Pt>()

    fun reset() { dir = Pt(1.0, 1.0); lastShown = HashMap() }

    fun merge(corners: List<Seen>, accept: Double): List<Seen> {
        val pairs = pairsOf(corners); val used = pairs.flatMap { listOf(it.first, it.second) }.toSet()
        fun unit(a: Seen, b: Seen): Pt { val dx = b.rect.cx - a.rect.cx; val dy = b.rect.cy - a.rect.cy; val n = max(1e-6, hypot(dx, dy)); return Pt(dx / n, dy / n) }
        var sx = 0.0; var sy = 0.0
        for ((i, j) in pairs) { var v = unit(corners[i], corners[j]); if (v.x * dir.x + v.y * dir.y < 0) v = Pt(-v.x, -v.y); sx += v.x; sy += v.y }
        val sn = hypot(sx, sy)
        if (sn > 0.5) dir = Pt(0.7 * dir.x + 0.3 * sx / sn, 0.7 * dir.y + 0.3 * sy / sn)
        val out = corners.filterIndexed { k, _ -> k !in used }.toMutableList(); val shownNow = HashMap<String, Pt>()
        for ((i, j) in pairs) {
            val v = unit(corners[i], corners[j]); val cos = (v.x * dir.x + v.y * dir.y) / max(1e-6, hypot(dir.x, dir.y))
            var p = if (cos >= 0) corners[i] else corners[j]; var s = if (cos >= 0) corners[j] else corners[i]
            val last = lastShown[p.code]
            if (abs(cos) < 0.3 && last != null && hypot(s.rect.cx - last.x, s.rect.cy - last.y) < hypot(p.rect.cx - last.x, p.rect.cy - last.y)) { val t = p; p = s; s = t }
            val m = merged(p, s, accept)
            out.add(m)
            shownNow[m.code] = Pt(p.rect.cx, p.rect.cy)
        }
        for ((k, v) in lastShown) if (k !in shownNow) shownNow[k] = v
        lastShown = shownNow
        return out
    }
}

// ---------------- stack scanner ----------------

/** Builds the order of a spread deck over many frames. See [StackyStackScanner]. */
internal class StackScan(var deckSize: Int = 52) {
    companion object { @JvmStatic var appearanceOrder = true }
    private class Item(val w: DoubleArray, val paired: Boolean, val accepted: Boolean, var rel: Double)
    private sealed class Op { class M(val j: Int, val p: Int, val s: Double) : Op(); object S : Op(); class I(val j: Int) : Op() }
    class Entry(val id: Int, val code: String, val sure: Boolean, val checked: Boolean)

    private val temper = 0.5; private val edgeRel = doubleArrayOf(0.4, 0.7)
    private var uid = 0
    var entries: List<Entry> = emptyList(); private set
    private var fullViews = 0; private var rejected = 0
    var lost = false; private set
    private var ids = ArrayList<Int>(); private var logp = ArrayList<DoubleArray>(); private var seen = ArrayList<Int>()
    private var miss = ArrayList<Int>(); private var pinned = ArrayList<String?>()
    var endLocked = false; private set
    private var startLocked = false
    var visible: Set<Int> = emptySet(); private set
    private var axis = Pt(1.0, 0.0)

    // First-appearance order (2026-11-09, same rule as the Swift and web scanners): dealing or spreading as you go has
    // no spread to line up, so the order is when each card first shows (read sure in 2 frames within 1 s). Shown
    // instead of the spatial order when that holds < 60% of the cards that appeared (back at 80%).
    var sequenceMode = false; private set
    private var apHits = HashMap<String, List<Double>>(); private var apFirst = HashMap<String, Double>(); private var apOrder = ArrayList<String>(); private var apSure = HashMap<String, Int>()
    private var apEver = HashMap<String, Double>(); private var apLate = HashSet<String>()
    private val apIds = HashMap<String, Int>()
    val appearanceCards: List<String> get() = apOrder
    /** Cards read long before they were confirmed (peeking out from under a dealt pile): never sure, may belong earlier. */
    val appearedLate: List<String> get() = apOrder.filter { it in apLate }

    init { reset() }

    fun reset() {
        apHits = HashMap(); apFirst = HashMap(); apOrder = ArrayList(); apSure = HashMap(); apEver = HashMap(); apLate = HashSet(); sequenceMode = false
        fullViews = 0; rejected = 0; lost = false; ids = ArrayList(); logp = ArrayList(); seen = ArrayList(); miss = ArrayList(); pinned = ArrayList()
        endLocked = false; startLocked = false; visible = emptySet(); axis = Pt(1.0, 0.0); assign()
    }

    fun undo() {
        if (sequenceMode) { if (apOrder.isNotEmpty()) { val c = apOrder.removeAt(apOrder.lastIndex); apHits.remove(c); apSure.remove(c); apLate.remove(c) }; assign(); return }
        if (ids.isEmpty()) return
        ids.removeAt(ids.lastIndex); logp.removeAt(logp.lastIndex); seen.removeAt(seen.lastIndex); miss.removeAt(miss.lastIndex); pinned.removeAt(pinned.lastIndex)
        endLocked = false; assign()
    }

    private fun newId() = ++uid

    /** One frame. [t]: seconds (default: now). */
    fun feed(cards: List<Seen>, t: Double = System.nanoTime() / 1e9) {
        val now = HashSet<String>()
        for (c in cards) {
            if (!c.accepted || !now.add(c.code)) continue
            apSure[c.code] = (apSure[c.code] ?: 0) + 1
            if (c.code !in apEver) apEver[c.code] = t
            val h = (apHits[c.code] ?: emptyList()).filter { t - it <= AP_WINDOW } + t
            apHits[c.code] = h
            if (h.size >= AP_NEED && c.code !in apOrder && apOrder.size < deckSize) {
                apOrder.add(c.code); apFirst[c.code] = h[0]
                if (h[0] - (apEver[c.code] ?: h[0]) > AP_WINDOW) apLate.add(c.code)   // late appearer: see StackEngine.swift
            }
        }
        feedSpatial(cards)
        if (!appearanceOrder) return
        val was = sequenceMode; val a = apOrder.size.toDouble(); val sp = ids.size.toDouble()
        if (!sequenceMode && apOrder.size >= 4 && sp < 0.6 * a) sequenceMode = true
        else if (sequenceMode && sp >= 0.8 * a) sequenceMode = false
        if (sequenceMode || was != sequenceMode) assign()
        if (sequenceMode) visible = apOrder.indices.filter { apOrder[it] in now }.toSet()
    }

    private fun feedSpatial(cards: List<Seen>) {
        val run = window(cards)
        if (run.isEmpty()) { visible = emptySet(); return }
        if (fullView(run)) return
        val n = ids.size
        fun strongOf(ops: List<Op>) = ops.count { it is Op.M && it.s >= 1.0 }
        val a = align(run)
        val b = if (n > 0 && run.size > 2 && strongOf(a.second) < 2) align(run.reversed()) else null
        val flip = b != null && b.first > a.first
        val best = if (flip) b!! else a; val items = if (flip) run.reversed() else run
        val strong = strongOf(best.second); val inserts = best.second.count { it is Op.I }
        if (n == 0) {
            if (items.none { it.accepted }) return
        } else if (strong < min(if (items.size > 3) 2 else 1, n)) {
            if (!(items.size == 1 && items[0].accepted && inserts == 1 && !endLocked && n < deckSize)) {
                visible = emptySet(); rejected++
                if (rejected >= 12 && !lost) lost = true
                if (n <= 2 && rejected >= 12 && run.size >= 3 && run.any { it.accepted }) { reset(); apply(align(run).second, run) }
                return
            }
        }
        rejected = 0; lost = false
        apply(best.second, items)
    }

    private fun ev(it: Item) = DoubleArray(52) { c -> temper * ln(it.w[c] + 1e-5) }

    private fun fullView(run: List<Item>): Boolean {
        if (run.size < ceil(deckSize * 0.9).toInt() || run.size > deckSize) return false
        if (fullViews > 0 && run.size == ids.size) {
            fun fit(r: List<Item>) = r.withIndex().sumOf { (i, it) -> val q = posterior(i); var d = 0.0; for (c in 0 until 52) d += q[c] * it.w[c]; ln(d + 1e-6) }
            val items = if (fit(run) >= fit(run.reversed())) run else run.reversed()
            items.forEachIndexed { i, it -> val e = ev(it); logp[i] = DoubleArray(52) { c -> logp[i][c] + e[c] }; seen[i]++ }
        } else if (fullViews == 0 || run.size > ids.size) {
            ids = ArrayList(run.map { newId() }); logp = ArrayList(run.map { ev(it) }); seen = ArrayList(run.map { 1 })
            miss = ArrayList(run.map { 0 }); pinned = ArrayList(run.map { null })
        } else return false
        fullViews++; rejected = 0; lost = false
        if (ids.size == deckSize) { endLocked = true; startLocked = true }
        visible = ids.indices.toSet()
        assign()
        return true
    }

    private fun window(cards: List<Seen>): List<Item> {
        val cs = cards.filter { it.rankProbs.size == 13 && it.suitProbs.size == 4 }
        if (cs.isEmpty()) return emptyList()
        val pts = cs.map { Pt(it.rect.cx, it.rect.cy) }
        val h = cs.map { it.rect.size }.sorted()[cs.size shr 1]
        var order = cs.indices.toList(); var straight = true
        if (pts.size >= 3) {
            val (v, near) = straightLine(pts, h)
            val chain = if (near.size < (0.85 * cs.size).toInt()) chainOrder(pts, h) else emptyList()
            if (chain.size > near.size) {
                order = chain; straight = false
                val a = pts[chain.first()]; val b = pts[chain.last()]; val len = max(1.0, hypot(b.x - a.x, b.y - a.y))
                axis = Pt((b.x - a.x) / len, (b.y - a.y) / len)
            } else { axis = v; order = near }
        }
        if (straight) order = order.sortedBy { pts[it].x * axis.x + pts[it].y * axis.y }
        val items = order.map { i ->
            val c = cs[i]; val w = DoubleArray(52)
            for (s in 0 until 4) for (k in 0 until 13) w[s * 13 + k] = c.suitProbs[s] * c.rankProbs[k]
            val t = w.sum(); if (t > 0) for (k in 0 until 52) w[k] /= t
            Item(w, c.pair != null, c.accepted, 1.0)
        }
        if (items.size >= 4) {
            edgeRel.forEachIndexed { d, r -> if (d < items.size shr 1) { items[d].rel = min(items[d].rel, r); items[items.size - 1 - d].rel = min(items[items.size - 1 - d].rel, r) } }
            if (items.first().paired) items.first().rel = 1.0
            if (items.last().paired) items.last().rel = 1.0
        }
        return items
    }

    private fun posterior(p: Int): DoubleArray {
        val l = logp[p]; val m = l.max(); val e = DoubleArray(52) { exp(l[it] - m) }; val s = e.sum()
        return DoubleArray(52) { e[it] / s }
    }

    private fun align(run: List<Item>): Pair<Double, List<Op>> {
        val n = ids.size; val m = run.size; val NEG = -1e9; val Q = ids.indices.map { posterior(it) }; val LOG52 = ln(52.0)
        fun sim(j: Int, p: Int): Double { var d = 0.0; val w = run[j].w; val q = Q[p]; for (c in 0 until 52) d += w[c] * q[c]; return run[j].rel * (ln(d + 1e-6) + LOG52).coerceIn(-0.8, 4.0) }
        fun insCost(p: Int): Double {
            if (n >= deckSize) return if ((p == n && !endLocked) || (p == 0 && !startLocked)) -3.0 else NEG
            if (p == n && n > 0) return if (endLocked) NEG else -1.2
            if (p == 0 && n > 0) return if (startLocked) NEG else -1.2
            return if (n == 0) 0.0 else -4.0
        }
        val dp = Array(m + 1) { DoubleArray(n + 1) { NEG } }
        val back = Array(m + 1) { arrayOfNulls<Triple<Int, Int, Op>>(n + 1) }
        dp[0][0] = 0.0
        for (j in 0..m) for (p in 0..n) {
            val base = dp[j][p]; if (!(base > NEG / 2)) continue
            if (j < m && p < n) { val s = sim(j, p); if (base + s > dp[j + 1][p + 1]) { dp[j + 1][p + 1] = base + s; back[j + 1][p + 1] = Triple(j, p, Op.M(j, p, s)) } }
            if (p < n) { val c = if (j == 0 || j == m) 0.0 else if (m >= 4 && (j == 1 || j == m - 1)) -0.25 else -0.7; if (base + c > dp[j][p + 1]) { dp[j][p + 1] = base + c; back[j][p + 1] = Triple(j, p, Op.S) } }
            if (j < m) { val c = insCost(p); if (c > NEG / 2 && base + c > dp[j + 1][p]) { dp[j + 1][p] = base + c; back[j + 1][p] = Triple(j, p, Op.I(j)) } }
        }
        val ops = ArrayList<Op>(); var j = m; var p = n
        while (true) { val b = back[j][p] ?: break; ops.add(b.third); j = b.first; p = b.second }
        return dp[m][n] to ops.reversed()
    }

    private fun apply(ops: List<Op>, run: List<Item>) {
        val nid = ArrayList<Int>(); val nl = ArrayList<DoubleArray>(); val ns = ArrayList<Int>(); val nm = ArrayList<Int>(); val np = ArrayList<String?>()
        val used = ops.indices.filter { ops[it] !is Op.S }
        val firstUsed = used.firstOrNull() ?: 0; val lastUsed = used.lastOrNull() ?: 0
        var p = 0; var vis = HashSet<Int>(); var lastIP: Pair<Int, Int>? = null; var firstIP: Pair<Int, Int>? = null
        ops.forEachIndexed { k, op ->
            when (op) {
                is Op.M -> {
                    val q = op.p; val w = run[op.j].w; val rel = run[op.j].rel
                    nid.add(ids[q]); nl.add(DoubleArray(52) { c -> logp[q][c] + temper * rel * ln(w[c] + 1e-5) }); ns.add(seen[q] + 1); nm.add(miss[q]); np.add(pinned[q]); p = q + 1
                }
                is Op.S -> {
                    val inside = k > firstUsed && k < lastUsed
                    nid.add(ids[p]); nl.add(logp[p]); ns.add(seen[p]); nm.add(miss[p] + if (inside) 1 else 0); np.add(pinned[p]); p++
                    return@forEachIndexed
                }
                is Op.I -> {
                    val w = run[op.j].w; val rel = run[op.j].rel
                    nid.add(newId()); nl.add(DoubleArray(52) { c -> temper * rel * ln(w[c] + 1e-5) }); ns.add(1); nm.add(0); np.add(null)
                }
            }
            val j = if (op is Op.M) op.j else (op as Op.I).j
            vis.add(nid.size - 1)
            if (firstIP == null) firstIP = j to nid.size - 1
            lastIP = j to nid.size - 1
        }
        var keep = nid.indices.filter { !(nm[it] >= 2 && nm[it] > ns[it] && np[it] == null) }
        while (keep.size > deckSize) {
            val cand = keep.filter { it !in vis && np[it] == null }
            if (cand.isEmpty()) break
            var weakest = cand[0]; for (i in cand) if (ns[i] - 2 * nm[i] < ns[weakest] - 2 * nm[weakest]) weakest = i
            keep = keep.filter { it != weakest }
        }
        if (keep.size < nid.size) vis = HashSet(vis.map { keep.indexOf(it) }.filter { it >= 0 })
        ids = ArrayList(keep.map { nid[it] }); logp = ArrayList(keep.map { nl[it] }); seen = ArrayList(keep.map { ns[it] })
        miss = ArrayList(keep.map { nm[it] }); pinned = ArrayList(keep.map { np[it] })
        lastIP = lastIP?.let { (item, place) -> keep.indexOf(place).takeIf { it >= 0 }?.let { item to it } }
        firstIP = firstIP?.let { (item, place) -> keep.indexOf(place).takeIf { it >= 0 }?.let { item to it } }
        lastIP?.let { (item, place) -> if (item == run.size - 1 && run[item].paired && place == ids.size - 1) endLocked = true }
        firstIP?.let { (item, place) -> if (item == 0 && run[item].paired && place == 0 && run.size > 1) startLocked = true }
        visible = vis
        assign()
    }

    private fun assign() {
        if (sequenceMode) {
            // Sure = read clearly AND nothing else appeared within 0.5 s (cards that come out together have no reliable order).
            val t = apOrder.map { apFirst[it] ?: 0.0 }
            entries = apOrder.mapIndexed { i, c ->
                val gap = listOf(i - 1, i + 1).filter { it in t.indices }.minOfOrNull { kotlin.math.abs(t[i] - t[it]) } ?: 9.0
                val n = apSure[c] ?: 0
                Entry(apIds.getOrPut(c) { newId() }, c, n >= 99 || (n >= 3 && gap >= AP_SURE_GAP && c !in apLate), false)
            }
            return
        }
        val n = ids.size
        if (n == 0) { entries = emptyList(); return }
        val Q = ids.indices.map { posterior(it) }
        val cols = if (n <= 52) hungarian(Q.mapIndexed { i, q ->
            val pin = pinned[i]
            if (pin != null) DoubleArray(52) { k -> if (k == cardIndex(pin)) 0.0 else 1e6 } else DoubleArray(52) { k -> -ln(q[k] + 1e-9) }
        }) else IntArray(n) { i -> Q[i].indices.maxBy { Q[i][it] } }
        entries = Q.mapIndexed { i, q ->
            val c = cols[i]; val best = q.indices.maxBy { q[it] }
            val sure = pinned[i] != null || (seen[i] >= 2 && q[c] >= 0.9 && best == c)
            Entry(ids[i], cardCode(c), sure, pinned[i] != null)
        }
    }

    fun accept(code: String, i: Int) {
        if (sequenceMode) {
            if (i < 0 || i >= apOrder.size) return
            val old = apOrder[i]; if (old != code) { apSure.remove(old); apHits.remove(old); apLate.remove(old) }
            val j = apOrder.indexOf(code)
            if (j >= 0 && j != i) { apOrder.removeAt(j); apOrder[if (j < i) i - 1 else i] = code } else apOrder[i] = code
            apSure[code] = 99; apLate.remove(code); assign(); return
        }
        if (i < 0 || i >= pinned.size) return
        for (k in pinned.indices) if (pinned[k] == code) pinned[k] = null
        pinned[i] = code; assign()
    }

    val toVerify get() = entries.count { !it.sure }
    val complete get() = entries.size == deckSize && toVerify == 0
    val missing: List<String> get() { if (deckSize != 52) return emptyList(); val have = entries.map { it.code }.toSet(); return MNEMONICA.filter { it !in have } }
    fun match(stack: List<String> = MNEMONICA) = matchStack(entries.map { it.code }, stack)
}

internal const val AP_NEED = 2
internal const val AP_WINDOW = 1.0
internal const val AP_SURE_GAP = 0.5
