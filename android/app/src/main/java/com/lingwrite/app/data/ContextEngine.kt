// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.AiService
import com.lingwrite.app.data.ai.Prompts
import com.lingwrite.app.data.ai.PromptHooks
import com.lingwrite.app.data.db.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlin.math.sqrt

/** 中文 n-gram 切分（FTS 用）。 */
object Grams {
    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    /** 按非文字字符切成连续片段。 */
    fun runs(s: String): List<String> = Regex("[\\p{IsHan}]+|[A-Za-z0-9]+").findAll(s).map { it.value.lowercase() }.toList()
    fun bigrams(s: String): String = runs(s).joinToString(" ") { r ->
        if (!isCjk(r[0]) || r.length == 1) r else r.windowed(2).joinToString(" ")
    }
    fun unigrams(s: String): String = runs(s).joinToString(" ") { r -> if (isCjk(r[0])) r.toCharArray().joinToString(" ") else r }
    /** 把一个检索词转成 FTS 短语："师父 父的"（二元，查 grams 列）；单个汉字返回 null（见 [unis]）。 */
    fun phrase(term: String): String? {
        val t = term.trim(); if (t.isEmpty()) return null
        val r = runs(t); if (r.isEmpty()) return null
        if (r.size == 1 && r[0].length == 1 && isCjk(r[0][0])) return null
        return "\"" + bigrams(t) + "\""
    }
    /** 多个词：AND（默认，用于搜索框）或 OR（用于上下文检索）。 */
    fun query(terms: List<String>, or: Boolean = false): String? = terms.mapNotNull(::phrase).takeIf { it.isNotEmpty() }?.joinToString(if (or) " OR " else " ")
    /** 全是单字时，查单字列。 */
    fun unis(terms: List<String>): String? = terms.map { it.trim() }.filter { it.length == 1 }.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** 正文切片：按段落累积到约 [size] 字。 */
fun chunk(text: String, size: Int = 300): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    var start = 0; val sb = StringBuilder(); var pos = 0
    for (para in text.split('\n')) {
        if (sb.isEmpty()) start = pos
        if (para.isNotBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(para.trim()) }
        pos += para.length + 1
        if (sb.length >= size) { out += start to sb.toString(); sb.clear() }
    }
    if (sb.isNotBlank()) out += start to sb.toString()
    return out
}

fun cosine(a: FloatArray, b: FloatArray): Float {
    var d = 0f; var na = 0f; var nb = 0f
    for (i in 0 until minOf(a.size, b.size)) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0f || nb == 0f) 0f else d / (sqrt(na) * sqrt(nb))
}
fun FloatArray.encode() = joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it) }
fun decodeVector(s: String) = s.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()

/** 上下文的一部分。priority 越小越重要；裁剪时从大往小删。 */
data class CtxPart(val key: String, val label: String, val priority: Double, val text: String) {
    val tokens get() = AiService.estimateTokens(text.length)
}

/** 按预算裁剪：先整块删掉最低优先级，删到只差一块时截断它；priority ≤ 3 的块（章纲、设定）只截断不删除。 */
fun fitBudget(parts: List<CtxPart>, budget: Int): List<CtxPart> {
    val keep = parts.filter { it.text.isNotBlank() }.sortedBy { it.priority }.toMutableList()
    while (keep.sumOf { it.tokens } > budget && keep.isNotEmpty()) {
        val last = keep.last()
        val over = keep.sumOf { it.tokens } - budget
        val canDrop = last.priority > 3
        if (last.tokens - over >= 80 || !canDrop) {
            val chars = ((last.tokens - over).coerceAtLeast(40) * 1.6).toInt()
            val cut = if (last.key == "cursor" || last.key == "tail") last.text.takeLast(chars) else last.text.take(chars)
            keep[keep.lastIndex] = last.copy(text = cut)
            if (!canDrop || keep.sumOf { it.tokens } <= budget) break
        } else keep.removeAt(keep.lastIndex)
    }
    return keep
}

/** 其他模块追加上下文（例如伏笔台账）。 */
object ContextHooks {
    val extra = mutableListOf<suspend (bookId: Long, chapter: ChapterEntity, text: String) -> List<CtxPart>>()
}

/** 最近一次发给模型的内容（上下文查看器用）。 */
data class CtxSnapshot(val chapterId: Long, val system: String, val parts: List<CtxPart>, val budget: Int, val at: Long = System.currentTimeMillis())

object ContextEngine {
    var last: CtxSnapshot? = null
        private set

    private val db get() = AppGraph.db

