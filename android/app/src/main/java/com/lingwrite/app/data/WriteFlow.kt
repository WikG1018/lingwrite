// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.ai.PromptContract
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.ForeshadowEntity
import com.lingwrite.app.data.db.VolumeSummaryEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 写前资料 / 写后自查；只读本地数据，不自动写正文。 */
data class BeforeData(
    val plan: ChapterPlanEntity?,
    val foreshadows: List<ForeshadowEntity>,
    val summaries: List<Pair<Int, String>>,
    val volumeSummary: VolumeSummaryEntity?,
)

data class AfterStatus(
    val wordCount: Int,
    val planFilled: Boolean,
    val landedGaps: List<String>,
    val openForeshadows: Int,
    val aiTasteFlags: Int,
)

object WriteFlow {
    suspend fun before(bookId: Long, chapterId: Long, chapterNumber: Int): BeforeData {
        val plan = AppGraph.db.plan().chapterOnce(chapterId)
        val fs = QuickRef.foreshadows(bookId, chapterNumber, "")
        val (sum, volume) = QuickRef.summaries(bookId, chapterId, chapterNumber)
        return BeforeData(plan, fs, sum.map { it.number to it.text }, volume)
    }

    /** 让 AI 给 3 个开场方向（只返回短句候选，不写正文）。 */
    suspend fun directions(bookId: Long, chapterId: Long, chapterNumber: Int, n: Int = 3): List<String> {
        val d = before(bookId, chapterId, chapterNumber)
        val user = buildString {
            append("给这一章想 $n 个不同的开场方向，每个 20 字以内，只写方向，不写正文、不写成段落。\n")
            append(PromptContract.JSON_ARRAY).append('\n')
            append("格式：[\"方向一\",\"方向二\",\"方向三\"]\n\n")
            d.plan?.takeIf { !it.isEmpty() }?.let { append("【本章章纲】\n").append(it.asPrompt()).append('\n') }
            if (d.summaries.isNotEmpty()) append("【前情】\n").append(d.summaries.joinToString("\n") { "第 ${it.first} 章：${it.second}" }).append('\n')
            append("【本章标的故事内时间】").append(d.plan?.storyTime.orEmpty()).append('\n')
        }
        val attempt = JsonReply.completeJson("directions", PromptContract.JSON_ONLY, user, bookId, chapterId, 500, array = true)
        val arr = attempt.json?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull()?.trim()?.takeIf { s -> s.isNotEmpty() } }.take(n)
    }

    suspend fun after(bookId: Long, chapterId: Long): AfterStatus {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId)
        val plan = db.plan().chapterOnce(chapterId)
        val gaps = plan?.let { PlanSync.landedGaps(it) }.orEmpty()
        val open = db.checks().foreshadowsOnce(bookId).count { it.status == "open" }
        val p = AppGraph.prefs.flow.first()
        val flags = c?.content?.let { Style.aiTaste(it, bannedList(p.bannedWords)).size } ?: 0
        return AfterStatus(
            wordCount = c?.wordCount ?: 0,
            planFilled = plan != null && (plan.event.isNotBlank() || plan.hook.isNotBlank() || plan.cliff.isNotBlank()),
            landedGaps = gaps,
            openForeshadows = open,
            aiTasteFlags = flags,
        )
    }
}
