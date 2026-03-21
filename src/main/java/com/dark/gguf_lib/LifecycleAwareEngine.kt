package com.dark.gguf_lib

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * LifecycleAwareEngine - Auto-manages native resources tied to Android lifecycle.
 *
 * Wraps a GGMLEngine and automatically:
 * - Stops generation when the Activity/Fragment pauses
 * - Releases the model when the Activity/Fragment is destroyed
 *
 * This prevents native memory leaks from forgotten `unload()` calls.
 *
 * Usage:
 * ```kotlin
 * class ChatActivity : AppCompatActivity() {
 *     private lateinit var engine: LifecycleAwareEngine
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         engine = LifecycleAwareEngine(lifecycle)
 *
 *         // Load and use like normal GGMLEngine
 *         engine.get().load("/path/to/model.gguf")
 *
 *         // Engine auto-releases on Activity destroy — no need for manual cleanup!
 *     }
 * }
 * ```
 *
 * Or with LlamaKt DSL:
 * ```kotlin
 * class ChatActivity : AppCompatActivity() {
 *     private lateinit var llama: LifecycleAwareEngine
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *
 *         val config = LlamaKt {
 *             model("/path/to/model.gguf")
 *             contextSize(4096)
 *         }
 *
 *         llama = LifecycleAwareEngine.wrap(lifecycle, config)
 *     }
 * }
 * ```
 */
class LifecycleAwareEngine(
    lifecycle: Lifecycle,
    private val stopOnPause: Boolean = true,
) : DefaultLifecycleObserver {

    private val engine = GGMLEngine()
    private var llamaKt: LlamaKt? = null

    init {
        lifecycle.addObserver(this)
    }

    /** Get the underlying GGMLEngine. */
    fun get(): GGMLEngine = engine

    /** Get the LlamaKt wrapper if created via [wrap]. */
    fun getLlamaKt(): LlamaKt? = llamaKt

    override fun onPause(owner: LifecycleOwner) {
        if (stopOnPause) {
            engine.stopGeneration()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        llamaKt?.close()
        engine.unload()
        owner.lifecycle.removeObserver(this)
    }

    companion object {
        /**
         * Create a lifecycle-aware LlamaKt instance.
         * The engine auto-releases when the lifecycle owner is destroyed.
         */
        fun wrap(lifecycle: Lifecycle, llamaKt: LlamaKt, stopOnPause: Boolean = true): LifecycleAwareEngine {
            val wrapper = LifecycleAwareEngine(lifecycle, stopOnPause)
            wrapper.llamaKt = llamaKt
            return wrapper
        }
    }
}
