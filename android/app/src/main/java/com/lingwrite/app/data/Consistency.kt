// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.ai.PromptContract
import com.lingwrite.app.data.db.*
import kotlinx.serialization.json.*

/** 伏笔状态（相对当前写到的章）。 */
enum class FsState { Overdue, Due, Open, Paid, Dropped }

fun fsState(f: ForeshadowEntity, current: Int, warnAhead: Int = 3): FsState = when {
    f.status == "paid" -> FsState.Paid
    f.status == "dropped" -> FsState.Dropped
    f.payoff != null && current > f.payoff -> FsState.Overdue
    f.payoff != null && f.payoff - current <= warnAhead -> FsState.Due
    else -> FsState.Open
}

/** 把检查结果中的引文定位回正文（找不到返回 -1）。 */
fun locate(text: String, quote: String): Int {
    if (quote.isBlank()) return -1
    text.indexOf(quote).let { if (it >= 0) return it }
    val q = quote.trim('…', '.', '“', '”', '"', ' ')
    for (len in listOf(16, 10, 6)) if (q.length >= len) text.indexOf(q.take(len)).let { if (it >= 0) return it }
    return -1
}

/** 故意为之的问题：同类且引文相近即视为同一条。 */
fun suppressed(kind: String, quote: String, intended: List<IssueEntity>) = intended.any { it.kind == kind && (it.quote == quote || (quote.length >= 6 && it.quote.contains(quote.take(6)))) }

fun kindLabel(k: String) = when (k) { "persona" -> "人设"; "power" -> "等级实力"; "time" -> "时间线"; "relation" -> "关系/生死"; else -> "其他" }

object Consistency {
    val SYS = "你是严谨的长篇小说审校编辑，专门找前后矛盾。\n" + PromptContract.JSON_ARRAY

