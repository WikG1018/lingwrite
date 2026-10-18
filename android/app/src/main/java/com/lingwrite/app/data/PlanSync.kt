// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import androidx.room.withTransaction
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.i18n.trOr
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

    /** 只改 [name] 这一个字段。 */
    fun withField(p: ChapterPlanEntity, name: String, value: String): ChapterPlanEntity = when (name) {
        "event" -> p.copy(event = value); "hook" -> p.copy(hook = value); "cliff" -> p.copy(cliff = value)
        "note" -> p.copy(note = value); "storyTime" -> p.copy(storyTime = value); else -> p
    }

    /** 字段 id → 界面名称（不把 event / storyTime 这类内部 id 显示给作者）。 */
    fun fieldLabel(name: String): String = when (name) {
        "event" -> trOr(R.string.outline_051, "核心事件")
        "hook" -> trOr(R.string.outline_052, "开篇钩子")
        "cliff" -> trOr(R.string.outline_053, "章尾悬念")
        "storyTime" -> trOr(R.string.outline_094, "故事内时间")
        "note" -> trOr(R.string.outline_110, "备注")
        else -> name
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
            // AI 返回空 note 时保留作者原来的备注，不清空（审查 #14）。
            note = s("note").ifBlank { plan.note },
            storyTime = s("storyTime").ifBlank { plan.storyTime },
        )
        return PlanProposal(chapterId, plan, after, diff(plan, after))
    }

    /**
     * 先写快照，再更新章纲；返回快照 id。
     * 只合并提议里有差异的字段：作者在提议出来之后手动改过的其他字段不会被 [PlanProposal.after] 整条覆盖（审查 #14）。
     */
    suspend fun apply(proposal: PlanProposal, note: String = ""): Long = AppGraph.db.withTransaction {
        val dao = AppGraph.db.plan()
        val latest = dao.chapterOnce(proposal.chapterId) ?: proposal.before
        val id = dao.insertPlanVersion(ChapterPlanVersionEntity(
            chapterId = proposal.chapterId, bookId = latest.bookId,
            planJson = LingJson.encodeToString(ChapterPlanEntity.serializer(), latest), note = note,
        ))
        val merged = proposal.diffs.fold(latest) { acc, d -> withField(acc, d.field, d.after) }
        dao.upsertChapter(merged)
        dao.prunePlanVersions(proposal.chapterId, 10)
        id
    }

    /** 撤销到某个快照：先给当前章纲再留一份快照（撤销本身也能撤销），整个过程在一个事务里。 */
    suspend fun restore(chapterId: Long, versionId: Long): ChapterPlanEntity? = AppGraph.db.withTransaction {
        val dao = AppGraph.db.plan()
        val v = dao.planVersion(versionId) ?: return@withTransaction null
        if (v.chapterId != chapterId) return@withTransaction null
        val plan = runCatching { LingJson.decodeFromString(ChapterPlanEntity.serializer(), v.planJson) }.getOrNull() ?: return@withTransaction null
        dao.chapterOnce(chapterId)?.let { current ->
            dao.insertPlanVersion(ChapterPlanVersionEntity(
                chapterId = chapterId, bookId = current.bookId,
                planJson = LingJson.encodeToString(ChapterPlanEntity.serializer(), current), note = "restore",
            ))
        }
        dao.upsertChapter(plan)
        dao.prunePlanVersions(chapterId, 10)
        plan
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
