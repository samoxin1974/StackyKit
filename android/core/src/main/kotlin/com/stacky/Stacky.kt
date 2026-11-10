package com.stacky

import kotlin.math.ln
import kotlin.math.max

// StackyKit for Android and the JVM: public API. Same names and behaviour as StackyKit for iOS.

/** One card index read in a frame. */
class StackyCard internal constructor(
    /** "10S", "QH", "AC": rank (A 2-10 J Q K) + suit (C D H S). */
    val code: String,
    /** min(rank confidence, suit confidence), 0-1. */
    val confidence: Float,
    /** confidence >= the model's accept threshold (0.7). Sure readings are right about 97% of the time. */
    val isSure: Boolean,
    /** The index's box, in pixels of the upright frame. */
    val box: Rect,
    /** The same card's other index, when both were visible and merged. */
    val otherIndexBox: Rect?,
    /** Which corner this is from frame to frame (frame memory on), or null. */
    val trackId: Int?,
    /** How many frames this corner's reading is averaged over. */
    val framesSeen: Int,
    val rankProbabilities: FloatArray,
    val suitProbabilities: FloatArray,
) {
    val rank get() = code.dropLast(1)
    val suit get() = code.takeLast(1)
    val isRed get() = suit == "H" || suit == "D"
    /** "Queen of Hearts". */
    val name get() = cardName(code)
    /** "Q♥". */
    val symbol get() = rank + mapOf("C" to "♣", "D" to "♦", "H" to "♥", "S" to "♠")[suit]

    internal val seen: Seen by lazy {
        Seen(code, confidence.toDouble(), DoubleArray(13) { rankProbabilities[it].toDouble() }, DoubleArray(4) { suitProbabilities[it].toDouble() },
            1f, box, isSure, framesSeen, trackId, otherIndexBox)
    }

    override fun toString() = "$code ${"%.2f".format(confidence)}${if (isSure) "" else " (unsure)"}"

    internal companion object {
        fun of(s: Seen, accept: Double) = StackyCard(
            s.code, s.conf.toFloat(), s.conf >= accept, s.rect, s.pair, s.track, s.frames,
            FloatArray(13) { s.rankProbs[it].toFloat() }, FloatArray(4) { s.suitProbs[it].toFloat() },
        )
    }
}

/** "Queen of Hearts" for "QH". */
fun cardName(code: String): String {
    val r = code.dropLast(1); val s = code.takeLast(1)
    val rank = mapOf("A" to "Ace", "K" to "King", "Q" to "Queen", "J" to "Jack")[r] ?: r
    val suit = mapOf("C" to "Clubs", "D" to "Diamonds", "H" to "Hearts", "S" to "Spades")[s] ?: s
    return "$rank of $suit"
}

/**
 * Reads the cards in camera frames or photos. One per camera stream: it keeps per-stream state (frame memory).
 * Call [read] from one thread at a time, and [reset] when the scene changes.
 */
class StackyReader @JvmOverloads constructor(val model: StackyModel, val options: Options = Options()) {
    data class Options(
        /** Follow each corner across frames and settle its reading. Off for single photos. */
        val frameMemory: Boolean = true,
        /** Merge a card's two indexes into one card. */
        val pairCorners: Boolean = true,
    )

    private val engine = Engine(model)
    private val memory = FrameMemory()
    private val pairing = CornerPairing()
    private val accept get() = model.manifest.accept.toDouble()

    /**
     * Reads an upright, unmirrored picture. Boxes are in its pixels. `tiles = 2` also searches the picture in overlapping
     * pieces, which keeps small indexes readable in big photos (over about 2000 px); it costs about five times as long.
     */
    @JvmOverloads
    fun read(image: StackyImage, tiles: Int = 1): List<StackyCard> {
        var cards = engine.read(image, tiles)
        if (options.frameMemory) cards = memory.step(cards, GrayFrame(image), accept)
        if (options.pairCorners) cards = if (options.frameMemory) pairing.merge(cards, accept) else pairCorners(cards, accept)
        return cards.map { StackyCard.of(it, accept) }
    }

    fun reset() { memory.reset(); pairing.reset() }
}

/**
 * Builds the order of a spread deck from many frames: feed it every frame's cards (from a reader with frame memory on).
 * It uses each card's position along the spread, knows a deck has each card once, says which places need another look,
 * and tells whether the order is a (cut) known stack such as Mnemonica.
 */
class StackyStackScanner @JvmOverloads constructor(deckSize: Int = 52) {
    data class Place(val index: Int, val code: String, val isSure: Boolean)

