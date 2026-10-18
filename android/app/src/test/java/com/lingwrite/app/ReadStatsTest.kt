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

    // 审查 #13 B：带 % 的小数不能再放大 100 倍。
    @Test fun percentHandlesSignFractionAndInvalid() {
        assertEquals(45, ReadStats.percent("45%"))
        assertEquals(45, ReadStats.percent("45"))
        assertEquals(45, ReadStats.percent("0.45"))
        assertEquals(46, ReadStats.percent("45.5%"))
        assertEquals(46, ReadStats.percent(" 45.5 ％ "))
        assertEquals(1, ReadStats.percent("1%"))
        assertEquals(1, ReadStats.percent("0.8%"))
        assertEquals(1, ReadStats.percent("0.5%"))
        assertEquals(8, ReadStats.percent("0.08"))
        assertEquals(100, ReadStats.percent("100%"))
        assertEquals(100, ReadStats.percent("1"))
        assertEquals(100, ReadStats.percent("130%"))
        assertEquals(-1, ReadStats.percent(""))
        assertEquals(-1, ReadStats.percent("abc"))
        assertEquals(-1, ReadStats.percent("%"))
        assertEquals(-1, ReadStats.percent("-5%"))
    }

    @Test fun parseTableKeepsLowPercentValues() {
        val rows = ReadStats.parseTable("章节\t追读\t完读率\n1\t1000\t0.8%\n2\t900\t1%")
        assertEquals(listOf(1, 1), rows.map { it.completion })
    }

    @Test fun parseEnglishAndJapaneseHeaders() {
        val en = ReadStats.parseTable("Chapter,Reads,Completion,Favorites\n1,1000,45%,10\n2,8000,0.4,8")
        assertEquals(listOf(1, 2), en.map { it.chapter })
        assertEquals(listOf(45, 40), en.map { it.completion })
        assertEquals(1000, en[0].reads); assertEquals(10, en[0].favorites)
        val ja = ReadStats.parseTable("話\t閲覧数\t読了率\tブックマーク\n第1話\t500\t30%\t4")
        assertEquals(1, ja.size)
        assertEquals(500, ja[0].reads); assertEquals(30, ja[0].completion); assertEquals(4, ja[0].favorites)
    }
}
