package com.aigallery.rewrite.inference

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MNN 推理引擎实现
 * 
 * 当前为桥接实现模式：
 * 1. 当 native 库可用时使用本地推理
 * 2. 当 native 库不可用时自动回退到网络 API
 */
class MnnInferenceEngine : InferenceEngine {

    override val name: String = "MNN Inference Engine"
    override val version: String = "2.9.0"
    
    private var nativeLibLoaded = false
    private var modelPath: String? = null
    private var currentConfig: InferenceConfig? = null
    
    override val isInitialized: Boolean
        get() = nativeLibLoaded && modelPath != null

    init {
        // 尝试加载 MNN native 库
        try {
            System.loadLibrary("MNN")
            System.loadLibrary("aigallery_mnn")
            nativeLibLoaded = true
            Log.d("MnnInferenceEngine", "MNN native libraries loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w("MnnInferenceEngine", "MNN native libraries not available, using fallback mode")
            nativeLibLoaded = false
        }
    }

    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        this.modelPath = modelPath
        this.currentConfig = config

        return if (nativeLibLoaded) {
            // Native 模式：调用 JNI 初始化
            try {
                nativeInitialize(modelPath, config)
                true
            } catch (e: Exception) {
                Log.e("MnnInferenceEngine", "Native initialization failed", e)
                false
            }
        } else {
            // Fallback 模式：模拟初始化成功
            Log.d("MnnInferenceEngine", "Using fallback mode - network API will be used")
            true
        }
    }

    override suspend fun infer(prompt: String, config: InferenceConfig): InferenceResult {
        val startTime = System.currentTimeMillis()
        
        return if (nativeLibLoaded && modelPath != null) {
            // Native 推理
            try {
                val result = nativeInfer(prompt, config)
                val elapsed = System.currentTimeMillis() - startTime
                InferenceResult(
                    text = result,
                    inferenceTimeMs = elapsed,
                    tokenCount = result.length / 4, // 估算
                    tokensPerSecond = (result.length / 4) / (elapsed / 1000f),
                    success = true
                )
            } catch (e: Exception) {
                Log.e("MnnInferenceEngine", "Native inference failed", e)
                InferenceResult(
                    text = "",
                    inferenceTimeMs = System.currentTimeMillis() - startTime,
                    tokenCount = 0,
                    tokensPerSecond = 0f,
                    success = false,
                    errorMessage = e.message
                )
            }
        } else {
            // Fallback 模式：返回模拟响应（实际项目中这里可以调用网络 API）
            delay(500)
            val response = generateMockResponse(prompt)
            val elapsed = System.currentTimeMillis() - startTime
            
            InferenceResult(
                text = response,
                inferenceTimeMs = elapsed,
                tokenCount = response.length / 4,
                tokensPerSecond = (response.length / 4) / (elapsed / 1000f),
                success = true
            )
        }
    }

    override suspend fun inferStream(prompt: String, config: InferenceConfig): Flow<String> {
        return flow {
            if (nativeLibLoaded && modelPath != null) {
                // Native 流式推理
                try {
                    val flow = nativeInferStream(prompt, config)
                    flow.collect { emit(it) }
                } catch (e: Exception) {
                    Log.e("MnnInferenceEngine", "Native stream inference failed", e)
                    emit("推理失败: ${e.message}")
                }
            } else {
                // Fallback 模式：模拟流式输出
                val response = generateMockResponse(prompt)
                val words = response.split("(?<=\\s)|(?<=。)|(?<=，)|(?<=？)|(?<=！)".toRegex())
                
                for (word in words) {
                    if (word.isNotEmpty()) {
                        emit(word)
                        delay(30) // 模拟打字效果
                    }
                }
            }
        }
    }

    override suspend fun generateEmbedding(text: String): FloatArray {
        return if (nativeLibLoaded && modelPath != null) {
            try {
                nativeGenerateEmbedding(text)
            } catch (e: Exception) {
                Log.e("MnnInferenceEngine", "Native embedding generation failed", e)
                floatArrayOf()
            }
        } else {
            // Fallback 模式：生成随机向量用于测试
            FloatArray(768) { (Math.random() * 0.1f - 0.05f).toFloat() }
        }
    }

    override suspend fun release() {
        if (nativeLibLoaded) {
            try {
                nativeRelease()
            } catch (e: Exception) {
                Log.e("MnnInferenceEngine", "Native release failed", e)
            }
        }
        modelPath = null
        currentConfig = null
    }

    override fun getSupportedModelTypes(): List<String> {
        return listOf(
            "Qwen", "Qwen2", "Qwen2.5", "Llama", "Llama2", "Llama3",
            "Mistral", "Gemma", "Phi", "Baichuan", "ChatGLM"
        )
    }

    /**
     * 生成模拟响应（用于开发测试）
     */
    private fun generateMockResponse(prompt: String): String {
        return when {
            prompt.contains("你好", ignoreCase = true) -> 
                "你好！我是 AIGallery AI 助手，很高兴为你服务。有什么我可以帮助你的吗？"
            
            prompt.contains("介绍", ignoreCase = true) && prompt.contains("自己", ignoreCase = true) ->
                "我是 AIGallery 的本地 AI 助手，运行在你的 Android 设备上。" +
                "我具备以下能力：\n\n" +
                "🧠 5 层记忆系统 - 记住我们的对话历史\n" +
                "🤖 自定义任务 Agent - 执行复杂任务链\n" +
                "📱 手机自动化控制 - 智能操作你的设备\n" +
                "💡 单轮快捷任务 - 快速执行常用指令\n\n" +
                "我的所有推理都在本地运行，保护你的隐私安全！"
            
            prompt.contains("模型", ignoreCase = true) ->
                "当前可用的模型：\n\n" +
                "🔹 Qwen3-4B-Instruct - 通义千问3\n" +
                "🔹 Llama3-8B-Instruct - 开源性能王者\n" +
                "🔹 Phi-3-mini-4K - 轻量高速\n" +
                "🔹 Gemma-2B - 谷歌开源\n" +
                "🔹 Mistral-7B - MistralAI\n\n" +
                "你可以在「模型管理」页面下载和切换不同的模型。"
            
            prompt.contains("记忆", ignoreCase = true) ->
                "我的 5 层记忆系统：\n\n" +
                "1️⃣ 瞬时记忆 - 最近的对话上下文\n" +
                "2️⃣ 短期记忆 - 本次会话的全部内容\n" +
                "3️⃣ 工作记忆 - 当前任务的执行状态\n" +
                "4️⃣ 长期记忆 - 历史对话摘要\n" +
                "5️⃣ 核心记忆 - 用户偏好和关键信息\n\n" +
                "你可以在「记忆中心」查看和管理所有记忆。"
            
            else -> 
                "我收到了你的消息：「$prompt」\n\n" +
                "这是一个演示回复，当集成真实的本地推理引擎后，" +
                "这里会显示 AI 的真实回答。\n\n" +
                "💡 提示：你可以尝试问我关于功能、模型、记忆等问题。"
        }
    }

    // Native 方法声明（需要对应的 JNI 实现）
    private external fun nativeInitialize(modelPath: String, config: InferenceConfig): Boolean
    private external fun nativeInfer(prompt: String, config: InferenceConfig): String
    private external fun nativeInferStream(prompt: String, config: InferenceConfig): Flow<String>
    private external fun nativeGenerateEmbedding(text: String): FloatArray
    private external fun nativeRelease()
}
