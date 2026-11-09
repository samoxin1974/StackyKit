package com.stacky

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogicTest {
    @Test fun mnemonicaHas52Cards() = assertEquals(52, MNEMONICA.toSet().size)

    @Test fun cutMnemonicaIsRecognised() {
        val cut = MNEMONICA.drop(17) + MNEMONICA.take(17)
        val m = StackyStackScanner.matchOrder(cut.take(20))!!
        assertTrue(m.probability > 0.99); assertEquals("KC", m.topCard)
    }

    @Test fun pokerHands() {
        assertEquals("Royal Flush", PokerHand.best(listOf("AC", "KC", "QC", "JC", "10C", "2H", "7D"))!!.name)
        assertEquals("Kings full of Sixes", PokerHand.best(listOf("KC", "KH", "KD", "6S", "6H"))!!.detail)
        assertEquals("Straight", PokerHand.best(listOf("AS", "2H", "3D", "4C", "5S"))!!.name)
    }

    @Test fun hungarianFindsTheCheapest() {
        val cols = hungarian(listOf(doubleArrayOf(4.0, 1.0, 3.0), doubleArrayOf(2.0, 0.0, 5.0), doubleArrayOf(3.0, 2.0, 2.0)))
        assertEquals(listOf(1, 0, 2), cols.toList())
    }
}

/** Runs only with STACKY_MODEL (a v13 folder) and STACKY_FIXTURES (spread.png + the website's spread.json). */
class ReaderTest {
    private val modelDir = System.getProperty("stacky.model").orEmpty()
    private val fixtures = System.getProperty("stacky.fixtures").orEmpty()

    private fun image(f: File): StackyImage {
        val b = ImageIO.read(f)
        return StackyImage(b.width, b.height, b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
    }

    @Test fun matchesTheWebsiteOnTheSpreadPhoto() {
        if (modelDir.isEmpty() || fixtures.isEmpty()) { println("skipped: set STACKY_MODEL and STACKY_FIXTURES"); return }
        val model = StackyModel.load(File(modelDir))
        val img = image(File(fixtures, "spread.png"))
        val t0 = System.nanoTime()
        val cards = StackyReader(model, StackyReader.Options(frameMemory = false, pairCorners = false)).read(img)
        println("read ${cards.size} indexes in ${(System.nanoTime() - t0) / 1_000_000} ms")

        // The website's reading of the same photo (browser, ONNX Runtime Web).
        val json = File(fixtures, "spread.json").readText()
        val web = Regex("\\{\"code\": \"(\\w+)\", \"conf\": [^}]*?\"rejected\": false[^}]*?\"r\": \\[(\\d+), (\\d+), (\\d+), (\\d+)]")
            .findAll(json).map { m -> m.groupValues[1] to m.groupValues.drop(2).map { it.toDouble() } }.toList()
        println("website read ${web.size} indexes")
        fun iou(a: Rect, b: List<Double>): Double {
            val iw = min(a.maxX, b[0] + b[2]) - max(a.x, b[0]); val ih = min(a.maxY, b[1] + b[3]) - max(a.y, b[1])
            if (iw <= 0 || ih <= 0) return 0.0
            return iw * ih / (a.w * a.h + b[2] * b[3] - iw * ih)
        }
        var same = 0
        for ((code, r) in web) {
            val hit = cards.maxByOrNull { iou(it.box, r) }
            if (hit != null && iou(hit.box, r) > 0.5 && hit.code == code) same++ else println("differs: web $code at $r, here ${hit?.code} iou ${hit?.let { iou(it.box, r) }}")
        }
        println("same box and card: $same of ${web.size}")
        assertTrue(same >= web.size * 0.95, "only $same of ${web.size} match the website")

        val photo = StackyReader(model, StackyReader.Options(frameMemory = false)).read(img)
        val places = StackyStackScanner.solvePhoto(photo)
        val m = StackyStackScanner.matchOrder(places.map { it.code })
        println("photo: ${places.size} places, ${places.count { it.isSure }} sure; Mnemonica ${m?.probability} from ${m?.topCard}")
        println(places.joinToString(" ") { it.code })
    }
}
