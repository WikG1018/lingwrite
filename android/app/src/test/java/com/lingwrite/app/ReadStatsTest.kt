// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ReadStats
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.ReadMetricEntity
import org.junit.Assert.*
import org.junit.Test

class ReadStatsTest {
    @Test fun parseHeaderTableWithPercentAndCommas() {
        val text = "章节\t追读\t完读率\t收藏\n1\t1,200\t12.3%\t80\n第2章\t900\t0.08\t70\n第 3 章 雨夜\t1,500\t15%\t90"
        val rows = ReadStats.parseTable(text)
        assertEquals(listOf(1, 2, 3), rows.map { it.chapter })
        assertEquals(1200, rows[0].reads)
        assertEquals(12, rows[0].completion)
        assertEquals(8, rows[1].completion)
        assertEquals(15, rows[2].completion)
    }

    @Test fun parsePositionalTableWithoutHeader() {
        val rows = ReadStats.parseTable("1, 1000, 50, 10\n2, 800, 40, 8")
        assertEquals(2, rows.size)
        assertEquals(1000, rows[0].reads)
        assertEquals(50, rows[0].completion)
        assertEquals(10, rows[0].favorites)
    }

    @Test fun latestKeepsTheNewestRowPerChapter() {
        val rows = listOf(
            ReadMetricEntity(id = 1, bookId = 1, chapterNumber = 1, completion = 30, recordedAt = 10),
            ReadMetricEntity(id = 2, bookId = 1, chapterNumber = 1, completion = 40, recordedAt = 20),
            ReadMetricEntity(id = 3, bookId = 1, chapterNumber = 2, completion = 20, recordedAt = 10),
        )
        val latest = ReadStats.latest(rows)
        assertEquals(listOf(1, 2), latest.map { it.chapterNumber })
        assertEquals(40, latest[0].completion)
    }

    @Test fun insightsFindDropBelowMedianAndPlanGaps() {
        val rows = listOf(
            ReadMetricEntity(id = 1, bookId = 1, chapterNumber = 1, completion = 60, recordedAt = 1),
            ReadMetricEntity(id = 2, bookId = 1, chapterNumber = 2, completion = 45, recordedAt = 1),
            ReadMetricEntity(id = 3, bookId = 1, chapterNumber = 3, completion = 50, recordedAt = 1),
        )
        val plans = mapOf(2L to ChapterPlanEntity(chapterId = 2, bookId = 1, event = "事件", cliff = ""))
        val ins = ReadStats.insights(rows, plans, mapOf(2L to 2), mapOf(1 to 3000, 2 to 1200, 3 to 3000), 3000)
        fun at(n: Int) = ins.first { it.chapter == n }
        assertEquals(25, at(2).dropPercent)
        assertTrue(at(2).noCliff)
        assertTrue(at(2).shortChapter)
        assertTrue(at(2).belowMedian)
    }
}
