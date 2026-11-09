package com.stacky.android

import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.stacky.StackyImage
import com.stacky.StackyModel

/**
 * Loads a model folder from the app's assets, such as `assets/stacky-v13/` holding detector.onnx, reader.onnx,
 * check.onnx and model.json. Takes a second or two: call it off the main thread, once.
 *
 * `fast` runs the networks with XNNPACK on the phone's CPU cores, which is the quickest choice on most phones.
 */
@JvmOverloads
fun StackyModel.Companion.fromAssets(context: Context, folder: String, fast: Boolean = true): StackyModel {
    val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (fast) runCatching { addXnnpack(mapOf("intra_op_num_threads" to Runtime.getRuntime().availableProcessors().coerceAtMost(4).toString())) }
    }
    return load({ name -> context.assets.open("$folder/$name").use { it.readBytes() } }, options)
}

/** A Bitmap as Stacky reads it. It must be upright: indexes as printed, not mirrored. */
fun Bitmap.toStackyImage(): StackyImage {
    val b = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    return StackyImage(b.width, b.height, px)
}

/**
 * A CameraX frame, turned upright. Set the ImageAnalysis to
 * `setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)`. Never mirror what you pass in: mirror only your
 * preview, and flip the boxes to match it.
 */
fun ImageProxy.toStackyImage(): StackyImage {
    val bitmap = toBitmap()
    val degrees = imageInfo.rotationDegrees
    val upright = if (degrees == 0) bitmap else
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, false)
    return upright.toStackyImage()
}
