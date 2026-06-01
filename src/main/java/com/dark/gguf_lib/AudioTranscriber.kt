package com.dark.gguf_lib

import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import com.dark.gguf_lib.models.StreamCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AudioTranscriber - On-device speech-to-text pipeline backed by
 * llama.cpp multimodal audio processing (mtmd).
 *
 * Supports loading an audio projector model (mmproj GGUF) alongside the
 * text model, then transcribing PCM audio input to text via the native
 * multimodal evaluation path.
 *
 * Usage:
 * ```kotlin
 * val transcriber = AudioTranscriber()
 * transcriber.load("/sdcard/models/whisper-mmproj.gguf")
 *
 * val text = transcriber.transcribe(pcmBytes, sampleRate = 16000)
 * ```
 */
class AudioTranscriber {

    private var loaded = false

    /**
     * Load an audio projector model file (mmproj GGUF format).
     * Must be called after the base text model is loaded.
     * @param path Absolute path to the audio projector model file
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
     * Transcribe PCM audio bytes to text.
     * @param audioPCMBytes Raw PCM audio data (16-bit signed, mono)
     * @param sampleRate Audio sample rate in Hz (default: 16000)
     */
    suspend fun transcribe(
        audioPCMBytes: ByteArray,
        sampleRate: Int = 16000,
    ): String = withContext(Dispatchers.IO) {
        if (!loaded) return@withContext "Error: Audio model is not loaded"
        GGUFNativeLib.nativeTranscribeAudio(audioPCMBytes) ?: ""
    }

    /**
     * Streaming audio transcription as a Flow.
     * Processes the audio and emits transcription tokens as they are generated.
     *
     * @param prompt Optional text prompt to guide transcription (e.g. language hint)
     * @param audioBytes Raw audio file bytes (WAV, MP3, FLAC supported)
     * @param sampleRate Audio sample rate in Hz (default: 16000)
     * @param maxTokens Maximum tokens to generate
     */
    fun transcribeFlow(
        prompt: String = "Transcribe the following audio:",
        audioBytes: ByteArray,
        sampleRate: Int = 16000,
        maxTokens: Int = 4096,
    ): Flow<GenerationEvent> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            val cb = object : StreamCallback {
                override fun onToken(token: String) { trySend(GenerationEvent.Token(token)) }
                override fun onToolCall(name: String, argsJson: String) {}
                override fun onDone() { trySend(GenerationEvent.Done); channel.close() }
                override fun onError(message: String) { trySend(GenerationEvent.Error(message)); channel.close() }
                override fun onProgress(progress: Float) { trySend(GenerationEvent.Progress(progress)) }
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
            GGUFNativeLib.nativeGenerateStreamWithAudio(prompt, audioBytes, sampleRate, maxTokens, cb)
        }
        awaitClose { job.cancel(); GGUFNativeLib.nativeStopGeneration() }
    }
}
