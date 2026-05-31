package com.dark.gguf_lib

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

/**
 * ModelDownloader - Tier-aware Hugging Face GGUF model manager
 * featuring download progress tracking and SHA-256 integrity validation.
 */
class ModelDownloader(private val context: Context) {

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, percentage: Float, speedBytesPerSec: Long)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    data class RecommendedModel(
        val name: String,
        val url: String,
        val sizeGB: Double,
        val description: String
    )

    /**
     * Get model recommendation matched specifically to the device tier.
     */
    fun getRecommendation(): RecommendedModel {
        return when (GGMLEngine.detectDeviceTier(context)) {
            DeviceTier.LOW_END -> RecommendedModel(
                name = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                url = "https://huggingface.co/lmstudio-community/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                sizeGB = 0.75,
                description = "Recommended for Low-End devices (< 4GB RAM) for lightweight/fast inference."
            )
            DeviceTier.MID_RANGE -> RecommendedModel(
                name = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                url = "https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                sizeGB = 2.0,
                description = "Recommended for Mid-Range devices (4GB - 8GB RAM). Great balance of quality and speed."
            )
            DeviceTier.HIGH_END -> RecommendedModel(
                name = "Llama-3-8B-Lexi-Uncensored_Q4_K_M.gguf",
                url = "https://huggingface.co/Lewdiculous/Llama-3-8B-Lexi-Uncensored-GGUF-IQ-Imatrix/resolve/main/Llama-3-8B-Lexi-Uncensored-Q4_K_M.gguf",
                sizeGB = 4.8,
                description = "Recommended for High-End devices (> 8GB RAM). High quality multi-turn capabilities."
            )
        }
    }

    /**
     * Download a GGUF model from Hugging Face with progressive callbacks.
     */
    suspend fun download(
        urlStr: String,
        fileName: String,
        callback: DownloadCallback
    ) = withContext(Dispatchers.IO) {
        val destFile = File(context.filesDir, fileName)
        
        try {
            val url = URL(urlStr)
            val connection = url.openConnection()
            connection.connect()

            val fileLength = connection.contentLengthLong
            val input = connection.getInputStream()
            val output = FileOutputStream(destFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            val startTime = System.currentTimeMillis()
            var lastUpdate = System.currentTimeMillis()

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUpdate >= 200 || total == fileLength) {
                    val durationSec = (currentTime - startTime) / 1000.0
                    val speed = if (durationSec > 0) (total / durationSec).toLong() else 0L
                    val pct = if (fileLength > 0) (total.toFloat() / fileLength.toFloat()) else 0f
                    
                    withContext(Dispatchers.Main) {
                        callback.onProgress(total, fileLength, pct, speed)
                    }
                    lastUpdate = currentTime
                }
            }

            output.flush()
            output.close()
            input.close()

            withContext(Dispatchers.Main) {
                callback.onComplete(destFile)
            }
        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            withContext(Dispatchers.Main) {
                callback.onError(e.localizedMessage ?: e.message ?: "Unknown download error")
            }
        }
    }

    /**
     * Validate the file integrity using SHA-256.
     */
    suspend fun validateChecksum(file: File, expectedHash: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val fis = file.inputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            fis.close()
            
            val hashBytes = digest.digest()
            val hexString = StringBuilder()
            for (b in hashBytes) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString().equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
