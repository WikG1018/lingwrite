// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.TtsChunks
import com.lingwrite.app.data.TtsLang
import com.lingwrite.app.data.TtsQueueState
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TtsChunksTest {
    @Test fun splitBySentencesAndMaxLength() {
        val text = "第一句。第二句！第三句？\n\n第四句。" + "很长的句子".repeat(60) + "。"
        val chunks = TtsChunks.split(text, max = 80)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 80 })
        assertTrue(chunks.all { it.isNotBlank() })
        assertEquals(text.replace("\n", "").trim(), chunks.joinToString("").replace("\n", "").trim())
    }

    @Test fun blankTextHasNoChunks() {
        assertEquals(emptyList<String>(), TtsChunks.split("  \n\n"))
    }

    // 审查 #17：多段排队时，只有最后一段读完才回到「播放」。
    @Test fun queueStaysSpeakingUntilLastChunk() {
        val q = TtsQueueState()
        val ids = q.begin(3) { "u$it" }
        q.onStart("u0"); assertTrue(q.speaking)
        assertFalse(q.onFinish("u0")); assertTrue("第一段读完仍在读", q.speaking)
        q.onStart("u1"); assertFalse(q.onFinish("u1")); assertTrue(q.speaking)
        assertTrue(q.onFinish(ids.last())); assertFalse(q.speaking)
    }

    @Test fun staleCallbacksFromPreviousBatchAreIgnored() {
        val q = TtsQueueState()
        q.begin(2) { "a$it" }
        q.begin(2) { "b$it" } // 重新播放：QUEUE_FLUSH
        assertFalse("旧批次最后一段的回调不结束新批次", q.onFinish("a1"))
        assertTrue(q.speaking)
        assertTrue(q.onFinish("b1"))
        q.clear(); assertFalse(q.speaking)
        assertFalse(q.onFinish("b1"))
    }

    @Test fun languageFollowsBookThenSystem() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, TtsLang.localeFor(null))
        assertEquals(Locale.US, TtsLang.localeFor("en"))
        assertEquals(Locale.JAPAN, TtsLang.localeFor("ja"))
        assertEquals(Locale.TRADITIONAL_CHINESE, TtsLang.localeFor("zh-TW"))
        assertEquals(listOf(Locale.JAPAN, Locale.FRANCE), TtsLang.candidates("ja", Locale.FRANCE))
        assertEquals(listOf(Locale.US), TtsLang.candidates("en", Locale.US))
    }
}