    /** 第一步：抽取本段里人物的陈述、状态、能力和时间信息。 */
    fun extractPrompt(seg: String) = buildString {
        append("从下面这段正文中抽取与人物相关的事实陈述：言行、境界实力、生死、关系、时间、谁知道什么。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append(PromptContract.VERBATIM_QUOTE).append('\n')
        append("输出 0–12 条，每条只写一个独立事实；没有可抽的内容就输出 []。格式示例：")
        append("[{\"who\":\"林砚\",\"claim\":\"林砚此时只知道断剑是师父遗物\",\"quote\":\"林砚把断剑收回袖里\"}]\n\n")
        append("【正文】\n").append(seg)
    }

    /** 第二步：与设定、当章状态、前情比对。 */
    fun comparePrompt(facts: String, basis: String) = buildString {
        append("对照【设定与前情】，判断下面每条事实是否与之矛盾。只报告确实矛盾的：不确定、可能是人物说谎、视角受限或故意隐瞒的，不要报。\n")
        append("kind 只能是：persona（人设崩）/ power（等级实力前后不一）/ time（时间错乱、年龄、人物此时不该知道某事）/ relation（关系或生死矛盾）。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append(PromptContract.VERBATIM_QUOTE).append('\n')
        append("没有矛盾就输出 []。格式示例：[{\"kind\":\"power\",\"quote\":\"林砚不过炼气三层\",\"basis\":\"第三章已写他筑基成功\",\"explain\":\"实力描述前后矛盾\"}]\n\n")
        append("【设定与前情】\n").append(basis).append("\n\n【本章事实】\n").append(facts)
    }

    suspend fun basis(c: ChapterEntity): String {
        val db = AppGraph.db
        val cards = db.lore().cardsOnce(c.bookId).filter { !it.pending && it.inject != "never" && (it.firstChapter == null || it.firstChapter <= c.number) }
        val states = db.lore().statesForBook(c.bookId).groupBy { it.cardId }
        val mentioned = cards.filter { k -> k.inject == "always" || k.allNames().any { c.content.contains(it) } }
        val rels = relationsAt(db.lore().relationsOnce(c.bookId), c.number)
        val names = cards.associate { it.id to it.name }
        val sums = db.context().summaries(c.bookId).associateBy { it.chapterId }
        val prev = db.chapters().chaptersOnce(c.bookId).filter { it.number < c.number }.takeLast(15)
        return buildString {
            append("【设定（第 ${c.number} 章时）】\n")
            mentioned.forEach { k -> append("· ${k.name}：").append(resolveAt(k, states[k.id].orEmpty(), c.number).joinToString("；") { "${it.k}=${it.v}" + (it.changedAt?.let { a -> "（第 $a 章起）" } ?: "") }).append('\n') }
            if (rels.isNotEmpty()) append("【关系】").append(rels.joinToString("；") { "${names[it.fromId]}—${names[it.toId]}：${it.label}" }).append('\n')
            val s = prev.mapNotNull { p -> sums[p.id]?.let { "第 ${p.number} 章：${it.text}" } }
            if (s.isNotEmpty()) append("【前情摘要】\n").append(s.joinToString("\n")).append('\n')
            val hits = runCatching { ContextEngine.retrieve(c.bookId, c, mentioned.filter { it.type == "character" }.map { it.name }, c.content.take(600), 4) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) append("【相关前文】\n").append(hits.joinToString("\n") { "（第 ${it.number} 章）${it.text.take(200)}" })
        }
    }

    /** 检查一章：长章节分段抽取，再统一比对。返回新问题数，失败时抛出。 */
    suspend fun check(chapterId: Long): Int {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId) ?: return 0
        if (c.content.isBlank()) return 0
        val facts = mutableListOf<JsonElement>()
        var failedChunks = 0
        c.content.chunked(4000).forEach { seg ->
            runCatching {
                val r = AppGraph.ai.complete("extract", SYS, extractPrompt(seg), c.bookId, c.id, 2000, outputLang = false).getOrThrow()
                val arr = (JsonReply.parse(r) as? JsonReply.Result.Ok)?.json?.jsonArray
                    ?: error("事实抽取返回格式无效")
                facts.addAll(arr)
            }.onFailure { failedChunks++ }
        }
        // 任何一段抽不出来都算这次检查失败：宁可让作者重试，也不能把「没抽到」当成「没有矛盾」清空问题。
        if (failedChunks > 0) error("一致性检查未完成：有 $failedChunks 段模型输出无法解析，已保留原有问题，请重试")
        if (facts.isEmpty()) { db.checks().clearOpen(chapterId); return 0 }
        val raw = AppGraph.ai.complete("check", SYS, comparePrompt(JsonArray(facts).toString(), basis(c)), c.bookId, c.id, 2400, outputLang = false).getOrThrow()
        val arr = extractJson(raw)?.let { runCatching { it.jsonArray }.getOrNull() } ?: error("模型没有按格式返回")
        val intended = db.checks().intended(c.bookId)
        db.checks().clearOpen(chapterId)
        var n = 0
        arr.forEach { e ->
            val o = e.jsonObject
            val kind = o.str("kind").ifBlank { "other" }; val quote = o.str("quote")
            if (suppressed(kind, quote, intended)) return@forEach
            db.checks().insertIssue(IssueEntity(bookId = c.bookId, chapterId = c.id, number = c.number, kind = kind, quote = quote, start = locate(c.content, quote), basis = o.str("basis"), explain = o.str("explain")))
            n++
        }
        return n
    }

    /** AI 建议候选伏笔（待作者确认）。 */
    suspend fun suggestForeshadows(chapterId: Long) {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId) ?: return
        if (c.content.isBlank()) return
        val known = db.checks().foreshadowsOnce(c.bookId).joinToString("；") { it.content }
        val user = buildString {
            append("找出这一章里埋下、之后需要回收的伏笔：悬而未决的线索、反常细节、承诺、未揭晓的身份。不要重复已有伏笔；没有就输出 []，最多 3 条。\n")
            append(PromptContract.JSON_ARRAY).append('\n')
            append(PromptContract.VERBATIM_QUOTE).append('\n')
            append("payoffIn 是建议多少章之后回收，必须是 1–200 的整数。格式示例：")
            append("[{\"content\":\"断剑上的缺口与师父死因有关，应在主角查旧案时回收\",\"quote\":\"断剑的缺口是新的\",\"payoffIn\":12}]\n\n")
            append("【已有伏笔】").append(known).append("\n\n【第 ").append(c.number).append(" 章】\n").append(c.content.take(12000))
        }
        val raw = AppGraph.ai.complete("foreshadow", SYS, user, c.bookId, c.id, 800, outputLang = false).getOrNull() ?: return
        extractJson(raw)?.let { runCatching { it.jsonArray }.getOrNull() }?.forEach { e ->
            val o = e.jsonObject
            if (o.str("content").isBlank()) return@forEach
            val q = o.str("quote")
            db.checks().insertForeshadow(ForeshadowEntity(bookId = c.bookId, content = o.str("content"), chapterId = c.id, plantNumber = c.number, quote = q, quoteStart = locate(c.content, q),
                payoff = o.str("payoffIn").toIntOrNull()?.let { c.number + it.coerceIn(1, 200) }, pending = true))
        }
    }

    /** 上下文自动带入：临近回收章、或提到关联设定卡的伏笔。 */
    suspend fun contextParts(bookId: Long, chapter: ChapterEntity, text: String): List<CtxPart> {
        val db = AppGraph.db
        val cards = db.lore().cardsOnce(bookId).associateBy { it.id }
        val list = db.checks().foreshadowsOnce(bookId).filter { it.status == "open" && it.plantNumber < chapter.number }.filter { f ->
            (f.payoff != null && f.payoff - chapter.number in -50..3) || (f.cardId != null && cards[f.cardId]?.allNames()?.any { text.contains(it) } == true)
        }
        if (list.isEmpty()) return emptyList()
        return listOf(CtxPart("foreshadow", "相关伏笔（未回收）", 3.5, list.joinToString("\n") { f ->
            "· ${f.content}（第 ${f.plantNumber} 章埋下" + (f.payoff?.let { "，计划第 $it 章回收" } ?: "") + "）" + if (f.quote.isNotBlank()) "原文：「${f.quote}」" else ""
        }))
    }
}
