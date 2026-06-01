package com.dark.gguf_lib

import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import com.dark.gguf_lib.models.ModelInfo
import com.dark.gguf_lib.models.StreamCallback
import com.dark.gguf_lib.toolcalling.ToolCallingConfig
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import com.dark.gguf_lib.toolcalling.AnnotatedToolExecutor
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
            backend = config.backend,
            cacheTypeK = config.cacheTypeK,
            cacheTypeV = config.cacheTypeV,
        )
        if (!loaded) return false

        // Load vision model if configured
        config.visionModelPath?.let {
            engine.loadVisionModel(it)
        }

        // Load audio model if configured
        config.audioModelPath?.let {
            engine.loadAudioModel(it)
        }

        // Load draft model if configured
        config.draftModelPath?.let {
            engine.loadDraftModel(it, config.draftModelThreads)
        }

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

    /** Check if the loaded model supports vision input. */
    val supportsVision: Boolean get() = engine.supportsVision()

    /** Check if the loaded model supports audio input. */
    val supportsAudio: Boolean get() = engine.supportsAudio()

    /** Get typed model information. */
    fun modelInfo(): ModelInfo? {
        ensureInitialized()
        val json = engine.getModelInfoJson() ?: return null
        return ModelInfo.fromJson(json)
    }

    fun chat(prompt: String, maxTokens: Int = 4096): Flow<GenerationEvent> {
        ensureInitialized()
        return engine.generateFlow(prompt, maxTokens)
    }

    /**
     * Multimodal generation — stream response for text prompt + image input.
     */
    fun chatWithImage(prompt: String, imageBytes: ByteArray, maxTokens: Int = 4096): Flow<GenerationEvent> {
        ensureInitialized()
        return engine.generateFlowWithImage(prompt, imageBytes, maxTokens)
    }

    /**
     * Multimodal generation — stream response for text prompt + audio input.
     */
    fun chatWithAudio(
        prompt: String,
        audioBytes: ByteArray,
        sampleRate: Int = 16000,
        maxTokens: Int = 4096,
    ): Flow<GenerationEvent> {
        ensureInitialized()
        return engine.generateFlowWithAudio(prompt, audioBytes, sampleRate, maxTokens)
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
            engine.unloadVisionModel()
            engine.unloadAudioModel()
            engine.unloadDraftModel()
            engine.unload()
            initialized = false
        }
    }

    /**
     * Automated agentic chat flow with annotation-driven tools (@Tool).
     *
     * Automatically registers tools from the provided handler instances,
     * intercepts tool calling requests from the model, executes the corresponding Kotlin
     * functions via reflection, feeds results back to the LLM context, and streams the
     * final text seamlessly to the subscriber.
     *
     * @param prompt The user's input/query
     * @param toolHandlers List of object instances containing @Tool annotated methods
     * @param maxTokens Maximum tokens to generate
     */
    fun chatWithTools(
        prompt: String,
        toolHandlers: List<Any>,
        maxTokens: Int = 4096,
    ): Flow<GenerationEvent> = callbackFlow {
        ensureInitialized()

        val toolExecutor = AnnotatedToolExecutor()
        val definitions = mutableListOf<ToolDefinitionBuilder.ToolDefinition>()
        
        toolHandlers.forEach { handler ->
            definitions.addAll(toolExecutor.registerTools(handler))
        }

        // Enable tool calling on the engine for this turn
        if (definitions.isNotEmpty()) {
            engine.enableToolCalling(definitions)
        }

        val job = launch(Dispatchers.IO) {
            var round = 0
            val maxRounds = config.toolConfig.maxRounds
            var hasPendingToolCalls = false
            val pendingToolCalls = mutableListOf<Pair<String, String>>()

            val cb = object : StreamCallback {
                override fun onToken(token: String) {
                    trySend(GenerationEvent.Token(token))
                }

                override fun onToolCall(name: String, argsJson: String) {
                    trySend(GenerationEvent.ToolCall(name, argsJson))
                    pendingToolCalls.add(Pair(name, argsJson))
                    hasPendingToolCalls = true
                }

                override fun onDone() {
                    // Handled inside our control loop
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

            // Start initial generation
            var success = GGUFNativeLib.nativeGenerateStream(prompt, maxTokens, cb)
            if (!success) {
                trySend(GenerationEvent.Error("Initial generation failed"))
                channel.close()
                return@launch
            }

            // Multi-round native agentic tool execution loop
            while (hasPendingToolCalls && round < maxRounds) {
                round++
                hasPendingToolCalls = false
                
                val currentToolCalls = ArrayList(pendingToolCalls)
                pendingToolCalls.clear()

                for (toolCall in currentToolCalls) {
                    val toolName = toolCall.first
                    val argsJson = toolCall.second

                    // Generate a unique ID for this tool call
                    val toolCallId = "call_${System.currentTimeMillis()}_${(0..999).random()}"

                    // Execute tool via annotation reflection
                    val startTime = System.currentTimeMillis()
                    var resultStr = ""
                    try {
                        resultStr = toolExecutor.execute(toolName, argsJson) ?: ""
                    } catch (e: Exception) {
                        resultStr = "Error executing tool: ${e.message}"
                    }
                    val durationMs = System.currentTimeMillis() - startTime

                    // Emit ToolResult event
                    trySend(GenerationEvent.ToolResult(toolCallId, toolName, resultStr))

                    // Inject the result natively
                    val injected = engine.injectToolResult(toolCallId, toolName, resultStr)
                    if (!injected) {
                        trySend(GenerationEvent.Error("Failed to inject tool result natively for $toolName"))
                        channel.close()
                        return@launch
                    }
                }

                // Resume generation from the updated KV cache position
                success = GGUFNativeLib.nativeResumeGeneration(maxTokens, cb)
                if (!success) {
                    trySend(GenerationEvent.Error("Resumed generation failed in round $round"))
                    channel.close()
                    return@launch
                }
            }

            if (hasPendingToolCalls) {
                trySend(GenerationEvent.Error("Exceeded maximum tool calling rounds ($maxRounds)"))
            } else {
                trySend(GenerationEvent.Done)
            }
            channel.close()
        }

        awaitClose { 
            job.cancel()
            engine.clearTools()
            toolExecutor.clear()
            GGUFNativeLib.nativeStopGeneration() 
        }
    }

    /**
     * Purge model active weight pages from RAM back to storage.
     * Keeps JNI handles alive, allowing near-instant wake-up.
     */
    fun purgeRAM(): Boolean = GGUFNativeLib.nativePurgeModelRAM()

    /**
     * Eagerly fault-in purged weight pages back to active RAM.
     */
    fun reloadRAM(): Boolean = GGUFNativeLib.nativeReloadModelRAM()

    /**
     * Shrinks context to windowSize by evicting older sequences but keeping system tokens intact.
     */
    fun applySlidingWindow(windowSize: Int): Boolean = raw.applySlidingWindow(windowSize)

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
        var backend: InferenceBackend = InferenceBackend.CPU,
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
        var visionModelPath: String? = null,
        var audioModelPath: String? = null,
        var draftModelPath: String? = null,
        var draftModelThreads: Int = 0,
    )

    class Builder {
        private val config = Config()
        private val toolDefs = mutableListOf<ToolDefinitionBuilder.ToolDefinition>()

        fun model(path: String) { config.modelPath = path }
        fun contextSize(size: Int) { config.contextSize = size }
        fun threads(count: Int) { config.threads = count }
        fun flashAttention(enabled: Boolean = true) { config.flashAttn = enabled }
        fun backend(b: InferenceBackend) { config.backend = b }
        fun cacheType(k: String, v: String = k) { config.cacheTypeK = k; config.cacheTypeV = v }
        fun systemPrompt(prompt: String) { config.systemPrompt = prompt }
        fun chatTemplate(template: String) { config.chatTemplate = template }
        fun promptCacheDir(dir: String) { config.promptCacheDir = dir }
        fun visionModel(path: String) { config.visionModelPath = path }
        fun audioModel(path: String) { config.audioModelPath = path }
        fun draftModel(path: String, threads: Int = 0) { config.draftModelPath = path; config.draftModelThreads = threads }

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
