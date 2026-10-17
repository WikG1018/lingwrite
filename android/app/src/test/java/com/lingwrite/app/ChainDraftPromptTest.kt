// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ChainPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 起草提示词：传进来的节拍必须全部出现在提示词里，段号/总数正确，第一章提示不能串到后面章节。 */
class ChainDraftPromptTest {
    @Test fun includesEveryBeatAndSegmentInfo() {
        val p = ChainPrompts.draft("", listOf("节拍甲", "节拍乙"), "", part = 3, total = 4, drafted = "")
        assertTrue(p.contains("节拍甲"))
        assertTrue(p.contains("节拍乙"))
        assertTrue(p.contains("3/4"))
    }

    @Test fun firstChapterHintOnlyForFirstChapter() {
        assertTrue(ChainPrompts.draft("", listOf("甲"), "", 1, 3, "", first = true).contains("第一章"))
        assertFalse(ChainPrompts.draft("", listOf("甲"), "", 2, 3, "", first = false).contains("第一章"))
    }
}
