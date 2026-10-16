// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.ai

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job

/**
 * 统一的 AI 结果状态机（F1，ai-features §0.1）。
 * Idle → Thinking → Streaming → Ready →（微调）→ Adopted → Idle；任何状态都可以进 Error。
 */
sealed interface AiRunState {
    data object Idle : AiRunState
    data object Thinking : AiRunState
    data class Streaming(val partial: String, val index: Int = 0) : AiRunState
    data class Ready(val candidates: List<Candidate>, val selected: Int = 0) : AiRunState
    data class Error(val message: String, val retryable: Boolean = true, val retries: Int = 0) : AiRunState
    data class Adopted(val target: AdoptTarget) : AiRunState
}

/** 采纳结果落到了哪里：正文 / 便签 / 设定 / 伏笔 / 章纲。 */
enum class AdoptTarget { Body, Note, Lore, Foreshadow, Outline }

/** 一条候选：[text] 是模型原文，[edited] 是作者微调后的版本，[logId] 对应 call_logs。 */
data class Candidate(val text: String, val edited: String? = null, val logId: Long? = null, val sourceIndex: Int = -1) {
    val display get() = edited ?: text
}

/**
 * 一次 AI 运行的可观察状态。UI 持有它，流式事件推进状态。
 * 运行结束（Ready/Error）后 [runId] 变化，UI 可据此做“换一批”的进出动画。
 */
class AiRun {
    var state by mutableStateOf<AiRunState>(AiRunState.Idle)
        private set
    var runId by mutableStateOf(0)
        private set
    var job: Job? = null

    val thinking get() = state is AiRunState.Thinking
    val busy get() = state is AiRunState.Thinking || state is AiRunState.Streaming

    fun reset() {
        job?.cancel()
        state = AiRunState.Idle
    }

    fun begin() {
        job?.cancel()
        runId++
        state = AiRunState.Thinking
    }

    fun stream(partial: String, index: Int = 0) {
        if (state is AiRunState.Thinking || state is AiRunState.Streaming) state = AiRunState.Streaming(partial, index)
    }

    fun ready(candidates: List<Candidate>) {
        state = if (candidates.isEmpty()) AiRunState.Error(tr(R.string.ai_run_001), retryable = true)
        else AiRunState.Ready(candidates)
    }

    fun select(index: Int) {
        val s = state
        if (s is AiRunState.Ready && index in s.candidates.indices) state = s.copy(selected = index)
    }

    /** 微调当前候选（不落库，等采纳）。 */
    fun tweakCurrent(text: String) {
        val s = state
        if (s is AiRunState.Ready) {
            val i = s.selected
            state = s.copy(candidates = s.candidates.toMutableList().also { it[i] = it[i].copy(edited = text) })
        }
    }

    fun fail(message: String, retries: Int = 0) {
        state = AiRunState.Error(message, retryable = true, retries = retries)
    }

    fun adopted(target: AdoptTarget) {
        state = AiRunState.Adopted(target)
    }

    /** Adopted 展示 1.2s 后回到 Idle（由调用方的 LaunchedEffect 调用）。 */
    fun settle() {
        if (state is AiRunState.Adopted) state = AiRunState.Idle
    }

    fun currentText(): String? = when (val s = state) {
        is AiRunState.Ready -> s.candidates.getOrNull(s.selected)?.display
        is AiRunState.Streaming -> s.partial
        else -> null
    }

    fun currentLogId(): Long? = (state as? AiRunState.Ready)?.candidates?.getOrNull((state as AiRunState.Ready).selected)?.logId
}
