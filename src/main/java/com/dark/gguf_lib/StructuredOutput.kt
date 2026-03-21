package com.dark.gguf_lib

import com.dark.gguf_lib.models.StreamCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * StructuredOutput - Type-safe JSON generation from LLM.
 *
 * Uses grammar-constrained generation to force the model to output valid JSON
 * matching a predefined schema, then parses the result into a Kotlin object.
 *
 * Usage:
 * ```kotlin
 * // Define your output schema
 * val schema = JsonSchema("weather_response")
 *     .string("city", "The city name")
 *     .number("temperature", "Temperature value")
 *     .enum("unit", "Temperature unit", listOf("celsius", "fahrenheit"))
 *     .boolean("is_raining", "Whether it's raining")
 *
 * // Generate structured output
 * val result = StructuredOutput.generate(
 *     engine = engine,
 *     prompt = "What's the weather in Tokyo?",
 *     schema = schema,
 * )
 *
 * if (result.success) {
 *     val json = result.json!!
 *     val city = json.getString("city")       // "Tokyo"
 *     val temp = json.getDouble("temperature") // 22.5
 * }
 * ```
 */
object StructuredOutput {

    /**
     * Generate structured JSON output constrained by the given schema.
     *
     * @param engine Loaded GGMLEngine instance
     * @param prompt User prompt describing what to generate
     * @param schema JSON schema definition
     * @param maxTokens Maximum tokens to generate
     * @param systemPrompt Optional system prompt override
     */
    suspend fun generate(
        engine: GGMLEngine,
        prompt: String,
        schema: JsonSchema,
        maxTokens: Int = 1024,
        systemPrompt: String? = null,
    ): StructuredResult = withContext(Dispatchers.IO) {
        // Build the constrained prompt
        val fullPrompt = buildString {
            if (systemPrompt != null) append(systemPrompt) else append("You are a helpful assistant that responds in JSON format.")
            append("\n\nRespond with ONLY a valid JSON object matching this schema:\n")
            append(schema.toSchemaString())
            append("\n\nUser: $prompt\n\nJSON:")
        }

        val output = StringBuilder()
        var error: String? = null

        val cb = object : StreamCallback {
            override fun onToken(token: String) { output.append(token) }
            override fun onToolCall(name: String, argsJson: String) {}
            override fun onDone() {}
            override fun onError(message: String) { error = message }
            override fun onMetrics(
                tps: Float, ttftMs: Float, totalMs: Float,
                tokensEvaluated: Int, tokensPredicted: Int,
                modelMB: Float, ctxMB: Float, peakMB: Float, memPct: Float,
            ) {}
        }

        engine.setSystemPrompt(fullPrompt)
        val ok = GGUFNativeLib.nativeGenerateStream(prompt, maxTokens, cb)

        if (!ok || error != null) {
            return@withContext StructuredResult(
                success = false,
                rawText = output.toString(),
                error = error ?: "Generation failed",
            )
        }

        // Extract JSON from the output (handle markdown code blocks, etc.)
        val rawText = output.toString().trim()
        val jsonStr = extractJson(rawText)

        try {
            val json = JSONObject(jsonStr)
            StructuredResult(success = true, json = json, rawText = rawText)
        } catch (e: Exception) {
            StructuredResult(success = false, rawText = rawText, error = "Failed to parse JSON: ${e.message}")
        }
    }

    /** Extract JSON from LLM output that might be wrapped in markdown or extra text. */
    private fun extractJson(text: String): String {
        // Try to find JSON in markdown code blocks
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        codeBlockRegex.find(text)?.let { return it.groupValues[1] }

        // Try to find a JSON object directly
        val jsonRegex = Regex("(\\{[^{}]*(?:\\{[^{}]*}[^{}]*)*})", RegexOption.DOT_MATCHES_ALL)
        jsonRegex.find(text)?.let { return it.groupValues[1] }

        return text
    }
}

/**
 * Schema builder for structured JSON output.
 */
class JsonSchema(val name: String) {
    private val properties = mutableMapOf<String, SchemaProperty>()
    private val requiredFields = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = true): JsonSchema {
        properties[name] = SchemaProperty("string", description)
        if (required) requiredFields.add(name)
        return this
    }

    fun number(name: String, description: String, required: Boolean = true): JsonSchema {
        properties[name] = SchemaProperty("number", description)
        if (required) requiredFields.add(name)
        return this
    }

    fun integer(name: String, description: String, required: Boolean = true): JsonSchema {
        properties[name] = SchemaProperty("integer", description)
        if (required) requiredFields.add(name)
        return this
    }

    fun boolean(name: String, description: String, required: Boolean = true): JsonSchema {
        properties[name] = SchemaProperty("boolean", description)
        if (required) requiredFields.add(name)
        return this
    }

    fun enum(name: String, description: String, values: List<String>, required: Boolean = true): JsonSchema {
        properties[name] = SchemaProperty("string", description, enumValues = values)
        if (required) requiredFields.add(name)
        return this
    }

    fun array(name: String, description: String, itemType: String = "string", required: Boolean = true): JsonSchema {
        properties[name] = SchemaProperty("array", description, itemType = itemType)
        if (required) requiredFields.add(name)
        return this
    }

    fun toSchemaString(): String {
        val obj = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().also { props ->
                properties.forEach { (name, prop) ->
                    props.put(name, JSONObject().apply {
                        put("type", prop.type)
                        put("description", prop.description)
                        prop.enumValues?.let { put("enum", JSONArray(it)) }
                        prop.itemType?.let { put("items", JSONObject().put("type", it)) }
                    })
                }
            })
            put("required", JSONArray(requiredFields))
        }
        return obj.toString(2)
    }

    private data class SchemaProperty(
        val type: String,
        val description: String,
        val enumValues: List<String>? = null,
        val itemType: String? = null,
    )
}

/** Result of structured generation. */
data class StructuredResult(
    val success: Boolean,
    val json: JSONObject? = null,
    val rawText: String = "",
    val error: String? = null,
)
