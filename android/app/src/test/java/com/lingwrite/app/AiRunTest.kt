// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.ui.ai.AdoptTarget
import com.lingwrite.app.ui.ai.AiRun
import com.lingwrite.app.ui.ai.AiRunState
import com.lingwrite.app.ui.ai.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F1：AiRun 状态机（ai-features §0.1）——Idle → Thinking → Streaming → Ready → Adopted，任何状态可进 Error。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AiRunTest {
    @Test fun happyPath() {
        val run = AiRun()
        assertTrue(run.state is AiRunState.Idle)
        run.begin()
        assertTrue(run.state is AiRunState.Thinking)
        assertTrue(run.busy)
        run.stream("断剑", 0)
        assertEquals("断剑", (run.state as AiRunState.Streaming).partial)
        run.ready(listOf(Candidate("第一版", null, 11L), Candidate("第二版", null, 12L)))
        val ready = run.state as AiRunState.Ready
        assertEquals(2, ready.candidates.size)
        assertEquals(0, ready.selected)
        run.select(1)
        assertEquals("第二版", run.currentText())
        assertEquals(12L, run.currentLogId())
        run.tweakCurrent("第二版（改）")
        assertEquals("第二版（改）", run.currentText())
        run.adopted(AdoptTarget.Body)
        assertTrue(run.state is AiRunState.Adopted)
        assertFalse(run.busy)
        run.settle()
        assertTrue(run.state is AiRunState.Idle)
    }

    @Test fun emptyResultBecomesError() {
        val run = AiRun()
        run.begin()
        run.ready(emptyList())
        assertTrue(run.state is AiRunState.Error)
        assertTrue((run.state as AiRunState.Error).retryable)
    }

    @Test fun failureCarriesRetryCount() {
        val run = AiRun()
        run.begin()
        run.fail("网络超时", retries = 1)
        val e = run.state as AiRunState.Error
        assertEquals("网络超时", e.message)
        assertEquals(1, e.retries)
    }

    @Test fun resetCancelsToIdle() {
        val run = AiRun()
        run.begin()
        run.stream("x")
        run.reset()
        assertTrue(run.state is AiRunState.Idle)
    }

    /** 流式只在 Thinking/Streaming 时推进，Ready 之后不会被迟到的分片改回去。 */
    @Test fun latePartialDoesNotOverwriteReady() {
        val run = AiRun()
        run.begin()
        run.ready(listOf(Candidate("定稿")))
        run.stream("迟到分片")
        assertEquals("定稿", run.currentText())
    }
}
