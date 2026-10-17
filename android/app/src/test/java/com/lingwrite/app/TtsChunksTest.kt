// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.TtsChunks
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
}
