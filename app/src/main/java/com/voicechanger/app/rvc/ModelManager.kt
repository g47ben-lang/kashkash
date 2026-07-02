package com.voicechanger.app.rvc

import android.content.Context
import org.json.JSONObject
import java.io.File

data class RvcModel(
    val name:       String,   // display name
    val onnxPath:   String,   // path to synthesizer .onnx
    val sampleRate: Int,      // model output sample rate (40000 or 48000)
    val phoneDim:   Int,      // HuBERT feature dim: 256 (v1) or 768 (v2)
)

object ModelManager {

    private const val HUBERT_FILE = "hubert.onnx"

    /** Root folder on device: /sdcard/Android/data/<pkg>/files/models/ */
    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun hubertPath(context: Context): String =
        File(modelsDir(context), HUBERT_FILE).absolutePath

    fun isReady(context: Context): Boolean =
        File(hubertPath(context)).exists()

    /** Scan models dir and return all .onnx files that have a matching .json sidecar. */
    fun listModels(context: Context): List<RvcModel> {
        val dir = modelsDir(context)
        return dir.listFiles { f -> f.name.endsWith(".onnx") && f.name != HUBERT_FILE }
            ?.mapNotNull { onnxFile ->
                val jsonFile = File(dir, "${onnxFile.nameWithoutExtension}.json")
                if (!jsonFile.exists()) return@mapNotNull null
                try {
                    val j        = JSONObject(jsonFile.readText())
                    val sr       = j.optInt("sr",         40000)
                    val phoneDim = j.optInt("phone_dim",  256)
                    RvcModel(
                        name       = onnxFile.nameWithoutExtension
                                         .replace("_", " ")
                                         .replace("-", " ")
                                         .split(" ").joinToString(" ") {
                                             it.replaceFirstChar { c -> c.uppercaseChar() }
                                         },
                        onnxPath   = onnxFile.absolutePath,
                        sampleRate = sr,
                        phoneDim   = phoneDim,
                    )
                } catch (_: Exception) { null }
            } ?: emptyList()
    }

    fun installPathInstructions(context: Context): String {
        val dir = modelsDir(context)
        return "העתק קבצי .onnx ו-.json אל:\n${dir.absolutePath}"
    }
}
