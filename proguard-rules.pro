# llama.kt ProGuard Rules
# Keep JNI native methods (called via reflection from native code)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the GGUFNativeLib singleton and all its native declarations
-keep class com.dark.gguf_lib.GGUFNativeLib { *; }

# Keep callback interfaces (instantiated from native code via JNI)
-keep class com.dark.gguf_lib.models.StreamCallback { *; }
-keep class com.dark.gguf_lib.models.EmbeddingCallback { *; }
-keep class com.dark.gguf_lib.models.EmbeddingResult { *; }
-keep class com.dark.gguf_lib.models.DecodingMetrics { *; }

# Keep all public API classes
-keep class com.dark.gguf_lib.GGMLEngine { *; }
-keep class com.dark.gguf_lib.LlamaKt { *; }
-keep class com.dark.gguf_lib.LlamaKt$* { *; }
-keep class com.dark.gguf_lib.ConversationManager { *; }
-keep class com.dark.gguf_lib.CharacterEngine { *; }
-keep class com.dark.gguf_lib.EmbeddingEngine { *; }
-keep class com.dark.gguf_lib.RAGEngine { *; }
-keep class com.dark.gguf_lib.ToolManager { *; }
-keep class com.dark.gguf_lib.LifecycleAwareEngine { *; }

# Keep data classes used in public API
-keep class com.dark.gguf_lib.models.** { *; }
-keep class com.dark.gguf_lib.toolcalling.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
