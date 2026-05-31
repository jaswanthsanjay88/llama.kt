package com.dark.gguf_lib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AudioTranscriber - High-performance on-device voice-to-text pipeline
 * using whisper.cpp under the hood.
 */
class AudioTranscriber {

    private var loaded = false

    /**
     * Load a Whisper model file (.bin format).
     * @param path Absolute path to the Whisper model file
     */
    fun load(path: String): Boolean {
        loaded = GGUFNativeLib.nativeLoadAudioModel(path)
        return loaded
    }

    /**
     * Release loaded audio model weights.
     */
    fun unload() {
        if (loaded) {
            GGUFNativeLib.nativeReleaseAudioModel()
            loaded = false
        }
    }

    val isLoaded: Boolean get() = loaded

    /**
     * Transcribe standard 16kHz PCM audio bytes to text string.
     */
    suspend fun transcribe(audioPCMBytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (!loaded) return@withContext "Error: Audio model is not loaded"
        GGUFNativeLib.nativeTranscribeAudio(audioPCMBytes) ?: ""
    }
}
