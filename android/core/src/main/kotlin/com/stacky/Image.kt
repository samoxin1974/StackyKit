package com.stacky

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An upright picture as packed ARGB pixels, row by row from the top left (the layout of Android's Bitmap.getPixels). */
class StackyImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size >= width * height) { "argb holds ${argb.size} pixels, need ${width * height}" } }

    /** RGB bytes of a region, clipped to the picture. */
    internal fun rgb(x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (yy in 0 until h) for (xx in 0 until w) {
            val p = argb[(y + yy) * width + x + xx]
            val o = (yy * w + xx) * 3
            out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte()
        }
        return out
    }

    /** Luma (0.299 R + 0.587 G + 0.114 B) of one pixel. */
    internal fun gray(i: Int): Float {
        val p = argb[i]
        return 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
    }
}

/** A box in the upright picture's pixels, origin top left. */
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val maxX get() = x + w
    val maxY get() = y + h
    internal val size get() = max(w, h)

    companion object {
        internal fun corners(x0: Double, y0: Double, x1: Double, y1: Double) = Rect(x0, y0, x1 - x0, y1 - y0)
    }
}

internal object Pixels {
    fun u(b: Byte) = b.toInt() and 255

    fun roundHalfEven(v: Double): Int {
        val f = floor(v); val d = v - f
        return when {
            d > 0.5 -> f.toInt() + 1
            d < 0.5 -> f.toInt()
            else -> if (f.toInt() % 2 == 0) f.toInt() else f.toInt() + 1
        }
    }

    /** PIL-style bilinear resize (triangle filter, antialiased when shrinking), RGB bytes; horizontal then vertical, 8-bit between. */
    fun resize(px: ByteArray, w: Int, h: Int, nw: Int, nh: Int): ByteArray {
        fun pass(src: ByteArray, inLen: Int, outLen: Int, lines: Int, horizontal: Boolean, srcW: Int): ByteArray {
            val scale = inLen.toDouble() / outLen; val fs = max(scale, 1.0); val support = fs
            val out = ByteArray((if (horizontal) outLen * lines else srcW * outLen) * 3)
            val ws = DoubleArray((2 * support + 3).toInt())
            for (o in 0 until outLen) {
                val center = (o + 0.5) * scale
                val xmin = max((center - support + 0.5).toInt(), 0); val xmax = min((center + support + 0.5).toInt(), inLen)
                var total = 0.0
                for (x in xmin until xmax) { val t = abs((x - center + 0.5) / fs); val wv = if (t < 1) 1 - t else 0.0; ws[x - xmin] = wv; total += wv }
                if (total > 0) for (k in 0 until xmax - xmin) ws[k] /= total
                for (l in 0 until lines) for (c in 0 until 3) {
                    var acc = 0.0
                    for (x in xmin until xmax) acc += ws[x - xmin] * u(src[if (horizontal) (l * srcW + x) * 3 + c else (x * srcW + l) * 3 + c])
                    val v = acc.roundToInt().coerceIn(0, 255).toByte()
                    if (horizontal) out[(l * outLen + o) * 3 + c] = v else out[(o * srcW + l) * 3 + c] = v
                }
            }
            return out
        }
        var cur = px; var cw = w
        if (nw != w) { cur = pass(cur, w, nw, h, true, w); cw = nw }
        if (nh != h) cur = pass(cur, h, nh, cw, false, cw)
        return cur
    }
}
