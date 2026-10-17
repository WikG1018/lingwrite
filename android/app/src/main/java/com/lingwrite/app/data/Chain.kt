// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.db.BookPlanEntity
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.VolumePlanEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 总纲链路的持久状态（存在 book_plans.chain 列，docs/v0.2/outline-chain.md §2）。
 * - cands：每一步未采纳的候选 JSON（步号 1–6）
 * - upstream：每一步采纳时上游内容的 hashCode，用来判断“上游已改”
 */
@Serializable
data class ChainState(
    val cands: Map<Int, String> = emptyMap(),
    val upstream: Map<Int, Int> = emptyMap(),
    val idea: String = "",
    val genre: String = "",
    val chapterVolumeId: Long? = null,
    val platform: String = "",
) {
    fun encode(): String = LingJson.encodeToString(serializer(), this)

    companion object {
        fun parse(s: String): ChainState = runCatching { LingJson.decodeFromString(serializer(), s) }.getOrNull() ?: ChainState()
    }
}

/**
 * 总纲的拼接格式（链路第 ③ 步，三段：主线 / 阶段 / 结局）：
 * 主线：…
 * 阶段一 名：目标；冲突；阶段结局
 * …
 * 结局：…
 */
object SynopsisFormat {
    @Serializable
    data class Stage(val name: String = "", val goal: String = "", val conflict: String = "", val end: String = "")

    fun build(spine: String, stages: List<Stage>, ending: String): String = buildString {
        if (spine.isNotBlank()) append("主线：").append(spine).append('\n')
        stages.forEachIndexed { i, s ->
            append("阶段").append(s.name.ifBlank { CnNum.of(i + 1) })
            listOf(s.goal, s.conflict, s.end).filter { it.isNotBlank() }.let { parts ->
                if (parts.isNotEmpty()) {
                    if (!endsWith(' ') && !isEmpty()) append(' ')
                    append(parts.joinToString("；"))
                }
            }
            append('\n')
        }
        if (ending.isNotBlank()) append("结局：").append(ending)
    }.trim()

    /** 解析回三段（宽容：解析失败时整段当 spine）。 */
    fun parse(s: String): Triple<String, List<Stage>, String> {
        val spine = s.lineSequence().firstOrNull { it.startsWith("主线") }?.removePrefix("主线：")?.removePrefix("主线:")?.trim().orEmpty()
        val ending = s.lineSequence().lastOrNull { it.startsWith("结局") }?.removePrefix("结局：")?.removePrefix("结局:")?.trim().orEmpty()
        val stages = s.lineSequence().filter { it.startsWith("阶段") }.map { line ->
            val body = line.removePrefix("阶段").trim()
            val name = body.substringBefore(' ').trim().ifBlank { body.substringBefore('：').trim() }
            val rest = body.substringAfter(' ', "").ifBlank { body.substringAfter('：', "") }
            val parts = rest.split('；', ';')
            Stage(name, parts.getOrNull(0)?.trim().orEmpty(), parts.getOrNull(1)?.trim().orEmpty(), parts.getOrNull(2)?.trim().orEmpty())
        }.toList()
        return Triple(spine, stages, ending)
    }
}

/** 链路步号。 */
object ChainSteps {
    const val IDEA = 1
    const val CORE = 2
    const val SYNOPSIS = 3
    const val VOLUMES = 4
    const val CHAPTERS = 5
    const val FIRST_CHAPTER = 6

    val labels = mapOf(1 to "创意", 2 to "核心设定", 3 to "总纲", 4 to "卷纲", 5 to "章纲", 6 to "第一章")
}

/** 第 ⑥ 步的场景节拍：正文上方的可勾选清单（chapter_plans.beats）。 */
@Serializable
data class Beat(val text: String, val done: Boolean = false)

fun parseBeats(s: String): List<Beat> = runCatching { LingJson.decodeFromString(ListSerializer(Beat.serializer()), s) }.getOrDefault(emptyList())
fun encodeBeats(l: List<Beat>): String = LingJson.encodeToString(ListSerializer(Beat.serializer()), l)

