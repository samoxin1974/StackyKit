package com.stacky

import ai.onnxruntime.OnnxTensor
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
val SUITS = listOf("C", "D", "H", "S")

/** One index read in a frame. Internal working form; the public one is [StackyCard]. */
internal class Seen(
    val code: String,
    val conf: Double,
    val rankProbs: DoubleArray,
    val suitProbs: DoubleArray,
    val corner: Float,
    val rect: Rect,
    var accepted: Boolean = false,
    var frames: Int = 1,
    var track: Int? = null,
    var pair: Rect? = null,
)

internal fun reading(rp: DoubleArray, sp: DoubleArray): Triple<String, Double, Pair<Int, Int>> {
    val ri = rp.indices.maxBy { rp[it] }; val si = sp.indices.maxBy { sp[it] }
    return Triple(RANKS[ri] + SUITS[si], min(rp[ri], sp[si]), ri to si)
}

/**
 * The same three networks and the same steps as StackyKit for iOS (FramePipeline + CardReader) and the website:
 *   detect: box-finder on the frame squeezed into 960x960 (top left, black padding) -> index boxes
 *   read:   8% padded crop -> card-colour normalise -> pad to 96 -> [gray, R, G, B] / 127.5 - 1 -> reader + corner check
 */
internal class Engine(val model: StackyModel) {
    private val env = StackyModel.env
    private val S get() = model.manifest.size

    private fun detectWhole(img: StackyImage, sx: Int, sy: Int, sw: Int, sh: Int): List<DoubleArray> {
        val size = S
        val s = size.toDouble() / max(sw, sh)
        val nw = max(1, min(size, (sw * s).roundToInt())); val nh = max(1, min(size, (sh * s).roundToInt()))
        val small = Pixels.resize(img.rgb(sx, sy, sw, sh), sw, sh, nw, nh)
        val plane = size * size
        val x = FloatArray(3 * plane)
        for (yy in 0 until nh) for (xx in 0 until nw) {
            val k = (yy * nw + xx) * 3; val i = yy * size + xx
            x[i] = Pixels.u(small[k]) / 255f; x[plane + i] = Pixels.u(small[k + 1]) / 255f; x[2 * plane + i] = Pixels.u(small[k + 2]) / 255f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            model.detector.run(mapOf("image" to input)).use { out ->
                val peaksT = out.get("peaks").get() as OnnxTensor
                val G = peaksT.info.shape[3].toInt(); val gp = G * G
                val pk = peaksT.floatBuffer; val bx = (out.get("boxes").get() as OnnxTensor).floatBuffer
                val res = ArrayList<DoubleArray>()
                for (i in 0 until gp) {
                    if (pk[i] < model.manifest.detectorConf) continue
                    val b = DoubleArray(4) { k -> bx[k * gp + i] * size / s }
                    if (b[2] > b[0] && b[3] > b[1]) res.add(doubleArrayOf(max(0.0, b[0]), max(0.0, b[1]), min(sw.toDouble(), b[2]), min(sh.toDouble(), b[3])))
                }
                return res
            }
        }
    }

    /** Boxes [x0, y0, x1, y1]. tiles > 1 also searches tiles x tiles overlapping pieces (for big photos), then merges. */
    fun detect(img: StackyImage, tiles: Int = 1): List<DoubleArray> {
        val W = img.width; val H = img.height
        val all = detectWhole(img, 0, 0, W, H).toMutableList()
        if (tiles <= 1) return all
        val ov = 0.2; val tw = W / (tiles - (tiles - 1) * ov); val th = H / (tiles - (tiles - 1) * ov)
        for (i in 0 until tiles) for (j in 0 until tiles) {
            val x = (i * tw * (1 - ov)).roundToInt(); val y = (j * th * (1 - ov)).roundToInt()
            val w = min(tw, (W - x).toDouble()).roundToInt(); val h = min(th, (H - y).toDouble()).roundToInt()
            for (b in detectWhole(img, x, y, w, h)) {
                val m = 2  // a box cut by an inner tile edge: the neighbouring tile or the whole frame sees it whole
                if ((b[0] < m && x > 0) || (b[1] < m && y > 0) || (b[2] > w - m && x + w < W) || (b[3] > h - m && y + h < H)) continue
                all.add(doubleArrayOf(b[0] + x, b[1] + y, b[2] + x, b[3] + y))
            }
        }
        return mergeBoxes(all)
    }

    private fun mergeBoxes(b: List<DoubleArray>): List<DoubleArray> {
        fun area(a: DoubleArray) = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
        val keep = ArrayList<DoubleArray>()
        for (a in b.reversed()) {
            val dup = keep.any { k ->
                val i = max(0.0, min(a[2], k[2]) - max(a[0], k[0])) * max(0.0, min(a[3], k[3]) - max(a[1], k[1]))
                i / (area(a) + area(k) - i + 1e-9) > 0.4 || i > 0.7 * min(area(a), area(k))
            }
            if (!dup) keep.add(a)
        }
        return keep
    }

