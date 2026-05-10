package com.aigallery.rewrite.inference

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import com.localai.server.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MNN Inference Engine - wraps LlamaEngine with Android-friendly interface
 */
class MnnInferenceEngine(
    private val context: Context
) : InferenceEngine {
    
    companion object {
        private const val TAG = "MnnInferenceEngine"
    }
    
    private var llamaEngine: LlamaEngine? = null
    private var modelPath: String? = null
    
    override val name: String = "MNN-Inference-Engine"
    override val version: String = "1.0"
    override val isInitialized: Boolean
        get() {
            val engine = llamaEngine
            return engine != null && engine.isModelLoaded.value == true
        }
    
    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        FileLogger.d(TAG, "initialize: path=$modelPath, config.backend=${config.backend}")
        this.modelPath = modelPath
        
        // Initialize LlamaEngine singleton
        val engine = LlamaEngine.initialize(context)
        llamaEngine = engine
        
        // Load model with runtime config
        val effectiveBackend = config.backend.ifBlank { "cpu" }
        val openclCache = config.openclCachePath ?: File(context.cacheDir, "opencl_cache").absolutePath
        
        return engine.loadModel(
            path = modelPath,
            nCtx = config.contextWindow,
            nThreads = if (config.numThreads > 0) config.numThreads else 0,
            backend = effectiveBackend,
            attentionMode = config.attentionMode,
            precision = config.precision,
            openclCachePath = openclCache
        )
    }
    
    override suspend fun infer(
        prompt: String,
        config: InferenceConfig
    ): InferenceResult {
        FileLogger.d(TAG, "infer: prompt length=${prompt.length}")
        val engine = llamaEngine
        return try {
            val result = engine?.generate(
                prompt = prompt,
                maxTokens = config.maxLength,
                temperature = config.temperature,
                topK = config.topK,
                topP = config.topP
            ) ?: ""
            
            InferenceResult(
                text = result,
                inferenceTimeMs = engine?.inferenceStats?.value?.lastInferenceTimeMs ?: 0,
                tokenCount = result.length / 4,
                tokensPerSecond = engine?.inferenceStats?.value?.tokensPerSecond ?: 0f,
                success = result.isNotEmpty(),
                errorMessage = if (result.isEmpty()) "Empty response" else null
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "infer failed", e)
            InferenceResult(
                text = "",
                inferenceTimeMs = 0,
                tokenCount = 0,
                tokensPerSecond = 0f,
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    override suspend fun inferStream(
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = flow {
        FileLogger.d(TAG, "inferStream: prompt length=${prompt.length}")
        
        try {
            llamaEngine?.generateStream(
                prompt = prompt,
                maxTokens = config.maxLength,
                temperature = config.temperature,
                topK = config.topK,
                topP = config.topP
            )?.collect { token ->
                emit(token)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "inferStream failed", e)
            emit("[Error: ${e.message}]")
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Generate embedding vector for text
     * Default: character n-gram based lightweight embedding (128-dim)
     * For neural embedding, use a dedicated embedding model
     */
    override suspend fun generateEmbedding(text: String): FloatArray {
        FileLogger.d(TAG, "generateEmbedding: text length=${text.length}")
        return withContext(Dispatchers.IO) {
            // Lightweight n-gram embedding (SimHash-based)
            // No neural network needed, works offline, good for semantic similarity
            val dim = 128  // compact dimension for mobile
            val vec = FloatArray(dim) { 0f }
            
            // Extract character bigrams and trigrams
            val chars = text.lowercase().toCharArray()
            for (i in chars.indices) {
                // Unigram
                val h1 = (chars[i].code * 31) % dim
                vec[h1] += 1f
                vec[(h1 + 1) % dim] += 0.5f
                
                // Bigram
                if (i + 1 < chars.size) {
                    val h2 = ((chars[i].code * 31 + chars[i + 1].code) * 17) % dim
                    vec[Math.abs(h2)] += 0.8f
                }
                
                // Trigram
                if (i + 2 < chars.size) {
                    val h3 = ((chars[i].code * 31 + chars[i + 1].code * 17 + chars[i + 2].code) * 7) % dim
                    vec[Math.abs(h3)] += 0.6f
                }
            }
            
            // L2 normalize
            var norm = 0f
            for (v in vec) norm += v * v
            norm = Math.sqrt(norm.toDouble()).toFloat()
            if (norm > 0f) {
                for (i in vec.indices) vec[i] /= norm
            }
            
            FileLogger.d(TAG, "generateEmbedding: n-gram embedding generated, dim=$dim")
            vec
        }
    }
    
    override suspend fun release() {
        FileLogger.d(TAG, "release")
        llamaEngine?.unloadModel()
        llamaEngine = null
    }
    
    override fun getSupportedModelTypes(): List<String> = listOf("mnn", "onnx")
    
    /**
     * Get memory usage in MB
     */
    fun getMemoryUsageMB(): Float = llamaEngine?.getMemoryUsageMB() ?: 0f
    
    /**
     * Apply runtime config changes (e.g., switch backend)
     * Note: Requires model reload to take effect
     */
    fun applyRuntimeConfig(
        backend: String,
        attentionMode: Int,
        precision: String,
        openclCachePath: String?
    ) {
        llamaEngine?.applyRuntimeConfig(backend, attentionMode, precision, openclCachePath)
    }
}