    /** 重建单章索引。 */
    suspend fun reindex(c: ChapterEntity) {
        val d = db.context()
        val old = d.passageIds(c.id)
        if (old.isNotEmpty()) old.chunked(500).forEach { d.deleteFts(it) }
        d.deletePassages(c.id)
        chunk(c.content).forEachIndexed { i, (start, t) ->
            val id = d.insertPassage(PassageEntity(bookId = c.bookId, chapterId = c.id, number = c.number, idx = i, start = start, text = t))
            d.insertFts(PassageFts(id, Grams.bigrams(t), Grams.unigrams(t)))
        }
        d.markIndexed(ChapterIndexEntity(c.id, System.currentTimeMillis()))
    }

    /** 把有改动的章节补进索引（可选：补算向量）。 */
    suspend fun refresh(bookId: Long, except: Long = -1) {
        val state = db.context().indexState(bookId).associate { it.chapterId to it.at }
        db.chapters().chaptersOnce(bookId).filter { it.id != except && (state[it.id] ?: 0) < it.updatedAt }.forEach { reindex(it) }
        val p = AppGraph.prefs.flow.first()
        if (p.semanticSearch && p.embedModel.isNotBlank()) {
            db.context().unvectored(bookId).chunked(32).forEach { batch ->
                val r = AppGraph.ai.embed(batch.map { it.text }, bookId).getOrNull() ?: return
                batch.zip(r).forEach { (ps, v) -> db.context().setVector(ps.id, v.encode()) }
            }
        }
    }

    /** 全文搜索（搜索页和上下文检索共用）。 */
    suspend fun search(text: String, bookId: Long, or: Boolean = false, limit: Int = 60): List<Hit> {
        val terms = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return runCatching {
            val sets = terms.map { term ->
                val q = Grams.phrase(term)
                if (q != null) db.context().search(q, bookId, Int.MAX_VALUE)
                else db.context().searchUnis(Grams.unis(listOf(term)) ?: return emptyList(), bookId, Int.MAX_VALUE)
            }
            val ids = if (or) sets.flatMap { it.map(Hit::id) }.toSet() else sets.map { it.map(Hit::id).toSet() }.reduceOrNull { a, b -> a intersect b }.orEmpty()
            sets.flatten().distinctBy { it.id }.filter { it.id in ids }.take(limit)
        }.getOrDefault(emptyList())
    }

    /** 检索相关旧片段：关键词（人名 + 章纲关键词）+ 可选语义。 */
    suspend fun retrieve(bookId: Long, chapter: ChapterEntity, keys: List<String>, probe: String, k: Int = 4): List<Hit> {
        val older = { h: Hit -> h.number < chapter.number - 2 }
        val kw = mutableMapOf<Long, Pair<Hit, Double>>()
        keys.distinct().take(12).forEach { term ->
            search(term, bookId, limit = 30).filter(older).forEach { h -> kw[h.id] = h to ((kw[h.id]?.second ?: 0.0) + 1.0 / (1 + term.length / 6)) }
        }
        val p = AppGraph.prefs.flow.first()
        if (p.semanticSearch && p.embedModel.isNotBlank() && probe.isNotBlank()) {
            AppGraph.ai.embed(listOf(probe.takeLast(800)), bookId).getOrNull()?.firstOrNull()?.let { qv ->
                db.context().vectored(bookId).filter { it.number < chapter.number - 2 }.map { it to cosine(qv, decodeVector(it.vector)) }
                    .sortedByDescending { it.second }.take(k).forEach { (ps, s) ->
                        val h = Hit(ps.id, ps.bookId, ps.chapterId, ps.number, ps.start, ps.text)
                        kw[h.id] = h to ((kw[h.id]?.second ?: 0.0) + s * 2)
                    }
            }
        }
        return kw.values.sortedByDescending { it.second }.take(k).map { it.first }.sortedBy { it.number }
    }

    /** 关键词：出场人物名 + 章纲核心事件里的词组。 */
    fun keywords(names: List<String>, plan: String): List<String> =
        names + plan.split(Regex("[，。、；：！？,.;:!?\\s“”\"（）()]+")).map { it.trim() }.filter { it.length in 2..8 }

