package com.dark.gguf_lib

import com.dark.gguf_lib.models.EmbeddingCallback
import com.dark.gguf_lib.models.StreamCallback

/**
 * Low-level JNI bridge to llama.cpp.
 *
 * All native methods are declared here. Higher-level APIs
 * (GGMLEngine, ToolManager, CharacterEngine) wrap these.
 */
object GGUFNativeLib {

    init {
        System.loadLibrary("gguf_lib")
    }

    // ---- Model Loading ----

    external fun nativeLoadModel(
        path: String, nCtx: Int, nThreads: Int,
        flashAttn: Boolean, backend: Int, cacheTypeK: String, cacheTypeV: String
    ): Boolean

    external fun nativeLoadModelFromFd(
        fd: Int, nCtx: Int, nThreads: Int,
        flashAttn: Boolean, backend: Int, cacheTypeK: String, cacheTypeV: String
    ): Boolean

    external fun nativeRelease()

    // ---- Multimodal Vision Engine ----

    external fun nativeLoadVisionModel(clipPath: String): Boolean
    external fun nativeReleaseVisionModel()
    external fun nativeGenerateStreamWithImage(
        prompt: String, imageBytes: ByteArray, maxTokens: Int, callback: StreamCallback
    ): Boolean
    external fun nativeGenerateStreamWithAudio(
        prompt: String, audioBytes: ByteArray, sampleRate: Int, maxTokens: Int, callback: StreamCallback
    ): Boolean
    external fun nativeSupportsVision(): Boolean
    external fun nativeSupportsAudio(): Boolean
    external fun nativeGetVisionInfo(): String?

    // ---- Speculative Draft Model ----

    external fun nativeLoadDraftModel(path: String, nThreads: Int): Boolean
    external fun nativeReleaseDraftModel()

    // ---- Model Info ----

    external fun nativeGetModelInfo(): String?

    // ---- Sampling ----

    external fun nativeSetSampling(
        temperature: Float, topK: Int, topP: Float, minP: Float,
        mirostat: Int, mirostatTau: Float, mirostatEta: Float, seed: Int
    )

    external fun nativeSetSystemPrompt(prompt: String)
    external fun nativeSetChatTemplate(template: String)
    external fun nativeUpdateSamplerParams(paramsJson: String): Boolean
    external fun nativeSetLogitBias(biasJson: String)

    // ---- Generation ----

    external fun nativeGenerateStream(
        prompt: String, maxTokens: Int, callback: StreamCallback
    ): Boolean

    external fun nativeGenerateStreamMultiTurn(
        messagesJson: String, maxTokens: Int, callback: StreamCallback
    ): Boolean

    external fun nativeStopGeneration()

    // ---- Tool Calling ----

    external fun nativeIsToolCallingSupported(): Boolean
    external fun nativeSetToolsJson(toolsJson: String)
    external fun nativeSetGrammarMode(mode: Int)
    external fun nativeSetTypedGrammar(enabled: Boolean)

    // ---- Control Vectors ----

    external fun nativeLoadControlVectors(vectorsJson: String): Boolean
    external fun nativeClearControlVector()

    // ---- KV Cache State ----

    external fun nativeGetStateSize(): Long
    external fun nativeGetContextUsage(): Float
    external fun nativeStateSaveToFile(path: String): Boolean
    external fun nativeStateLoadFromFile(path: String): Boolean

    // ---- Character Engine ----

    external fun nativeSetPersonality(paramsJson: String)
    external fun nativeSetMood(mood: Int)
    external fun nativeGetCharacterContext(): String
    external fun nativeSetUncensored(enabled: Boolean)
    external fun nativeGetUncensored(): Boolean
    external fun nativeSupportsThinking(): Boolean

    // ---- Optimization Controls ----

    external fun nativeSetSpeculativeDecoding(enabled: Boolean, nDraft: Int, ngramSize: Int)
    external fun nativeSetPromptCacheDir(path: String)
    external fun nativeWarmUp(): Boolean

    // ---- Embedding Engine ----

    external fun nativeLoadEmbeddingModel(path: String, nThreads: Int, nCtx: Int): Boolean
    external fun nativeEncodeText(text: String, normalize: Boolean, callback: EmbeddingCallback): Boolean
    external fun nativeReleaseEmbeddingModel()

    // ---- RAG Engine ----

    external fun nativeCreateRagEngine(
        nThreads: Int, chunkSize: Int, chunkOverlap: Int,
        nDims: Int, topK: Int, topN: Int, lateChunking: Boolean
    ): Boolean

    external fun nativeLoadRagModel(path: String): Boolean
    external fun nativeLoadRagModelFromFd(fd: Int): Boolean
    external fun nativeRagIsLoaded(): Boolean

    external fun nativeRagAddDocument(text: String, docId: String): Int
    external fun nativeRagRemoveDocument(docId: String): Int
    external fun nativeRagClear()
    external fun nativeRagDocumentCount(): Int
    external fun nativeRagChunkCount(): Int

    /** Returns JSON array of results: [{text, doc_id, chunk_index, score}, ...] */
    external fun nativeRagQuery(query: String): String?

    /** Returns augmented prompt with retrieved context injected */
    external fun nativeRagBuildPrompt(query: String, userPrompt: String): String?

    /** Returns JSON info about the RAG engine state */
    external fun nativeRagInfo(): String?

    external fun nativeReleaseRagEngine()

    // ---- Audio Transcribing (whisper.cpp) ----

    external fun nativeLoadAudioModel(path: String): Boolean
    external fun nativeReleaseAudioModel()
    external fun nativeTranscribeAudio(pcmBytes: ByteArray): String?

    // ---- Dynamic RAM Swapping ----

    external fun nativePurgeModelRAM(): Boolean
    external fun nativeReloadModelRAM(): Boolean

    // ---- Sliding KV Cache & Reranking ----

    external fun nativeApplySlidingWindow(windowSize: Int): Boolean
    external fun nativeRagRerank(query: String, docs: Array<String>): String?

    // ---- Native Function Calling (Agentic) ----

    external fun nativeInjectToolResult(toolCallId: String, toolName: String, resultJson: String): Boolean
    external fun nativeResumeGeneration(maxTokens: Int, callback: StreamCallback): Boolean
}
