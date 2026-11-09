package com.stacky

/** The best five-card poker hand in up to seven cards. */
data class PokerHand(
    /** 0 high card ... 9 royal flush. */
    val category: Int,
    /** "Full House". */
    val name: String,
    /** "Kings full of Sixes". */
    val detail: String,
    /** The card codes that make the hand. */
    val cards: List<String>,
) {
    companion object {
        private val HANDS = listOf("High Card", "Pair", "Two Pair", "Three of a Kind", "Straight", "Flush", "Full House", "Four of a Kind", "Straight Flush", "Royal Flush")
        private fun value(code: String) = when (val r = code.dropLast(1)) { "A" -> 14; "K" -> 13; "Q" -> 12; "J" -> 11; else -> r.toInt() }
        private fun rname(v: Int) = mapOf(14 to "Ace", 13 to "King", 12 to "Queen", 11 to "Jack")[v] ?: v.toString()
        private fun plural(v: Int) = if (v == 6) "Sixes" else rname(v) + "s"
        private fun suitName(code: String) = cardName(code).substringAfter(" of ")

        private fun <T> combos(a: List<T>, k: Int): List<List<T>> = when {
            k == 0 -> listOf(emptyList())
            a.size < k -> emptyList()
            a.size == k -> listOf(a)
            else -> combos(a.drop(1), k - 1).map { listOf(a[0]) + it } + combos(a.drop(1), k)
        }

        private fun evaluate(cs: List<String>): Pair<PokerHand, List<Int>> {
            val vals = cs.map(::value).sortedDescending()
            val groups = vals.groupingBy { it }.eachCount().map { it.key to it.value }.sortedWith(compareBy<Pair<Int, Int>>({ -it.second }, { -it.first }))
            val five = cs.size == 5; val flush = five && cs.map { it.takeLast(1) }.toSet().size == 1
            var sh: Int? = null
            if (five && vals.toSet().size == 5) { if (vals[0] - vals[4] == 4) sh = vals[0] else if (vals == listOf(14, 5, 4, 3, 2)) sh = 5 }
            val kick = groups.map { it.first }
            fun mk(c: Int, detail: String, tb: List<Int>) = PokerHand(c, HANDS[c], detail, cs) to listOf(c) + tb
            val suit = suitName(cs[0])
            return when {
                sh != null && flush -> if (sh == 14) mk(9, suit, listOf(sh)) else mk(8, "${rname(sh)}-high, $suit", listOf(sh))
                groups[0].second == 4 -> mk(7, "Four ${plural(groups[0].first)}", kick)
                groups.size >= 2 && groups[0].second == 3 && groups[1].second >= 2 -> mk(6, "${plural(groups[0].first)} full of ${plural(groups[1].first)}", kick)
                flush -> mk(5, "${rname(vals[0])}-high, $suit", vals)
                sh != null -> mk(4, "${rname(sh)}-high", listOf(sh))
                groups[0].second == 3 -> mk(3, "Three ${plural(groups[0].first)}", kick)
                groups.size >= 2 && groups[0].second == 2 && groups[1].second == 2 -> mk(2, "${plural(groups[0].first)} and ${plural(groups[1].first)}", kick)
                groups[0].second == 2 -> mk(1, "Pair of ${plural(groups[0].first)}", kick)
                else -> mk(0, "${rname(vals[0])} high", vals)
            }
        }

        private fun better(a: List<Int>, b: List<Int>): Boolean {
            for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { -1 }; val y = b.getOrElse(i) { -1 }; if (x != y) return x > y }
            return false
        }

        /** The best hand in these card codes, such as ["AC", "KC", "QC", "JC", "10C", "2H", "7D"]. */
        @JvmStatic
        fun best(codes: List<String>): PokerHand? {
            val distinct = codes.distinct()
            if (distinct.isEmpty()) return null
            var best: Pair<PokerHand, List<Int>>? = null
            for (c in combos(distinct, minOf(5, distinct.size))) { val h = evaluate(c); if (best == null || better(h.second, best.second)) best = h }
            return best?.first
        }
    }
}
