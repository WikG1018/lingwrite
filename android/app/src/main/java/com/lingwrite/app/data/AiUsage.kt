// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.CallLogEntity
import kotlinx.coroutines.flow.first

data class ChapterUsage(
    val chapterId: Long,
    val number: Int,
    val title: String,
    val words: Int,
    val share: PublishCheck.AiShare,
    val calls: Int,
    val accepted: Int,
    val edited: Int,
    val rejected: Int,
)

data class TaskUsage(val task: String, val calls: Int, val accepted: Int)

data class AiUsageReport(
    val chapters: List<ChapterUsage>,
    val totalShare: PublishCheck.AiShare,
    val totalCalls: Int,
    val totalAccepted: Int,
    val totalEdited: Int,
    val totalRejected: Int,
    val tasks: List<TaskUsage>,
) {
    val adoptedPercent: Int get() {
        val denom = totalAccepted + totalEdited + totalRejected
        return if (denom <= 0) 0 else (totalAccepted + totalEdited) * 100 / denom
    }
}

/** 只统计 App 内标记的 AI 段落与 call_logs；不联网、不评判平台合规。 */
object AiUsage {
    suspend fun report(bookId: Long): AiUsageReport {
        val db = AppGraph.db
        val headers = db.chapters().headersOnce(bookId)
        val logs = db.ai().logs(bookId, 5000).first().filter { it.task != "test" }
        val byChapter = logs.groupBy { it.chapterId }
        var gen = 0; var edited = 0; var total = 0; var gParas = 0; var eParas = 0
        var calls = 0; var accepted = 0; var editedN = 0; var rejected = 0
        val chapters = headers.mapNotNull { h ->
            val c = db.chapters().get(h.id) ?: return@mapNotNull null
            val s = PublishCheck.share(c.content, c.aiMarks)
            gen += s.genChars; edited += s.editedChars; total += s.totalChars; gParas += s.genParagraphs; eParas += s.editedParagraphs
            val cl = byChapter[h.id].orEmpty()
            val a = cl.count { it.outcome == "accepted" }
            val e = cl.count { it.outcome == "edited" }
            val r = cl.count { it.outcome == "rejected" }
            calls += cl.size; accepted += a; editedN += e; rejected += r
            ChapterUsage(h.id, h.number, h.title, h.wordCount, s, cl.size, a, e, r)
        }
        val tasks = logs.groupBy { it.task }.map { (t, l) -> TaskUsage(t, l.size, l.count { it.outcome == "accepted" || it.outcome == "edited" }) }
            .sortedByDescending { it.calls }
        return AiUsageReport(
            chapters = chapters,
            totalShare = PublishCheck.AiShare(gen, edited, total, gParas, eParas),
            totalCalls = calls, totalAccepted = accepted, totalEdited = editedN, totalRejected = rejected,
            tasks = tasks,
        )
    }
}
