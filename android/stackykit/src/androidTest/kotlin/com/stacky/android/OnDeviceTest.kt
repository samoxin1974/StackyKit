package com.stacky.android

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stacky.StackyModel
import com.stacky.StackyReader
import com.stacky.StackyStackScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads a photo of a spread Mnemonica deck on the phone or emulator. Put a model folder at
 * src/androidTest/assets/stacky-v13/ and the photo at src/androidTest/assets/spread.png.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    @Test fun readsASpreadDeck() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val model = StackyModel.fromAssets(ctx, "stacky-v13")
        val bitmap = ctx.assets.open("spread.png").use { BitmapFactory.decodeStream(it) }
        val reader = StackyReader(model, StackyReader.Options(frameMemory = false))
        reader.read(bitmap.toStackyImage())   // warm-up
        val t0 = System.nanoTime()
        val cards = reader.read(bitmap.toStackyImage())
        val ms = (System.nanoTime() - t0) / 1_000_000
        val places = StackyStackScanner.solvePhoto(cards)
        val match = StackyStackScanner.matchOrder(places.map { it.code })
        Log.i("Stacky", "model ${model.name}: ${cards.size} cards in $ms ms, ${places.size} places, Mnemonica ${match?.probability} from ${match?.topCard}")
        assertTrue("only ${places.size} places", places.size >= 45)
        assertTrue("not Mnemonica", (match?.probability ?: 0.0) > 0.99)
    }
}
