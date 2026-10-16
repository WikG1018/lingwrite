// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.ai.FakeLlmRegistry
import com.lingwrite.app.data.ai.LlmEvent
import com.lingwrite.app.data.ai.LlmProvider
import com.lingwrite.app.data.ai.ChatRequest
import kotlinx.coroutines.flow.flow

/**
 * 测试辅助：用固定回复驱动 AI 调用，不经过网络。
 * 例：FakeLlmRegistryHelper.withReply("[{...}]") { 代码里调用 AppGraph.ai... }
 */
object FakeLlmRegistryHelper {
    /** 每次调用返回 [reply] 的假 provider。 */
    fun provider(reply: String): LlmProvider = object : LlmProvider {
        override fun stream(req: ChatRequest) = flow {
            emit(LlmEvent.Delta(reply))
            emit(LlmEvent.Done(10, 10))
        }
    }

    suspend fun <T> withReply(reply: String, block: suspend () -> T): T {
        FakeLlmRegistry.provider = provider(reply)
        try {
            return block()
        } finally {
            FakeLlmRegistry.provider = null
        }
    }
}
