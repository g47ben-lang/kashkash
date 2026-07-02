package com.voicechanger.app.rvc

import android.content.Context
import org.json.JSONObject
import java.io.File

data class RvcModel(
    val name:       String,
    val onnxPath:   String,
    val sampleRate: Int,
    val phoneDim:   Int,
)

object ModelManager {

    private const val HUBERT_FILE = "hubert.onnx"
    private const val ASSETS_DIR  = "models"

    // ── External storage (user-placed files) ───────────────────────────────

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    // ── Bundled assets ─────────────────────────────────────────────────────

    private fun assetsList(context: Context): Array<String>? =
        runCatching { context.assets.list(ASSETS_DIR) }.getOrNull()

    /** Copy asset to cache dir; ONNX Runtime needs a real file path. */
    private fun extractAsset(context: Context, assetName: String): String {
        val out = File(context.cacheDir, "models/$assetName")
        if (out.exists()) return out.absolutePath
        out.parentFile?.mkdirs()
        context.assets.open("$ASSETS_DIR/$assetName").use { i ->
            out.outputStream().use { i.copyTo(it) }
        }
        return out.absolutePath
    }

    // ── HuBERT ─────────────────────────────────────────────────────────────

    fun hubertPath(context: Context): String {
        val ext = File(modelsDir(context), HUBERT_FILE)
        if (ext.exists()) return ext.absolutePath
        val assets = assetsList(context) ?: return ext.absolutePath
        return if (HUBERT_FILE in assets) extractAsset(context, HUBERT_FILE)
               else ext.absolutePath
    }

    fun isReady(context: Context): Boolean = File(hubertPath(context)).exists()

    // ── Model list (assets + external storage, external wins on conflict) ──

    fun listModels(context: Context): List<RvcModel> {
        val found = mutableMapOf<String, RvcModel>()

        // 1. Bundled assets
        assetsList(context)
            ?.filter { it.endsWith(".onnx") && it != HUBERT_FILE }
            ?.forEach { onnxName ->
                val stem     = onnxName.removeSuffix(".onnx")
                val jsonName = "$stem.json"
                val assets   = assetsList(context) ?: return@forEach
                if (jsonName !in assets) return@forEach
                runCatching {
                    val j   = JSONObject(context.assets.open("$ASSETS_DIR/$jsonName")
                                  .bufferedReader().readText())
                    found[stem] = RvcModel(
                        name       = displayName(stem),
                        onnxPath   = extractAsset(context, onnxName),
                        sampleRate = j.optInt("sr",        40000),
                        phoneDim   = j.optInt("phone_dim", 256),
                    )
                }
            }

        // 2. External storage (overrides same stem from assets)
        val dir = modelsDir(context)
        dir.listFiles { f -> f.name.endsWith(".onnx") && f.name != HUBERT_FILE }
            ?.forEach { onnxFile ->
                val stem     = onnxFile.nameWithoutExtension
                val jsonFile = File(dir, "$stem.json")
                if (!jsonFile.exists()) return@forEach
                runCatching {
                    val j = JSONObject(jsonFile.readText())
                    found[stem] = RvcModel(
                        name       = displayName(stem),
                        onnxPath   = onnxFile.absolutePath,
                        sampleRate = j.optInt("sr",        40000),
                        phoneDim   = j.optInt("phone_dim", 256),
                    )
                }
            }

        return found.values.toList()
    }

    fun installPathInstructions(context: Context): String =
        "העתק קבצי .onnx ו-.json אל:\n${modelsDir(context).absolutePath}"

    private fun displayName(stem: String) =
        stem.replace("_", " ").replace("-", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
}
