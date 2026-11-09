package com.stacky.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.stacky.PokerHand
import com.stacky.Rect
import com.stacky.StackyCard
import com.stacky.StackyModel
import com.stacky.StackyReader
import com.stacky.StackyStackScanner
import com.stacky.StackySteady
import com.stacky.android.fromAssets
import com.stacky.android.toStackyImage
import java.util.concurrent.Executors
import kotlin.math.max

/** Three ways to use Stacky, like the website and the iOS demo: one card, a poker hand, or a whole deck. */
class MainActivity : AppCompatActivity() {
    private enum class Mode(val label: String) { CARD("Card"), HAND("Hand"), DECK("Deck") }

    private lateinit var preview: PreviewView
    private lateinit var overlay: Overlay
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var reader: StackyReader? = null
    private val steady = StackySteady()
    private val scanner = StackyStackScanner()
    @Volatile private var mode = Mode.CARD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = Overlay(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24); setBackgroundColor(0x99000000.toInt()); text = "Loading the model…"
        }
        val modes = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            for (m in Mode.values()) addView(Button(context).apply {
                text = m.label
                setOnClickListener { mode = m; steady.reset(); scanner.reset(); reader?.reset() }
            })
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(modes) }
        setContentView(FrameLayout(this).apply {
            addView(preview); addView(this@MainActivity.overlay)
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        worker.execute {
            val model = StackyModel.fromAssets(this, "stacky-v13")
            reader = StackyReader(model)
            runOnUiThread { status.text = "Model ${model.name} ready. Point the camera at cards." }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else status.text = "Stacky needs the camera."
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val shown = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(worker) { frame ->
                frame.use {
                    val r = reader ?: return@use
                    val image = it.toStackyImage()
                    val cards = r.read(image)
                    show(cards, image.width, image.height)
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the camera thread. */
    private fun show(cards: List<StackyCard>, w: Int, h: Int) {
        val boxes: List<Pair<Rect, String?>>
        val text: String
        when (mode) {
            Mode.CARD -> {
                val main = steady.update(cards).maxByOrNull { it.box.w * it.box.h }
                boxes = listOfNotNull(main?.let { it.box to it.symbol })
                text = main?.name ?: "Hold up one card"
            }
            Mode.HAND -> {
                val shown = steady.update(cards)
                val hand = PokerHand.best(shown.map { it.code })
                boxes = shown.map { it.box to it.symbol }
                text = hand?.let { "${it.name}: ${it.detail}" } ?: "Lay out up to seven cards"
            }
            Mode.DECK -> {
                scanner.feed(cards)
                val order = scanner.order
                val match = scanner.match()
                boxes = cards.map { it.box to null }
                text = when {
                    order.isEmpty() -> "Move slowly along a spread deck"
                    scanner.isLost -> "Lost the place: go back over cards already read"
                    match != null && match.probability > 0.9 -> "${order.size} cards · Mnemonica from ${match.topCard}"
                    else -> "${order.size} cards · ${order.count { !it.isSure }} to check"
                }
            }
        }
        runOnUiThread { overlay.set(boxes, w, h); status.text = text }
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdown() }
}

/** Draws boxes over the preview, scaled the way PreviewView's FILL_CENTER scales the picture. */
@SuppressLint("ViewConstructor")
private class Overlay(context: Context) : View(context) {
    private var boxes: List<Pair<Rect, String?>> = emptyList()
    private var w = 1; private var h = 1
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    fun set(b: List<Pair<Rect, String?>>, width: Int, height: Int) { boxes = b; w = width; h = height; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = max(width / w.toFloat(), height / h.toFloat())
        val ox = (width - w * s) / 2; val oy = (height - h * s) / 2
        for ((r, name) in boxes) {
            val box = RectF(ox + r.x.toFloat() * s, oy + r.y.toFloat() * s, ox + r.maxX.toFloat() * s, oy + r.maxY.toFloat() * s)
            canvas.drawRoundRect(box, 10f, 10f, stroke)
            if (name != null) {
                val red = name.contains('♥') || name.contains('♦')
                label.color = if (red) Color.rgb(200, 16, 46) else Color.BLACK
                val tw = label.measureText(name) + 28
                canvas.drawRoundRect(RectF(box.centerX() - tw / 2, box.bottom + 12, box.centerX() + tw / 2, box.bottom + 64), 26f, 26f, chip)
                canvas.drawText(name, box.centerX(), box.bottom + 52, label)
            }
        }
    }
}
