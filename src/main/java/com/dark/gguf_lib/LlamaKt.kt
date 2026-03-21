package com.dark.gguf_lib

import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import com.dark.gguf_lib.models.ModelInfo
import com.dark.gguf_lib.models.StreamCallback
import com.dark.gguf_lib.toolcalling.ToolCallingConfig
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * LlamaKt — Idiomatic Kotlin DSL entry point for llama.cpp inference.
 *
 * Provides a clean, builder-pattern interface that replaces verbose manual setup.
 *
 * Usage:
 * ```kotlin
 * val llama = LlamaKt {
 *     model("/sdcard/models/llama-3.2-1b-q4.gguf")
 *     contextSize(8192)
 *     sampling {
 *         temperature(0.7f)
 *         topP(0.9f)
 *         topK(40)
 *         minP(0.05f)
 *     }
 *     systemPrompt("You are a helpful AI assistant.")
 *     threads(0) // auto-detect
 * }
 *
 * // Simple chat
 * llama.chat("Hello!").collect { event ->
 *     when (event) {
 *         is GenerationEvent.Token -> print(event.text)
 *         is GenerationEvent.Done -> println()
 *         else -> {}
 *     }
 * }
 *
 * // Cleanup
 * llama.close()
 * ```
 */
class LlamaKt private constructor(
    private val config: Config,
) : AutoCloseable {

    private val engine = GGMLEngine()
    private var initialized = false

    /**
     * Initialize the engine with the configured parameters.
     * Called automatically on first use, but can be called explicitly for eager loading.
     *
     * @return true if initialization succeeded
     */
    fun initialize(): Boolean {
        if (initialized) return true

        val loaded = engine.load(
            path = config.modelPath,
            contextSize = config.contextSize,
            threads = config.threads,
            flashAttn = config.flashAttn,
            cacheTypeK = config.cacheTypeK,
            cacheTypeV = config.cacheTypeV,
        )
        if (!loaded) return false

        // Apply sampling config
        config.samplingConfig?.let { s ->
            engine.setSampling(
                temperature = s.temperature,
                topK = s.topK,
                topP = s.topP,
                minP = s.minP,
                mirostat = s.mirostat,
                mirostatTau = s.mirostatTau,
                mirostatEta = s.mirostatEta,
                seed = s.seed,
            )
        }

        // Apply system prompt
        config.systemPrompt?.let { engine.setSystemPrompt(it) }

        // Apply chat template override
        config.chatTemplate?.let { engine.setChatTemplate(it) }

        // Apply tool calling
        if (config.tools.isNotEmpty()) {
            engine.enableToolCalling(config.tools, config.toolConfig)
        }

        // Apply speculative decoding
        if (config.speculativeDecoding) {
            engine.setSpeculativeDecoding(true, config.speculativeNDraft, config.speculativeNgram)
        }

        // Apply prompt cache dir
        config.promptCacheDir?.let { engine.setPromptCacheDir(it) }

        initialized = true
        return true
    }

    /** Get the underlying engine for advanced operations. */
    val raw: GGMLEngine get() = engine

    /** Whether the engine is loaded and ready. */
    val isReady: Boolean get() = initialized && engine.isLoaded

    /** Get typed model information. */
    fun modelInfo(): ModelInfo? {
        ensureInitialized()
        val json = engine.getModelInfoJson() ?: return null
        return ModelInfo.fromJson(json)
    }

    /**
     * Single-turn chat — shortcut for generateFlow with auto-initialization.
     */
    fun chat(prompt: String, maxTokens: Int = 4096): Flow<GenerationEvent> {
        ensureInitialized()
        return engine.generateFlow(prompt, maxTokens)
    }

    /**
     * Multi-turn chat with a ConversationManager.
     * Automatically serializes the conversation history.
     */
    fun chat(
        conversation: ConversationManager,
        maxTokens: Int = 4096,
    ): Flow<GenerationEvent> = callbackFlow {
        ensureInitialized()

        val messagesJson = conversation.toJson()
        val job = launch(Dispatchers.IO) {
            val accumulated = StringBuilder()
            val cb = object : StreamCallback {
                override fun onToken(token: String) {
                    accumulated.append(token)
                    trySend(GenerationEvent.Token(token))
                }
                override fun onToolCall(name: String, argsJson: String) {
                    trySend(GenerationEvent.ToolCall(name, argsJson))
                }
                override fun onDone() {
                    // Auto-add assistant response to conversation history
                    conversation.addAssistant(accumulated.toString())
                    trySend(GenerationEvent.Done)
                    channel.close()
                }
                override fun onError(message: String) {
                    trySend(GenerationEvent.Error(message))
                    channel.close()
                }
                override fun onProgress(progress: Float) {
                    trySend(GenerationEvent.Progress(progress))
                }
                override fun onMetrics(
                    tps: Float, ttftMs: Float, totalMs: Float,
                    tokensEvaluated: Int, tokensPredicted: Int,
                    modelMB: Float, ctxMB: Float, peakMB: Float, memPct: Float,
                ) {
                    trySend(GenerationEvent.Metrics(
                        DecodingMetrics(tps, ttftMs, totalMs, tokensEvaluated, tokensPredicted, modelMB, ctxMB, peakMB, memPct)
                    ))
                }
            }
            GGUFNativeLib.nativeGenerateStreamMultiTurn(messagesJson, maxTokens, cb)
        }
        awaitClose { job.cancel(); GGUFNativeLib.nativeStopGeneration() }
    }

    /** Stop the current generation. */
    fun stop() = engine.stopGeneration()

    /** Release all native resources. */
    override fun close() {
        if (initialized) {
            engine.unload()
            initialized = false
        }
    }

    private fun ensureInitialized() {
        if (!initialized) {
            if (!initialize()) throw IllegalStateException("Failed to initialize LlamaKt — model load failed for: ${config.modelPath}")
        }
    }

    // ---- Configuration DSL ----

    data class SamplingConfig(
        var temperature: Float = 0.7f,
        var topK: Int = 40,
        var topP: Float = 0.9f,
        var minP: Float = 0.05f,
        var mirostat: Int = 0,
        var mirostatTau: Float = 5.0f,
        var mirostatEta: Float = 0.1f,
        var seed: Int = -1,
    )

    class SamplingBuilder {
        private val config = SamplingConfig()
        fun temperature(v: Float) { config.temperature = v }
        fun topK(v: Int) { config.topK = v }
        fun topP(v: Float) { config.topP = v }
        fun minP(v: Float) { config.minP = v }
        fun mirostat(v: Int) { config.mirostat = v }
        fun mirostatTau(v: Float) { config.mirostatTau = v }
        fun mirostatEta(v: Float) { config.mirostatEta = v }
        fun seed(v: Int) { config.seed = v }
        internal fun build() = config
    }

    data class Config(
        var modelPath: String = "",
        var contextSize: Int = 4096,
        var threads: Int = 0,
        var flashAttn: Boolean = false,
        var cacheTypeK: String = "q8_0",
        var cacheTypeV: String = "q8_0",
        var systemPrompt: String? = null,
        var chatTemplate: String? = null,
        var samplingConfig: SamplingConfig? = null,
        var tools: List<ToolDefinitionBuilder.ToolDefinition> = emptyList(),
        var toolConfig: ToolCallingConfig = ToolCallingConfig(),
        var speculativeDecoding: Boolean = false,
        var speculativeNDraft: Int = 4,
        var speculativeNgram: Int = 4,
        var promptCacheDir: String? = null,
    )

    class Builder {
        private val config = Config()
        private val toolDefs = mutableListOf<ToolDefinitionBuilder.ToolDefinition>()

        fun model(path: String) { config.modelPath = path }
        fun contextSize(size: Int) { config.contextSize = size }
        fun threads(count: Int) { config.threads = count }
        fun flashAttention(enabled: Boolean = true) { config.flashAttn = enabled }
        fun cacheType(k: String, v: String = k) { config.cacheTypeK = k; config.cacheTypeV = v }
        fun systemPrompt(prompt: String) { config.systemPrompt = prompt }
        fun chatTemplate(template: String) { config.chatTemplate = template }
        fun promptCacheDir(dir: String) { config.promptCacheDir = dir }

        fun sampling(block: SamplingBuilder.() -> Unit) {
            config.samplingConfig = SamplingBuilder().apply(block).build()
        }

        fun speculativeDecoding(nDraft: Int = 4, ngramSize: Int = 4) {
            config.speculativeDecoding = true
            config.speculativeNDraft = nDraft
            config.speculativeNgram = ngramSize
        }

        fun tool(name: String, description: String, block: ToolDefinitionBuilder.() -> Unit = {}) {
            toolDefs.add(ToolDefinitionBuilder(name, description).apply(block).build())
        }

        fun toolConfig(block: ToolCallingConfig.() -> Unit) {
            config.toolConfig = ToolCallingConfig().apply(block)
        }

        internal fun build(): Config {
            require(config.modelPath.isNotEmpty()) { "Model path is required. Use model(\"/path/to/model.gguf\")" }
            config.tools = toolDefs.toList()
            return config
        }
    }

    companion object {
        /** Create a LlamaKt instance with DSL configuration. */
        operator fun invoke(block: Builder.() -> Unit): LlamaKt {
            val config = Builder().apply(block).build()
            return LlamaKt(config)
        }
    }
}
