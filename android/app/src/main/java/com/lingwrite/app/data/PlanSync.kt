// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import androidx.room.withTransaction
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.ChapterPlanVersionEntity
import com.lingwrite.app.data.db.VolumePlanEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject

data class PlanDiff(val field: String, val before: String, val after: String)

data class PlanProposal(
    val chapterId: Long,
    val before: ChapterPlanEntity,
    val after: ChapterPlanEntity,
    val diffs: List<PlanDiff>,
)

data class VolumeProgress(val volumeId: Long, val title: String, val planned: Int, val actual: Int)

/** 章纲-正文同步：AI 只给草稿，作者确认后才写；每次回写先留快照。 */
object PlanSync {
    private val fields = listOf("event", "hook", "cliff", "note", "storyTime")

    fun diff(before: ChapterPlanEntity, after: ChapterPlanEntity): List<PlanDiff> = fields.mapNotNull { f ->
        val a = field(before, f); val b = field(after, f)
        if (a == b) null else PlanDiff(f, a, b)
    }

    fun field(p: ChapterPlanEntity, name: String): String = when (name) {
        "event" -> p.event; "hook" -> p.hook; "cliff" -> p.cliff; "note" -> p.note; "storyTime" -> p.storyTime; else -> ""
    }

    fun landedGaps(p: ChapterPlanEntity): List<String> {
        val l = parseLanded(p.landed) ?: return emptyList()
        return buildList {
            if (l.event == false) add("event")
            if (l.hook == false) add("hook")
            if (l.cliff == false) add("cliff")
        }
    }

    suspend fun propose(chapterId: Long): PlanProposal? {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId) ?: return null
        if (c.content.isBlank()) return null
        val plan = db.plan().chapterOnce(chapterId) ?: ChapterPlanEntity(chapterId, c.bookId)
        val user = OutlinePrompts.resync(plan, c.content, landedGaps(plan))
        val raw = AppGraph.ai.complete("outline", OutlinePrompts.SYS, user, c.bookId, chapterId, 1200).getOrNull() ?: return null
        val json = (JsonReply.parse(raw) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject ?: extractJson(raw, array = false)?.jsonObject ?: return null
        fun s(k: String) = json.str(k).trim()
        val after = plan.copy(
            event = s("event").ifBlank { plan.event },
            hook = s("hook").ifBlank { plan.hook },
            cliff = s("cliff").ifBlank { plan.cliff },
            note = s("note"),
            storyTime = s("storyTime").ifBlank { plan.storyTime },
        )
        return PlanProposal(chapterId, plan, after, diff(plan, after))
    }

    /** 先写快照，再更新章纲；返回快照 id。 */
    suspend fun apply(proposal: PlanProposal, note: String = ""): Long = AppGraph.db.withTransaction {
        val dao = AppGraph.db.plan()
        val before = dao.chapterOnce(proposal.chapterId) ?: proposal.before
        val id = dao.insertPlanVersion(ChapterPlanVersionEntity(
            chapterId = proposal.chapterId, bookId = before.bookId,
            planJson = LingJson.encodeToString(ChapterPlanEntity.serializer(), before), note = note,
        ))
        dao.upsertChapter(proposal.after)
        dao.prunePlanVersions(proposal.chapterId, 10)
        id
    }

    suspend fun restore(chapterId: Long, versionId: Long): ChapterPlanEntity? {
        val dao = AppGraph.db.plan()
        val v = dao.planVersion(versionId) ?: return null
        if (v.chapterId != chapterId) return null
        val plan = runCatching { LingJson.decodeFromString(ChapterPlanEntity.serializer(), v.planJson) }.getOrNull() ?: return null
        dao.upsertChapter(plan)
        return plan
    }

    suspend fun volumeProgress(bookId: Long): List<VolumeProgress> {
        val db = AppGraph.db
        val vols = db.chapters().volumesOnce(bookId)
        val plans = db.plan().volumes(bookId).first()
        val counts = db.chapters().headersOnce(bookId).groupingBy { it.volumeId }.eachCount()
        return plans.map { p: VolumePlanEntity ->
            val v = vols.firstOrNull { it.id == p.volumeId }
            VolumeProgress(p.volumeId, v?.title.orEmpty(), p.chapterCount, counts[p.volumeId] ?: 0)
        }.filter { it.planned > 0 }
    }
}
