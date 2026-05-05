package com.aigallery.rewrite.ui.screens.singleturn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SingleTurnState(
    val selectedTaskType: SingleTurnTaskType? = null,
    val inputText: String = "",
    val outputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val history: List<TaskResult> = emptyList()
)

@HiltViewModel
class SingleTurnViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(SingleTurnState())
    val state: StateFlow<SingleTurnState> = _state.asStateFlow()

    fun selectTaskType(taskType: SingleTurnTaskType) {
        _state.update { it.copy(selectedTaskType = taskType, inputText = "", outputText = "") }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun executeTask() {
        val taskType = _state.value.selectedTaskType ?: return
        val input = _state.value.inputText.trim()
        if (input.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                // Simulate task execution
                kotlinx.coroutines.delay(1500)

                val output = when (taskType) {
                    SingleTurnTaskType.WRITING -> "【写作助手输出】\n\n根据您的要求，以下是关于主题「${input}」的写作内容：\n\n[这里是模拟的AI生成内容，在实际应用中会调用LLM生成]"
                    SingleTurnTaskType.TRANSLATION -> "【翻译结果】\n\n原文：$input\n\n译文：[翻译内容]"
                    SingleTurnTaskType.SUMMARY -> "【摘要】\n\n原始内容：$input\n\n核心要点：\n1. 要点1\n2. 要点2\n3. 要点3"
                    SingleTurnTaskType.REWRITE -> "【改写结果】\n\n原文：$input\n\n改写后：[改写内容]"
                    SingleTurnTaskType.ANALYSIS -> "【分析结果】\n\n分析内容：$input\n\n主要发现：\n- 发现1\n- 发现2"
                    SingleTurnTaskType.BRAINSTORM -> "【头脑风暴】\n\n问题：$input\n\n创意想法：\n1. 想法1\n2. 想法2\n3. 想法3"
                }

                // Add to history
                val result = TaskResult(
                    taskType = taskType,
                    input = input,
                    output = output,
                    parameters = emptyMap(),
                    durationMs = 1500
                )

                _state.update {
                    it.copy(
                        outputText = output,
                        isLoading = false,
                        history = listOf(result) + it.history.take(19)
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message ?: "任务执行失败",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun clearOutput() {
        _state.update { it.copy(outputText = "", inputText = "") }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
    }
}