/**
 * 链路状态的读写（C3「上游已改」）：
 * - 每一步生成候选时把原始 JSON 存进 [ChainState.cands]，重新进入某一步能接着看未采纳的候选；
 * - 每一步采纳时把「当时用到的上游内容指纹」存进 [ChainState.upstream]；
 * - [changed] 用当前内容重新算指纹，不一致就是「上游已改，可重新生成」。
 */
object ChainStore {
    /** 该步采纳时用到哪些上游内容（顺序固定，只做指纹比对，不做展示）。 */
    fun inputParts(bp: BookPlanEntity, vplans: List<VolumePlanEntity>, step: Int): List<String> = when (step) {
        1 -> listOf(bp.idea)
        2 -> listOf(bp.idea, bp.logline)
        3 -> listOf(bp.idea, bp.logline, bp.sellingPoints)
        4 -> listOf(bp.synopsis)
        5 -> listOf(bp.synopsis, vplans.sortedBy { it.volumeId }.joinToString("|") { it.outline + it.goal + it.climax })
        else -> listOf(bp.synopsis, vplans.sortedBy { it.volumeId }.joinToString("|") { it.outline })
    }

    fun signature(bp: BookPlanEntity, vplans: List<VolumePlanEntity>, step: Int): Int =
        inputParts(bp, vplans, step).hashCode()

    private suspend fun edit(bookId: Long, f: (BookPlanEntity, ChainState) -> ChainState) {
        val dao = AppGraph.db.plan()
        val bp = dao.bookOnce(bookId) ?: BookPlanEntity(bookId)
        val st = f(bp, ChainState.parse(bp.chain))
        dao.upsertBook(bp.copy(chain = st.encode()))
    }

    /** 缓存某一步未采纳的候选（存模型原文，读取时用同一个 parse 函数）。 */
    suspend fun putCands(bookId: Long, step: Int, raw: String) =
        edit(bookId) { _, st -> if (raw.isBlank()) st else st.copy(cands = st.cands + (step to raw)) }

    suspend fun clearCands(bookId: Long, step: Int) = edit(bookId) { _, st -> st.copy(cands = st.cands - step) }
    suspend fun setChapterVolume(bookId: Long, volumeId: Long) = edit(bookId) { _, st -> st.copy(chapterVolumeId = volumeId) }

    suspend fun cands(bookId: Long, step: Int): String? =
        ChainState.parse(AppGraph.db.plan().bookOnce(bookId)?.chain.orEmpty()).cands[step]

    /** 采纳某一步后记录上游指纹（读库里的最新内容）。 */
    suspend fun markAdopted(bookId: Long, step: Int, vplans: List<VolumePlanEntity> = emptyList()) {
        edit(bookId) { bp, st -> st.copy(upstream = st.upstream + (step to signature(bp, vplans, step))) }
    }

    /** true = 该步的上游在采纳之后改过，可以重新生成。 */
    fun changed(bp: BookPlanEntity, vplans: List<VolumePlanEntity>, step: Int): Boolean {
        val recorded = ChainState.parse(bp.chain).upstream[step] ?: return false
        return signature(bp, vplans, step) != recorded
    }
}

/** 章纲候选（数据层；UI 的 PlanSuggestion 由它映射，方便测试直接驱动链路）。 */
data class ChapterPlanDraft(val title: String, val event: String, val hook: String, val cliff: String, val custom: Map<String, String> = emptyMap())

/**
 * 链路每一步的 AI 调用与采纳（C2/C4/C5/A7）。
 * UI（ChainRoute / 向导第 8 步）和测试走同一套函数：提示词 → 真重试解析 → 采纳写库。
 */