    private fun normalizeCardColor(px: ByteArray, n: Int): ByteArray {
        val lum = FloatArray(n) { (Pixels.u(px[it * 3]) + Pixels.u(px[it * 3 + 1]) + Pixels.u(px[it * 3 + 2])) / 765f }
        val sorted = lum.copyOf().also { it.sort() }
        val pos = 0.8 * (n - 1); val lo = pos.toInt(); val hi = min(n - 1, lo + 1)
        val q = sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        val white = DoubleArray(3) { c ->
            val vals = ArrayList<Double>()
            for (i in 0 until n) if (lum[i] >= q) vals.add(Pixels.u(px[i * 3 + c]) / 255.0)
            median(vals)
        }
        if ((white[0] + white[1] + white[2]) / 3 <= 0.4) return px
        val out = ByteArray(px.size)
        for (i in 0 until n) for (c in 0 until 3) {
            val v = ((Pixels.u(px[i * 3 + c]) / 255.0) / max(white[c], 0.15)).coerceIn(0.0, 1.0)
            out[i * 3 + c] = Pixels.roundHalfEven(v * 255).toByte()
        }
        return out
    }

    private fun median(a: MutableList<Double>): Double {
        if (a.isEmpty()) return 0.0
        a.sort(); val m = a.size shr 1
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2
    }

    /** ImageOps.pad to 96x96 with fill 230, written straight into the [gray, R, G, B] tensor at off. */
    private fun padTensor(px: ByteArray, w: Int, h: Int, x: FloatArray, off: Int) {
        val size = 96; val r = w.toDouble() / h
        var nw = size; var nh = size
        if (r > 1) nh = Pixels.roundHalfEven(h.toDouble() / w * size) else if (r < 1) nw = Pixels.roundHalfEven(w.toDouble() / h * size)
        nw = max(1, nw); nh = max(1, nh)
        val small = Pixels.resize(px, w, h, nw, nh); val ox = (size - nw) shr 1; val oy = (size - nh) shr 1; val plane = size * size
        for (i in 0 until plane) {
            val yy = i / size - oy; val xx = i % size - ox
            var R = 230; var G = 230; var B = 230
            if (yy in 0 until nh && xx in 0 until nw) { val k = (yy * nw + xx) * 3; R = Pixels.u(small[k]); G = Pixels.u(small[k + 1]); B = Pixels.u(small[k + 2]) }
            val gray = (R * 19595 + G * 38470 + B * 7471 + 0x8000) shr 16
            x[off + i] = gray / 127.5f - 1; x[off + plane + i] = R / 127.5f - 1; x[off + 2 * plane + i] = G / 127.5f - 1; x[off + 3 * plane + i] = B / 127.5f - 1
        }
    }

    /** Reads every box. Boxes the corner check turns down are dropped. */
    fun readBoxes(img: StackyImage, boxes: List<DoubleArray>): List<Seen> {
        val n = boxes.size
        if (n == 0) return emptyList()
        val W = img.width; val H = img.height; val one = 4 * 96 * 96
        val x = FloatArray(n * one)
        boxes.forEachIndexed { k, b ->
            val p = max(b[2] - b[0], b[3] - b[1]) * 0.08
            val a = min(W - 1, max(0, (b[0] - p).toInt())); val t = min(H - 1, max(0, (b[1] - p).toInt()))
            val c = min(W, (b[2] + p + 1).toInt()); val d = min(H, (b[3] + p + 1).toInt())
            // never past the picture's edge
            val w = max(1, min(W - a, c - a)); val h = max(1, min(H - t, d - t))
            padTensor(normalizeCardColor(img.rgb(a, t, w, h), w * h), w, h, x, k * one)
        }
        val keep = ArrayList<Seen>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(n.toLong(), 4, 96, 96)).use { input ->
            val feeds = mapOf("x" to input)
            model.reader.run(feeds).use { r ->
                model.check.run(feeds).use { c ->
                    val corner = (c.get("corner").get() as OnnxTensor).floatBuffer
                    val rank = (r.get("rank").get() as OnnxTensor).floatBuffer
                    val suitLogits = (r.get("suit_logits").get() as OnnxTensor).floatBuffer
                    for (k in 0 until n) {
                        val cs = corner[k]
                        if (cs < model.manifest.checkThreshold) continue
                        val rp = DoubleArray(13) { rank[k * 13 + it].toDouble() }
                        val sl = DoubleArray(4) { suitLogits[k * 4 + it].toDouble() }
                        val m = sl.max(); val e = sl.map { exp(it - m) }; val sum = e.sum()
                        val sp = DoubleArray(4) { e[it] / sum }
                        val (code, conf, _) = reading(rp, sp)
                        val b = boxes[k]
                        keep.add(Seen(code, conf, rp, sp, cs, Rect.corners(b[0], b[1], b[2], b[3]), accepted = conf >= model.manifest.accept))
                    }
                }
            }
        }
        return keep
    }

    fun read(img: StackyImage, tiles: Int = 1): List<Seen> = readBoxes(img, detect(img, tiles))
}

