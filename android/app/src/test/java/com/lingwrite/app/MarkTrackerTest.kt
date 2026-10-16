// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.AiMark
import com.lingwrite.app.data.MarkTracker
import com.lingwrite.app.data.ParaDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkTrackerTest {
    @Test fun shiftsAfterInsertBefore() {
        val m = MarkTracker.adjust("ab[AI]cd", "xxab[AI]cd", listOf(AiMark(2, 6)))
        assertEquals(AiMark(4, 8, "gen"), m.single())
    }
    @Test fun editInsideBecomesEdited() {
        val m = MarkTracker.adjust("ab[AI]cd", "ab[AXI]cd", listOf(AiMark(2, 6)))
        assertEquals(AiMark(2, 7, "edited"), m.single())
    }
    @Test fun untouchedBefore() {
        assertEquals(AiMark(0, 2), MarkTracker.adjust("ABcd", "ABcdzz", listOf(AiMark(0, 2))).single())
    }
    @Test fun diff() {
        val d = ParaDiff.diff("一\n二\n三", "一\n三\n四")
        assertEquals(listOf(ParaDiff.Op.Same, ParaDiff.Op.Del, ParaDiff.Op.Same, ParaDiff.Op.Add), d.map { it.op })
    }
}
