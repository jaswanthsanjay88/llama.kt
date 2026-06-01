package com.dark.gguf_lib.models

/**
 * Sealed class representing events during text generation.
 * Emitted through Flow-based generation APIs.
 */
sealed class GenerationEvent {
    /** A new token of generated text. */
    data class Token(val text: String) : GenerationEvent()

    /** The model invoked a tool/function call. */
    data class ToolCall(val name: String, val argsJson: String) : GenerationEvent()

    /** The result from executing a tool, fed back for continued generation. */
    data class ToolResult(val callId: String, val name: String, val result: String) : GenerationEvent()

    /** Generation completed successfully. */
    data object Done : GenerationEvent()

    /** An error occurred during generation. */
    data class Error(val message: String) : GenerationEvent()

    /** Performance metrics from the generation pass. */
    data class Metrics(val metrics: DecodingMetrics) : GenerationEvent()

    /** Prompt evaluation progress (0.0 to 1.0). */
    data class Progress(val progress: Float) : GenerationEvent()

    /**
     * Thinking/reasoning block from models that support chain-of-thought.
     * Contains the model's internal reasoning before producing the final answer.
     */
    data class ThinkingBlock(val thought: String) : GenerationEvent()

    /**
     * Partial token accumulation — useful for UI rendering with partial words.
     * Contains the accumulated text so far (not just the new token).
     */
    data class PartialResponse(val accumulatedText: String) : GenerationEvent()
}
