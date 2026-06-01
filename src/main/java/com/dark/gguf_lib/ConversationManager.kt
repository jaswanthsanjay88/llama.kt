package com.dark.gguf_lib

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ConversationManager - Typed multi-turn conversation history.
 *
 * Manages chat messages with proper data classes instead of raw JSON strings.
 * Provides auto-serialization for the JNI layer, context tracking, and
 * save/restore functionality.
 *
 * Usage:
 * ```kotlin
 * val conversation = ConversationManager(maxHistory = 50)
 * conversation.systemPrompt = "You are a helpful assistant."
 *
 * conversation.addUser("What is Kotlin?")
 * // After generation, assistant response is auto-added by LlamaKt
 *
 * conversation.addUser("Tell me more about coroutines")
 * // Use with LlamaKt:
 * llama.chat(conversation).collect { ... }
 *
 * // Save/restore
 * conversation.saveTo(File("/path/to/chat.json"))
 * val restored = ConversationManager.loadFrom(File("/path/to/chat.json"))
 * ```
 */
class ConversationManager(
    private val maxHistory: Int = 100,
) {
    private val messages = mutableListOf<ChatMessage>()

    /** System prompt — always sent as the first message. */
    var systemPrompt: String? = null

    /** Add a user message. */
    fun addUser(content: String) {
        messages.add(ChatMessage(Role.USER, content))
        trimHistory()
    }

    /** Add an assistant message. */
    fun addAssistant(content: String) {
        if (content.isNotBlank()) {
            messages.add(ChatMessage(Role.ASSISTANT, content))
            trimHistory()
        }
    }

    /** Add a tool result message. */
    fun addToolResult(content: String) {
        messages.add(ChatMessage(Role.TOOL, content))
        trimHistory()
    }

    /** Add an assistant tool-call message (records the tool invocation). */
    fun addToolCall(name: String, argsJson: String, toolCallId: String) {
        messages.add(ChatMessage(Role.ASSISTANT, argsJson, toolCallId = toolCallId, toolName = name))
        trimHistory()
    }

    /** Get all messages (including system prompt). */
    fun allMessages(): List<ChatMessage> {
        val all = mutableListOf<ChatMessage>()
        systemPrompt?.let { all.add(ChatMessage(Role.SYSTEM, it)) }
        all.addAll(messages)
        return all
    }

    /** Number of messages (excluding system prompt). */
    val messageCount: Int get() = messages.size

    /** Get the last N messages. */
    fun lastMessages(n: Int): List<ChatMessage> = messages.takeLast(n)

    /** Clear all messages (keeps system prompt). */
    fun clear() = messages.clear()

    /** Remove the last message (useful for retry). */
    fun removeLast(): ChatMessage? = if (messages.isNotEmpty()) messages.removeAt(messages.size - 1) else null

    /**
     * Serialize to JSON string for the JNI layer.
     * Format: [{"role":"system","content":"..."}, {"role":"user","content":"..."}, ...]
     */
    fun toJson(): String {
        val arr = JSONArray()
        systemPrompt?.let {
            arr.put(JSONObject().apply {
                put("role", "system")
                put("content", it)
            })
        }
        messages.forEach { msg ->
            arr.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", msg.content)
                msg.toolCallId?.let { put("tool_call_id", it) }
                msg.toolName?.let { put("name", it) }
            })
        }
        return arr.toString()
    }

    /**
     * Save conversation to a JSON file.
     */
    fun saveTo(file: File) {
        val obj = JSONObject().apply {
            put("system_prompt", systemPrompt ?: "")
            put("messages", JSONArray().also { arr ->
                messages.forEach { msg ->
                    arr.put(JSONObject().apply {
                        put("role", msg.role.value)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.toolName?.let { put("name", it) }
                    })
                }
            })
        }
        file.writeText(obj.toString(2))
    }

    private fun trimHistory() {
        while (messages.size > maxHistory) {
            messages.removeAt(0)
        }
    }

    companion object {
        /**
         * Load conversation from a JSON file.
         */
        fun loadFrom(file: File): ConversationManager {
            val manager = ConversationManager()
            try {
                val obj = JSONObject(file.readText())
                val sys = obj.optString("system_prompt", "")
                if (sys.isNotEmpty()) manager.systemPrompt = sys

                val arr = obj.optJSONArray("messages")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val msg = arr.getJSONObject(i)
                        val role = Role.fromString(msg.getString("role"))
                        val content = msg.getString("content")
                        val timestamp = msg.optLong("timestamp", System.currentTimeMillis())
                        val toolCallId = if (msg.has("tool_call_id")) msg.getString("tool_call_id") else null
                        val toolName = if (msg.has("name")) msg.getString("name") else null
                        manager.messages.add(ChatMessage(role, content, timestamp, toolCallId, toolName))
                    }
                }
            } catch (_: Exception) {
                // Return empty manager on error
            }
            return manager
        }
    }
}

/** Chat message roles. */
enum class Role(val value: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromString(s: String): Role = entries.find { it.value == s } ?: USER
    }
}

/** A single chat message. */
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)
