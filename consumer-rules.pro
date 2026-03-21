# llama.kt Consumer ProGuard Rules
# These rules are automatically applied to apps that depend on this library.

# Keep JNI bridge — native code calls these methods by name
-keep class com.dark.gguf_lib.GGUFNativeLib { *; }

# Keep callback interfaces — instantiated from JNI
-keep class com.dark.gguf_lib.models.StreamCallback { *; }
-keep class com.dark.gguf_lib.models.EmbeddingCallback { *; }
-keep class com.dark.gguf_lib.models.EmbeddingResult { *; }

# Keep native method signatures
-keepclasseswithmembernames class * {
    native <methods>;
}
