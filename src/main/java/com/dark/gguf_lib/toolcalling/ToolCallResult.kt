package com.dark.gguf_lib.toolcalling

/**
 * Structured result from executing a tool call.
 *
 * Carries the execution outcome along with timing and identification metadata,
 * enabling the agentic loop to track tool performance and inject results
 * back into the native context.
 */
data class ToolCallResult(
    /** Unique identifier matching the tool call request. */
    val toolCallId: String,
    /** Name of the executed tool function. */
    val name: String,
    /** Serialized result content (typically JSON). */
    val result: String,
    /** Whether the tool executed successfully. */
    val success: Boolean = true,
    /** Wall-clock execution time in milliseconds. */
    val durationMs: Long = 0,
)