    suspend fun build(bookId: Long, chapterId: Long, cursor: Int, text: String): String {
        val p = AppGraph.prefs.flow.first()
        val chapter = db.chapters().get(chapterId) ?: return ""
        val before = text.substring(0, cursor.coerceIn(0, text.length))
        val parts = mutableListOf<CtxPart>()
        val bp = db.plan().bookOnce(bookId)
        val cp = db.plan().chapterOnce(chapterId)
        cp?.takeIf { !it.isEmpty() }?.let { parts += CtxPart("plan", "当前章纲", 2.0, it.asPrompt()) }
        // 设定：本章已提及 + 章纲提及 + 始终带入，按当章状态
        val cards = db.lore().cardsOnce(bookId).filter { !it.pending && it.inject != "never" && (it.firstChapter == null || it.firstChapter <= chapter.number) }
        val states = db.lore().statesForBook(bookId).groupBy { it.cardId }
        val probe = text + (cp?.asPrompt() ?: "")
        val used = cards.filter { it.inject == "always" || it.allNames().any { n -> probe.contains(n) } }
        if (used.isNotEmpty()) parts += CtxPart("lore", "出场人物与设定（第 ${chapter.number} 章时）", 3.0, used.joinToString("\n") { c ->
            val f = resolveAt(c, states[c.id].orEmpty(), chapter.number).joinToString("；") { "${it.k}：${it.v}" }
            "· ${c.name}" + (if (c.aliases.isNotBlank()) "（又名 ${c.aliases}）" else "") + (if (c.role.isNotBlank()) " ${c.role}" else "") + if (f.isNotBlank()) "：$f" else ""
        })
        db.plan().volumeOnce(chapter.volumeId)?.outline?.takeIf { it.isNotBlank() }?.let { parts += CtxPart("volume", "当前卷纲", 4.0, it) }
        bp?.synopsis?.takeIf { it.isNotBlank() }?.let { parts += CtxPart("synopsis", "总纲", 5.0, it) }
        val prev = db.chapters().previous(bookId, chapter.number, 3)
        if (prev.isNotEmpty()) parts += CtxPart("tail", "前几章结尾", 6.0, prev.reversed().joinToString("\n\n") { "（第 ${it.number} 章 ${it.title} 结尾）\n" + it.content.takeLast(if (it == prev.first()) 800 else 400) })
        // 分层摘要：最近 10 章用章摘要；之前的卷用卷摘要
        val sums = db.context().summaries(bookId).associateBy { it.chapterId }
        val all = db.chapters().chaptersOnce(bookId).filter { it.number < chapter.number - prev.size }
        val recent = all.filter { it.number >= chapter.number - 12 }
        val vols = db.chapters().volumesOnce(bookId)
        val vsum = db.context().volumeSummaries(bookId).associateBy { it.volumeId }
        val layered = buildString {
            val curVolIdx = vols.indexOfFirst { it.id == chapter.volumeId }
            vols.forEachIndexed { i, v ->
                if (i < curVolIdx) vsum[v.id]?.text?.takeIf { it.isNotBlank() }?.let { append("【${v.title}】").append(it).append('\n') }
            }
            val oldInCur = all.filter { it.volumeId == chapter.volumeId && it !in recent }
            oldInCur.mapNotNull { sums[it.id] }.takeIf { it.isNotEmpty() }?.let { l -> append("【本卷早前】").append(l.joinToString(" ") { it.text.take(80) }).append('\n') }
            recent.forEach { c -> sums[c.id]?.let { append("第 ${c.number} 章：").append(it.text).append('\n') } }
        }
        if (layered.isNotBlank()) parts += CtxPart("summary", "前情摘要", 7.0, layered.trim())
        ContextHooks.extra.forEach { runCatching { parts += it(bookId, chapter, text) } }
        runCatching { refresh(bookId, except = chapterId) }
        val hits = retrieve(bookId, chapter, keywords(used.filter { it.type == "character" }.map { it.name }, cp?.event.orEmpty()), before.takeLast(600) + (cp?.event ?: ""))
        if (hits.isNotEmpty()) parts += CtxPart("retrieved", "相关旧片段", 8.0, hits.joinToString("\n\n") { "（第 ${it.number} 章）${it.text}" })
        if (before.isNotBlank()) parts += CtxPart("cursor", "本章已写内容（末尾）", 2.5, before.takeLast(3000))
        val system = Prompts.BASE + "\n" + PromptHooks.style(bookId, p)
        val budget = (p.contextBudget - AiService.estimateTokens(system.length) - 1500).coerceAtLeast(1500)
        val fitted = fitBudget(parts, budget)
        last = CtxSnapshot(chapterId, system, fitted, budget)
        // 拼装顺序：从全局到局部，光标前文本最后
        val order = listOf("synopsis", "volume", "summary", "retrieved", "lore", "tail", "plan", "cursor")
        return fitted.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) 4 else i } }.joinToString("\n\n") { "【${it.label}】\n${it.text}" }
    }

    /** 非正文任务的上下文（F2，ai-features §0.2）：按需取各部分，整体按 token 预算裁剪。 */
    sealed interface Part {
        data object Synopsis : Part
        data object VolumeOutline : Part
        data object ChapterPlan : Part
        data class PrevSummaries(val n: Int = 5) : Part
        data object Characters : Part
        data class Lore(val query: String = "") : Part
        data object OpenForeshadows : Part
        data object StyleCard : Part
    }

    suspend fun brief(bookId: Long, chapterId: Long? = null, parts: Set<Part>, budget: Int? = null): String {
        val p = AppGraph.prefs.flow.first()
        val chapter = chapterId?.let { db.chapters().get(it) }
        val out = mutableListOf<CtxPart>()
        for (part in parts) {
            runCatching {
                when (part) {
                    is Part.Synopsis -> db.plan().bookOnce(bookId)?.synopsis?.takeIf { it.isNotBlank() }
                        ?.let { out += CtxPart("synopsis", "总纲", 2.0, it) }
                    is Part.VolumeOutline -> chapter?.let { db.plan().volumeOnce(it.volumeId)?.outline?.takeIf { s -> s.isNotBlank() } }
                        ?.let { out += CtxPart("volume", "当前卷纲", 3.0, it) }
                    is Part.ChapterPlan -> chapterId?.let { db.plan().chapterOnce(it) }?.takeIf { !it.isEmpty() }
                        ?.let { out += CtxPart("plan", "当前章纲", 2.0, it.asPrompt()) }
                    is Part.PrevSummaries -> {
                        val chapterNumbers = db.chapters().chaptersOnce(bookId).filter { chapter == null || it.number < chapter.number }.associate { it.id to it.number }
                        val sums = db.context().summaries(bookId).filter { it.chapterId in chapterNumbers }.sortedByDescending { chapterNumbers[it.chapterId] }
                        val n = part.n
                        val text = sums.take(n).sortedBy { chapterNumbers[it.chapterId] }.map { s ->
                            val no = chapterNumbers[s.chapterId]!!
                            "第 $no 章：${s.text}"
                        }.joinToString("\n")
                        if (text.isNotBlank()) out += CtxPart("summary", "前情摘要", 4.0, text)
                    }
                    is Part.Characters -> {
                        val cards = db.lore().cardsOnce(bookId).filter { !it.pending && it.type == "character" }
                        val states = db.lore().statesForBook(bookId).groupBy { it.cardId }
                        val visible = cards.filter { it.inject != "never" && (chapter == null || it.firstChapter == null || it.firstChapter <= chapter.number) }
                        if (visible.isNotEmpty()) out += CtxPart("lore", "人物设定（当前章时）", 3.0, visible.joinToString("\n") { c -> "· ${c.name}（${c.role}）：" + resolveAt(c, states[c.id].orEmpty(), chapter?.number ?: Int.MAX_VALUE).joinToString("；") { "${it.k}=${it.v}" } })
                    }
                    is Part.Lore -> {
                        val all = db.lore().cardsOnce(bookId).filter { !it.pending && it.inject != "never" && (chapter == null || it.firstChapter == null || it.firstChapter <= chapter.number) }
                        val matched = if (part.query.isBlank()) all else {
                            val terms = part.query.split(Regex("\\s+")).filter { it.isNotBlank() }
                            all.filter { c -> terms.any { t -> c.allNames().any { n -> n.contains(t) || t.contains(n) } } || c.name.contains(part.query) }.ifEmpty { all.filter { it.inject == "always" } }
                        }
                        val states = db.lore().statesForBook(bookId).groupBy { it.cardId }
                        if (matched.isNotEmpty()) out += CtxPart("lore", "设定卡（当前章时）", 3.5, matched.take(30).joinToString("\n") { c -> "· ${c.name}（${c.type}）：" + resolveAt(c, states[c.id].orEmpty(), chapter?.number ?: Int.MAX_VALUE).joinToString("；") { "${it.k}=${it.v}" } })
                    }
                    is Part.OpenForeshadows -> {
                        val open = db.checks().foreshadowsOnce(bookId).filter { !it.pending && it.status == "open" && (chapter == null || it.plantNumber < chapter.number) }
                        if (open.isNotEmpty()) out += CtxPart("foreshadow", "未回收伏笔", 5.0, open.take(10).joinToString("\n") { "· ${it.content}（第 ${it.plantNumber} 章埋下${it.payoff?.let { "，计划第 $it 章回收" } ?: ""}）" })
                    }
                    is Part.StyleCard -> PromptHooks.style(bookId, p).takeIf { it.isNotBlank() }
                        ?.let { out += CtxPart("style", "文风要求", 6.0, it) }
                }
            }
        }
        val b = (budget ?: (p.contextBudget / 2).coerceAtLeast(4000)) - 300
        return fitBudget(out, b).sortedBy { it.priority }.joinToString("\n\n") { "【${it.label}】\n${it.text}" }
    }

    /**
     * A13：生成 3 个章名候选并写进 `chapter_plans.titleIdeas`。
     * 两处调用：完成本章时章名为空自动跑；重命名对话框的 ✨ 手动再来一批。任务名 `title`，走自动任务模型。
     */
    suspend fun titleIdeas(bookId: Long, chapterId: Long): List<String> {
        val c = db.chapters().get(chapterId) ?: return emptyList()
        val summary = db.context().summaryOnce(chapterId)?.text.orEmpty().ifBlank { c.content.takeLast(800) }
        if (summary.isBlank()) return emptyList()
        val recent = db.chapters().chaptersOnce(bookId).filter { it.title.isNotBlank() }.sortedByDescending { it.number }.take(10).joinToString("、") { it.title }
        val titleUser = buildString {
            append("【本章摘要】").append(summary)
            append("\n【本书已有章名风格】").append(recent)
            append("\n给出 3 个章名：每个 ≤8 字，贴合本章核心事件，不剧透关键反转，风格与已有章名一致，彼此差异明显。\n")
            append(com.lingwrite.app.data.ai.PromptContract.JSON_ARRAY).append('\n')
            append("格式示例：[\"擂台余波\",\"断剑认主\",\"夜半来客\"]")
        }
        val raw = AppGraph.ai.complete("title", "你是网文编辑，擅长起章名。", titleUser, bookId, chapterId, 200)
            .getOrNull() ?: return emptyList()
        val titles = (com.lingwrite.app.data.ai.JsonReply.parse(raw) as? com.lingwrite.app.data.ai.JsonReply.Result.Ok)
            ?.json?.let { j -> runCatching { (j as kotlinx.serialization.json.JsonArray).map { e -> e.toString().trim('"') } }.getOrNull() }.orEmpty()
        if (titles.isEmpty()) return emptyList()
        val plan = db.plan().chapterOnce(chapterId) ?: com.lingwrite.app.data.db.ChapterPlanEntity(chapterId, c.bookId)
        db.plan().upsertChapter(plan.copy(titleIdeas = encodeList(titles.take(3))))
        return titles.take(3)
    }

    /** 章完成后：重建索引 + 生成章摘要（作者确认过的不覆盖）+ 每 5 章汇总一次卷摘要。 */
    suspend fun afterChapterDone(chapterId: Long) {
        val c = db.chapters().get(chapterId) ?: return
        reindex(c)
        if (c.content.isBlank()) return
        val existing = db.context().summaryOnce(chapterId)
        if (existing?.confirmed != true) {
            AppGraph.ai.complete("summary", "你是小说编辑，擅长写准确、信息密度高的章节摘要。",
                "用 200 字以内概括这一章，按顺序覆盖：①发生了什么（只写关键事件）②人物关系或状态有什么变化 ③留下了什么悬念。不要评价文笔、不要分点、不要复制大段原文，只输出摘要正文。\n\n${c.content.take(15000)}", c.bookId, c.id, 500)
                .onSuccess { generated -> db.withTransaction {
                    if (db.context().summaryOnce(chapterId) == existing && db.chapters().get(chapterId)?.content == c.content)
                        db.context().upsertSummary(SummaryEntity(chapterId, c.bookId, generated.trim()))
                } }
        }
        // A13：章名为空时顺带生成 3 个标题候选，存进 chapter_plans.titleIdeas
        if (c.title.isBlank()) titleIdeas(c.bookId, chapterId)
        val inVol = db.chapters().chaptersOnce(c.bookId).filter { it.volumeId == c.volumeId }
        val sums = db.context().summaries(c.bookId).associateBy { it.chapterId }
        val have = inVol.mapNotNull { sums[it.id] }
        val vs = db.context().volumeSummary(c.volumeId)
        if (have.size >= 3 && have.size - (vs?.chapters ?: 0) >= 5 && vs?.confirmed != true) {
            AppGraph.ai.complete("summary", "你是小说编辑。",
                "把这一卷的章节摘要汇总成 300 字以内的卷摘要：主线进展、重要人物变化、未解决的悬念；按时间顺序写，不要分点、不要评价写作，只输出摘要正文。\n\n" + have.joinToString("\n") { it.text }, c.bookId, null, 700)
                .onSuccess { generated -> db.withTransaction {
                    if (db.context().volumeSummary(c.volumeId) == vs)
                        db.context().upsertVolumeSummary(VolumeSummaryEntity(c.volumeId, c.bookId, generated.trim(), chapters = have.size))
                } }
        }
    }
}
