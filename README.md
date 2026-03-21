# llama.kt

A high-performance local LLM inference engine for Kotlin and Android, built on [llama.cpp](https://github.com/ggml-org/llama.cpp).

Run large language models — Llama 3, Phi-3, Gemma, Mistral, Qwen, and more — directly on Android devices. Fully offline. Fully private. No server required.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Getting Started](#getting-started)
- [Usage Examples](#usage-examples)
- [API Reference](#api-reference)
- [Build Instructions](#build-instructions)
- [Architecture](#architecture)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

llama.kt is a Kotlin-first Android library that wraps the llama.cpp inference engine via JNI. It provides an idiomatic Kotlin API with coroutines, Flow-based streaming, and DSL configuration — while delivering native C++ performance with ARM-optimized kernels (NEON, DotProd, i8mm).

The library supports GGUF model files and runs entirely on-device with no network connectivity required.

---

## Features

### Core Inference
- **GGUF Model Loading** — Load models from file path, file descriptor, or Android content URIs (SAF)
- **Streaming Generation** — Flow-based token streaming with real-time callbacks
- **Multi-Turn Chat** — Typed conversation history with automatic serialization
- **Context Management** — KV cache state save/restore across sessions

### Advanced Capabilities
- **Tool Calling** — Grammar-constrained function calling in OpenAI format
- **Structured Output** — Schema-defined JSON generation with type-safe results
- **RAG Engine** — On-device retrieval-augmented generation with embedding models
- **Character Engine** — Personality traits, mood states, and control vector support
- **Embeddings** — Separate embedding model instance for semantic search

### Performance
- **ARM v8.6-a Optimizations** — DotProd, i8mm, FP16 instruction support
- **Speculative Decoding** — N-gram self-speculative for 1.3-2x throughput on structured output
- **Batched JNI** — Token batching to minimize JNI boundary crossing overhead
- **Prompt Caching** — Disk-backed system prompt cache for fast cold starts
- **Performance Core Pinning** — Automatic big.LITTLE core affinity for consistent throughput

### Developer Experience
- **Kotlin DSL** — One-liner engine configuration with builder pattern
- **Lifecycle-Aware** — Automatic resource cleanup tied to Android lifecycle
- **ProGuard Safe** — Pre-configured rules for R8 compatibility
- **Typed API** — Data classes for model info, metrics, events, and messages

---

## Getting Started

### Requirements

| Requirement | Version |
|---|---|
| Android NDK | 27.0+ |
| CMake | 3.22.1+ |
| Min SDK | 24 |
| Target ABI | arm64-v8a |
| Kotlin | 1.9+ |

### Installation

Add the module as a dependency in your project:

```kotlin
// settings.gradle.kts
include(":llama.kt")

// app/build.gradle.kts
dependencies {
    implementation(project(":llama.kt"))
}
```

---

## Usage Examples

### Basic: Kotlin DSL

```kotlin
val llama = LlamaKt {
    model("/sdcard/models/llama-3.2-1b-q4.gguf")
    contextSize(8192)
    sampling {
        temperature(0.7f)
        topP(0.9f)
        topK(40)
    }
    systemPrompt("You are a helpful assistant.")
}

llama.chat("What is Kotlin?").collect { event ->
    when (event) {
        is GenerationEvent.Token -> print(event.text)
        is GenerationEvent.Done -> println()
        is GenerationEvent.Metrics -> {
            println("${event.metrics.tokensPerSecond} tokens/sec")
        }
        is GenerationEvent.Error -> {
            System.err.println("Error: ${event.message}")
        }
        else -> {}
    }
}

llama.close()
```

### Multi-Turn Conversation

```kotlin
val conversation = ConversationManager(maxHistory = 50)
conversation.systemPrompt = "You are a coding assistant."

conversation.addUser("Explain coroutines in Kotlin")

llama.chat(conversation).collect { event ->
    when (event) {
        is GenerationEvent.Token -> print(event.text)
        is GenerationEvent.Done -> {} // response auto-appended to history
        else -> {}
    }
}

// Continue the conversation
conversation.addUser("Show me a practical example")
llama.chat(conversation).collect { ... }

// Persist conversation to disk
conversation.saveTo(File(context.filesDir, "chat_session.json"))

// Restore later
val restored = ConversationManager.loadFrom(File(context.filesDir, "chat_session.json"))
```

### Tool Calling

```kotlin
val llama = LlamaKt {
    model("/path/to/model.gguf")
    tool("get_weather", "Get the current weather for a location") {
        stringParam("city", "City name", required = true)
        enumParam("unit", "Temperature unit", listOf("celsius", "fahrenheit"))
    }
    tool("search_web", "Search the web for information") {
        stringParam("query", "Search query", required = true)
        integerParam("max_results", "Maximum results to return", required = false)
    }
}

llama.chat("What is the weather in Tokyo?").collect { event ->
    when (event) {
        is GenerationEvent.ToolCall -> {
            val name = event.name       // "get_weather"
            val args = event.argsJson   // {"city":"Tokyo","unit":"celsius"}
            // Execute tool, then feed result back
        }
        is GenerationEvent.Token -> print(event.text)
        else -> {}
    }
}
```

### Structured JSON Output

```kotlin
val schema = JsonSchema("analysis_result")
    .string("summary", "Brief summary of the analysis")
    .number("confidence", "Confidence score between 0 and 1")
    .enum("sentiment", "Overall sentiment", listOf("positive", "negative", "neutral"))
    .array("keywords", "Relevant keywords", itemType = "string")

val result = StructuredOutput.generate(
    engine = engine,
    prompt = "Analyze: The new update improved performance significantly",
    schema = schema,
)

if (result.success) {
    val summary = result.json!!.getString("summary")
    val confidence = result.json!!.getDouble("confidence")
    val sentiment = result.json!!.getString("sentiment")
}
```

### Lifecycle-Aware Usage

```kotlin
class ChatActivity : AppCompatActivity() {
    private lateinit var engine: LifecycleAwareEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = LifecycleAwareEngine(lifecycle)
        engine.get().load("/path/to/model.gguf")

        // No need to call unload() — the engine auto-releases
        // when the Activity is destroyed.
    }
}
```

### Device-Adaptive Loading

```kotlin
val params = GGMLEngine.getRecommendedParams(context)
engine.load(
    path = modelPath,
    contextSize = params.contextSize,
    cacheTypeK = params.cacheTypeK,
    cacheTypeV = params.cacheTypeV,
)
```

---

## API Reference

### Entry Points

| Class | Description |
|---|---|
| `LlamaKt` | DSL-based engine builder. Recommended entry point. |
| `GGMLEngine` | Low-level engine with full control over all parameters. |

### Chat and Conversation

| Class | Description |
|---|---|
| `ConversationManager` | Multi-turn chat history with typed messages, serialization, and persistence. |
| `ChatMessage` | Data class representing a single message with role, content, and timestamp. |
| `Role` | Enum: `SYSTEM`, `USER`, `ASSISTANT`, `TOOL`. |

### Generation Events

| Event | Description |
|---|---|
| `GenerationEvent.Token` | A new generated token. |
| `GenerationEvent.Done` | Generation completed. |
| `GenerationEvent.Error` | An error occurred. |
| `GenerationEvent.ToolCall` | The model invoked a function. |
| `GenerationEvent.Metrics` | Performance metrics for the generation pass. |
| `GenerationEvent.Progress` | Prompt evaluation progress (0.0 to 1.0). |
| `GenerationEvent.ThinkingBlock` | Internal reasoning from chain-of-thought models. |
| `GenerationEvent.PartialResponse` | Accumulated text so far. |

### Subsystems

| Class | Description |
|---|---|
| `CharacterEngine` | Personality, mood, uncensored mode, and control vectors. |
| `EmbeddingEngine` | Separate embedding model for text vectorization. |
| `RAGEngine` | Retrieval-augmented generation with on-device indexing. |
| `ToolManager` | Function calling registration and management. |
| `StructuredOutput` | Schema-constrained JSON generation. |

### Data Classes

| Class | Description |
|---|---|
| `ModelInfo` | Parsed model metadata with formatted sizes. |
| `DecodingMetrics` | Tokens/sec, TTFT, memory usage, and evaluation counts. |
| `LoadingParams` | Recommended loading parameters per device tier. |
| `GenerationResult` | Complete result from non-streaming generation. |

---

## Build Instructions

### From Source

```bash
git clone https://github.com/user/llama.kt.git
cd llama.kt
./gradlew assembleRelease
```

The output AAR is located at `build/outputs/aar/`.

### Integration

To use llama.kt as a module in an existing Android project:

1. Copy the `llama.kt` directory into your project root.
2. Add `include(":llama.kt")` to your `settings.gradle.kts`.
3. Add `implementation(project(":llama.kt"))` to your app's `build.gradle.kts`.

---

## Architecture

```
llama.kt/
├── build.gradle.kts                 # Android Library + CMake config
├── proguard-rules.pro               # R8/ProGuard keep rules
├── consumer-rules.pro               # Rules propagated to consumer apps
└── src/main/
    ├── AndroidManifest.xml
    ├── cpp/                          # Native layer
    │   ├── CMakeLists.txt            # Build configuration
    │   ├── gguf_lib.cpp              # JNI bridge (Kotlin <-> C++)
    │   ├── include/                  # llama.cpp public headers
    │   ├── src/                      # llama.cpp core implementation
    │   ├── ggml/                     # GGML tensor library + CPU backend
    │   ├── common/                   # Shared utilities (sampling, chat, etc.)
    │   ├── engine/                   # Tool calling, RAG, character engine
    │   └── vendor/                   # Third-party deps (nlohmann/json, stb, etc.)
    └── java/com/dark/gguf_lib/       # Kotlin layer
        ├── LlamaKt.kt               # DSL builder entry point
        ├── GGMLEngine.kt            # Core inference engine
        ├── GGUFNativeLib.kt          # JNI method declarations
        ├── ConversationManager.kt    # Multi-turn chat history
        ├── LifecycleAwareEngine.kt   # Android lifecycle integration
        ├── StructuredOutput.kt       # Schema-based JSON generation
        ├── CharacterEngine.kt        # Personality and mood control
        ├── EmbeddingEngine.kt        # Text embedding generation
        ├── RAGEngine.kt              # Retrieval-augmented generation
        ├── ToolManager.kt            # Function calling management
        ├── models/                   # Data classes (ModelInfo, Metrics, etc.)
        └── toolcalling/              # Tool definition builders
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute to this project.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

llama.kt is built on [llama.cpp](https://github.com/ggml-org/llama.cpp), which is also MIT-licensed.