object ChainRunner {
    /** ① 创意 → 3 个梗概候选。 */
    suspend fun pitches(bookId: Long, idea: String, genre: String, platform: String): List<Triple<String, String, String>> {
        val att = JsonReply.completeJson("wizard", ChainPrompts.SYS, ChainPrompts.pitches(idea, genre, platform), bookId, null, 900)
        if (att.error != null) return emptyList()
        ChainStore.putCands(bookId, 1, att.raw)
        return ChainPrompts.parsePitches(att.raw)
    }

    /** ② 卖点候选。 */
    suspend fun sellingPoints(bookId: Long): List<String> {
        val bp = AppGraph.db.plan().bookOnce(bookId) ?: BookPlanEntity(bookId)
        val att = JsonReply.completeJson("wizard", ChainPrompts.SYS, ChainPrompts.sellingPoints(ChainPrompts.upstream(bp)), bookId, null, 400)
        if (att.error != null) return emptyList()
        ChainStore.putCands(bookId, 2, att.raw)
        return ChainPrompts.parseListReply(att.raw)
    }

    /** ③ 总纲三段。 */
    suspend fun synopsis(bookId: Long): Triple<String, List<SynopsisFormat.Stage>, String>? {
        val bp = AppGraph.db.plan().bookOnce(bookId) ?: BookPlanEntity(bookId)
        val att = JsonReply.completeJson("outline", ChainPrompts.SYS, ChainPrompts.synopsis(ChainPrompts.upstream(bp)), bookId, null, 1200)
        if (att.error != null) return null
        ChainStore.putCands(bookId, 3, att.raw)
        return ChainPrompts.parseSynopsis(att.raw)
    }

    /** ④ 拆卷候选。 */
    suspend fun volumes(bookId: Long, n: Int): List<ChainPrompts.VolumeDraft> {
        val bp = AppGraph.db.plan().bookOnce(bookId) ?: BookPlanEntity(bookId)
        val att = JsonReply.completeJson("outline", ChainPrompts.SYS, ChainPrompts.volumes(ChainPrompts.upstream(bp), n), bookId, null, (n * 320 + 800).coerceAtMost(6000))
        if (att.error != null) return emptyList()
        ChainStore.putCands(bookId, 4, att.raw)
        return ChainPrompts.parseVolumes(att.raw)
    }

