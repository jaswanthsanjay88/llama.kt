package com.dark.gguf_lib.models

import org.json.JSONObject

/**
 * Typed representation of model metadata.
 * Parsed from native JSON returned by nativeGetModelInfo().
 */
data class ModelInfo(
    val name: String = "",
    val description: String = "",
    val architecture: String = "",
    val contextLength: Int = 0,
    val parameterCount: Long = 0,
    val modelSizeMB: Float = 0f,
    val vocabularySize: Int = 0,
    val quantization: String = "",
    val chatTemplate: String = "",
) {
    companion object {
        fun fromJson(json: String): ModelInfo? {
            return try {
                val obj = JSONObject(json)
                ModelInfo(
                    name = obj.optString("name", ""),
                    description = obj.optString("description", ""),
                    architecture = obj.optString("architecture", ""),
                    contextLength = obj.optInt("n_ctx", 0),
                    parameterCount = obj.optLong("n_params", 0),
                    modelSizeMB = obj.optDouble("model_size", 0.0).toFloat(),
                    vocabularySize = obj.optInt("n_vocab", 0),
                    quantization = obj.optString("quantization", ""),
                    chatTemplate = obj.optString("chat_template", ""),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Human-readable parameter count (e.g. "7.2B", "1.5B", "500M") */
    val parameterCountFormatted: String get() {
        return when {
            parameterCount >= 1_000_000_000 -> "%.1fB".format(parameterCount / 1_000_000_000.0)
            parameterCount >= 1_000_000 -> "%.0fM".format(parameterCount / 1_000_000.0)
            parameterCount >= 1_000 -> "%.0fK".format(parameterCount / 1_000.0)
            else -> "$parameterCount"
        }
    }

    /** Human-readable model size (e.g. "4.3 GB", "512 MB") */
    val modelSizeFormatted: String get() {
        return if (modelSizeMB >= 1024) "%.1f GB".format(modelSizeMB / 1024.0)
        else "%.0f MB".format(modelSizeMB)
    }
}
