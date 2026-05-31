package com.dark.gguf_lib

import com.google.gson.Gson
import java.lang.reflect.Field

/**
 * Extension to dynamically generate structured outputs mapped directly to your Kotlin classes
 * using Gson reflection.
 *
 * Usage:
 * ```
 * data class AnalysisResult(val summary: String, val confidence: Double)
 * val result = engine.generateTypeSafe("Analyze...", AnalysisResult::class.java)
 * ```
 */

suspend fun <T> GGMLEngine.generateTypeSafe(prompt: String, clazz: Class<T>, maxTokens: Int = 4096): T? {
    if (!isLoaded) return null

    // 1. Automatically generate JsonSchema using reflection on class fields
    val schemaName = clazz.simpleName.lowercase()
    val schema = JsonSchema(schemaName)
    
    for (field in clazz.declaredFields) {
        field.isAccessible = true
        // Ignore synthetic fields (e.g. Kotlin metadata, jacoco, etc.)
        if (field.isSynthetic) continue
        
        val name = field.name
        val description = "Field $name"
        
        when (field.type) {
            String::class.java -> schema.string(name, description)
            Int::class.java, java.lang.Integer::class.java -> schema.integer(name, description)
            Double::class.java, java.lang.Double::class.java, Float::class.java, java.lang.Float::class.java -> schema.number(name, description)
            Boolean::class.java, java.lang.Boolean::class.java -> schema.boolean(name, description)
            else -> schema.string(name, description) // Fallback to string representation
        }
    }

    // 2. Enforce strict JSON output generation using llama.cpp grammar
    val result = StructuredOutput.generate(this, prompt, schema, maxTokens)
    if (!result.success || result.json == null) return null

    // 3. Deserialize JSON back to typed Kotlin object using Gson
    return try {
        Gson().fromJson(result.json.toString(), clazz)
    } catch (e: Exception) {
        null
    }
}

suspend fun <T> LlamaKt.generateTypeSafe(prompt: String, clazz: Class<T>, maxTokens: Int = 4096): T? {
    return raw.generateTypeSafe(prompt, clazz, maxTokens)
}