    /** ⑤ 章纲候选：带入这一卷已有章纲，接着往后拆（A7 滚动规划）。 */
    suspend fun chapters(bookId: Long, volumeId: Long, n: Int, hint: String = ""): List<ChapterPlanDraft> {
        val dao = AppGraph.db.plan()
        val bp = dao.bookOnce(bookId) ?: BookPlanEntity(bookId)
        val vo = dao.volumeOnce(volumeId)?.outline.orEmpty()
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).filter { it.volumeId == volumeId }.sortedBy { it.number }
        val plans = dao.chaptersOnce(bookId).associateBy { it.chapterId }
        val prev = chs.mapNotNull { ch -> plans[ch.id]?.takeIf { !it.isEmpty() }?.let { ChapterLabel.short(ch.number) + "：" + it.event } }
            .takeLast(5).joinToString("\n")
        val count = n.coerceIn(1, 20)
        val att = JsonReply.completeJson(
            "outline", ChainPrompts.SYS,
            OutlinePrompts.chapters(bp.synopsis, vo.ifBlank { "（未写，按总纲推进）" }, prev, count, parseList(bp.fields), hint),
            bookId, null, (count * 220 + 900).coerceAtMost(8000),
        )
        if (att.error != null) return emptyList()
        ChainStore.putCands(bookId, 5, att.raw)
        ChainStore.setChapterVolume(bookId, volumeId)
        return ChainPrompts.parseChapterPlans(att.raw, parseList(bp.fields))
    }

    /** ⑥ 场景节拍（辅助/生成共用）。 */
    suspend fun beats(bookId: Long, chapterId: Long): List<String> {
        val dao = AppGraph.db.plan()
        val bp = dao.bookOnce(bookId) ?: BookPlanEntity(bookId)
        val plan = dao.chapterOnce(chapterId)?.asPrompt() ?: ""
        val att = JsonReply.completeJson("outline", ChainPrompts.SYS, ChainPrompts.beats(ChainPrompts.upstream(bp), plan, bp.style), bookId, chapterId, 500)
        if (att.error != null) return emptyList()
        return ChainPrompts.parseBeats(att.raw)
    }

    /** ⑥ 开篇示意（辅助模式，只进便签）。 */
    suspend fun openings(bookId: Long, beats: List<String>): List<Pair<String, String>> {
        val bp = AppGraph.db.plan().bookOnce(bookId) ?: BookPlanEntity(bookId)
        val att = JsonReply.completeJson("outline", ChainPrompts.SYS, ChainPrompts.openings(ChainPrompts.upstream(bp), beats), bookId, null, 500)
        val json = att.json ?: return emptyList()
        return runCatching {
            (json as kotlinx.serialization.json.JsonArray).map { e ->
                val o = e.jsonObject
                o.str("text") to o.str("effect")
            }
        }.getOrDefault(emptyList())
    }

    /** 采纳节拍：写进 chapter_plans.beats；辅助模式再把节拍和开篇示意存进本章便签（绝不进正文）。 */
    suspend fun adoptBeats(bookId: Long, chapterId: Long, beats: List<String>, openings: List<Pair<String, String>>, assistMode: Boolean) {
        val dao = AppGraph.db.plan()
        val cp = dao.chapterOnce(chapterId) ?: ChapterPlanEntity(chapterId, bookId)
        dao.upsertChapter(cp.copy(beats = encodeBeats(beats.map { Beat(it) })))
        if (assistMode) {
            AppGraph.db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = chapterId, kind = "beat", text = beats.joinToString("\n") { "· $it" }))
            openings.forEach { (t, e) -> AppGraph.db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = chapterId, kind = "direction", text = "【开篇示意】$t（$e）")) }
        }
    }

    /**
     * 采纳勾选的卷（C4）。[overwrite] = true 时覆盖同序号的卷纲（保留卷内章节），否则追加在后面。
     * 返回实际处理的卷数。
     */
    suspend fun adoptVolumes(bookId: Long, drafts: List<ChainPrompts.VolumeDraft>, overwrite: Boolean, originalIndices: List<Int> = drafts.indices.toList()): Int {
        val dao = AppGraph.db.plan()
        val existing = AppGraph.db.chapters().volumesOnce(bookId).sortedBy { it.sort }
        var done = 0
        drafts.forEachIndexed { i, v ->
            val target = if (overwrite) existing.getOrNull(originalIndices[i]) else null
            if (target != null) {
                val vp = dao.volumeOnce(target.id) ?: VolumePlanEntity(target.id, bookId)
                dao.upsertVolume(vp.copy(outline = v.outline, goal = v.goal, climax = v.climax, chapterCount = v.chapters))
                if (v.name.isNotBlank() && v.name != target.title) AppGraph.db.chapters().updateVolume(target.copy(title = v.name))
                done++
            } else if (v.name.isNotBlank() || v.outline.isNotBlank()) {
                val id = AppGraph.books.addVolume(bookId, v.name)
                dao.upsertVolume(VolumePlanEntity(id, bookId, v.outline, v.goal, v.climax, v.chapters))
                done++
            }
        }
        return done
    }

    /** 采纳章纲：新增章节并写 chapter_plans，章名不带编号。返回新建/更新的章数。 */
    suspend fun adoptChapters(bookId: Long, volumeId: Long, drafts: List<ChapterPlanDraft>): Int {
        val dao = AppGraph.db.plan()
        drafts.forEach { s ->
            val chId = AppGraph.books.addChapterTo(bookId, volumeId, Naming.stripChapter(s.title))
            dao.upsertChapter(ChapterPlanEntity(chId, bookId, s.event, s.hook, s.cliff, custom = encodeMap(s.custom), threads = "主线", fromAi = true))
        }
        return drafts.size
    }
}