    private val engine = StackScan(deckSize)

    var deckSize: Int
        get() = engine.deckSize
        set(v) { engine.deckSize = v }

    /** One camera frame's readings. [timeSeconds]: the frame's time (default: now); pass video time when replaying a file. */
    @JvmOverloads
    fun feed(cards: List<StackyCard>, timeSeconds: Double = System.nanoTime() / 1e9) = engine.feed(cards.map { it.seen }, timeSeconds)
    /** True when the order comes from when cards first showed (dealing, or spreading as you go) rather than from the spread. */
    val isInShownOrder: Boolean get() = engine.sequenceMode
    /** In shown order: cards read long before they were confirmed (peeking out from under a dealt pile). Never sure; may belong earlier. */
    val appearedLate: List<String> get() = engine.appearedLate

    /** The deck so far, top of the spread first. */
    val order: List<Place> get() = engine.entries.mapIndexed { i, e -> Place(i, e.code, e.sure) }
    /** Places seen in the last frame. */
    val visiblePlaces: Set<Int> get() = engine.visible
    /** Cards not in the order yet (52-card decks). */
    val missing: List<String> get() = engine.missing
    /** Every place is read and sure. */
    val isComplete: Boolean get() = engine.complete
    /** The last frames didn't fit the order: go back to a part already scanned. */
    val isLost: Boolean get() = engine.lost
    /** The end of the spread has been seen. */
    val endFound: Boolean get() = engine.endLocked

    /** Sets a place by hand (it stays). */
    fun confirm(code: String, index: Int) = engine.accept(code, index)
    fun undoLast() = engine.undo()
    fun reset() = engine.reset()

    /** How likely the order is a (cut) known stack. `stack` = card codes top to bottom; default Mnemonica. */
    @JvmOverloads
    fun match(stack: List<String> = MNEMONICA): StackMatch? = engine.match(stack)

    companion object {
        @JvmStatic val mnemonicaOrder: List<String> get() = MNEMONICA

        /**
         * One photo of a whole spread: the order, each card used once. Pass the cards from a reader with frame memory off
         * (and tiles = 2 for a big photo).
         */
        @JvmStatic
        fun solvePhoto(cards: List<StackyCard>): List<Place> {
            val placed = spreadOrder(cards.map { it.seen }).take(52).map { cards[it] }
            if (placed.isEmpty()) return emptyList()
            val q = placed.map { c ->
                val w = DoubleArray(52); for (s in 0 until 4) for (k in 0 until 13) w[s * 13 + k] = c.suitProbabilities[s].toDouble() * c.rankProbabilities[k]
                val t = w.sum(); DoubleArray(52) { w[it] / t }
            }
            val cols = hungarian(q.map { row -> DoubleArray(52) { -ln(row[it] + 1e-9) } })
            return placed.indices.map { i ->
                val k = cols[i]; val top = q[i].indices.maxBy { q[i][it] }
                Place(i, cardCode(k), q[i][k] >= 0.9 && top == k)
            }
        }

        /** Whether an order of card codes is (part of) a cut stack, default Mnemonica. */
        @JvmStatic @JvmOverloads
        fun matchOrder(codes: List<String>, stack: List<String> = MNEMONICA): StackMatch? = matchStack(codes, stack)
    }
}

/**
 * Steady display for live views: a card shows once it has been read surely `need` times within `within` seconds,
 * and stays `grace` seconds after it was last seen.
 */
class StackySteady @JvmOverloads constructor(val need: Int = 3, val within: Double = 0.8, val grace: Double = 0.9) {
    private class Hit(var times: List<Double>, var card: StackyCard, var last: Double, var shown: Boolean = false)
    private val hits = LinkedHashMap<String, Hit>()

    /** `now` in seconds. */
    @JvmOverloads
    fun update(cards: List<StackyCard>, now: Double = System.nanoTime() / 1e9): List<StackyCard> {
        for (c in cards) {
            if (!c.isSure) continue
            val h = hits.getOrPut(c.code) { Hit(emptyList(), c, now) }
            h.times = (h.times + now).filter { now - it <= max(within, grace) }; h.card = c; h.last = now
        }
        val shown = ArrayList<StackyCard>()
        val iter = hits.entries.iterator()
        while (iter.hasNext()) {
            val h = iter.next().value
            if (now - h.last > grace) { iter.remove(); continue }
            if (h.shown || h.times.count { now - it <= within } >= need) { h.shown = true; shown.add(h.card) }
        }
        return shown
    }

    fun reset() = hits.clear()
}
