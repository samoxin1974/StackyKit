package com.stacky

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** A Stacky model: the box-finder, the reader and the corner check (ONNX), with the thresholds from model.json. */
class StackyModel private constructor(
    val manifest: Manifest,
    internal val detector: OrtSession,
    internal val reader: OrtSession,
    internal val check: OrtSession,
) : AutoCloseable {
    /** The fields of model.json. */
    data class Manifest(
        val name: String,
        val detectorConf: Float,
        val checkThreshold: Float,
        val accept: Float,
        val size: Int,
    )

    val name get() = manifest.name

    override fun close() { detector.close(); reader.close(); check.close() }

    companion object {
        internal val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

        /** Loads a model folder holding detector.onnx, reader.onnx, check.onnx and model.json. */
        @JvmStatic @JvmOverloads
        fun load(folder: File, options: OrtSession.SessionOptions = OrtSession.SessionOptions()): StackyModel =
            load({ File(folder, it).readBytes() }, options)

        /** Loads a model from any source, such as an Android app's assets: `open("detector.onnx")` returns that file's bytes. */
        @JvmStatic @JvmOverloads
        fun load(open: (String) -> ByteArray, options: OrtSession.SessionOptions = OrtSession.SessionOptions()): StackyModel {
            val manifest = parseManifest(String(open("model.json")))
            return StackyModel(
                manifest,
                env.createSession(open("detector.onnx"), options),
                env.createSession(open("reader.onnx"), options),
                env.createSession(open("check.onnx"), options),
            )
        }

        internal fun parseManifest(json: String): Manifest {
            fun str(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            fun num(k: String) = Regex("\"$k\"\\s*:\\s*(-?[0-9.eE+-]+)").find(json)?.groupValues?.get(1)?.toDouble()
            return Manifest(
                name = str("name") ?: "unknown",
                detectorConf = (num("detector_conf") ?: 0.15).toFloat(),
                checkThreshold = (num("check_threshold") ?: 0.5).toFloat(),
                accept = (num("accept") ?: 0.7).toFloat(),
                size = (num("size") ?: 960.0).toInt(),
            )
        }
    }
}
