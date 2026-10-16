// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A12 拆文：一章的结构摘要（核心事件、钩子、爽点、章尾悬念、冲突强度、抽象结构）。 */
@Serializable
data class BreakdownChapter(
    val n: Int,
    val event: String = "",
    val hook: String = "",
    val cool: String = "",
    val cliff: String = "",
    val intensity: Int = 3,
    val pattern: String = "",
)

/** A12 拆文：抽象出的结构模板（不含原文人名与设定）。 */
@Serializable
data class BreakdownTemplate(val chapter: Int, val pattern: String)

/** A12 拆文的一次完整分析结果。 */
data class BreakdownResult(
    val name: String,
    val chapters: List<BreakdownChapter>,
    val template: List<BreakdownTemplate>,
    val notes: List<String>,
) {
    val curve: List<Int> get() = chapters.map { it.intensity }
}

object BreakdownPrompts {
    const val SYS = "你是网文结构分析师。只分析结构和节奏，输出时不得复述原文句子（引用≤10字），不得保留原作人名地名，用“主角/对手/师门”等角色代称。严格只输出 JSON。"

    fun chapter(n: Int, text: String): String =
        "【第 $n 章】${text.take(6000)}\n输出：{\"event\":\"核心事件≤40字\",\"hook\":\"钩子≤20字\",\"cool\":\"爽点≤20字\",\"cliff\":\"章尾悬念≤20字\",\"intensity\":1-5,\"pattern\":\"抽象结构≤40字，用主角/对手等代称\"}"

    fun summary(chapters: List<BreakdownChapter>): String =
        "【各章分析】" + chapters.joinToString("\n") { "第 ${it.n} 章：${it.event}｜${it.pattern}" } +
            "\n输出：{\"template\":[{\"chapter\":1,\"pattern\":\"\"}],\"notes\":[\"节奏要点≤30字\"]}"
}

object Breakdown {
    /** 分章分析（原文只在内存里），然后汇总成模板。 */
    suspend fun analyze(name: String, texts: List<String>, bannedNames: List<String> = emptyList()): Result<BreakdownResult> = runCatching {
        val chapters = texts.mapIndexed { i, t ->
            val raw = AppGraph.ai.complete("outline", BreakdownPrompts.SYS, BreakdownPrompts.chapter(i + 1, t), null, null, 800).getOrThrow()
            val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
                ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
            BreakdownChapter(
                n = i + 1,
                event = clean(o.str("event"), bannedNames),
                hook = clean(o.str("hook"), bannedNames),
                cool = clean(o.str("cool"), bannedNames),
                cliff = clean(o.str("cliff"), bannedNames),
                intensity = o.str("intensity").toIntOrNull()?.coerceIn(1, 5) ?: 3,
                pattern = clean(o.str("pattern"), bannedNames),
            )
        }
        val raw = AppGraph.ai.complete("outline", BreakdownPrompts.SYS, BreakdownPrompts.summary(chapters), null, null, 900).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
        val template = (o["template"]?.jsonArray ?: emptyList()).mapIndexed { i, e ->
            val t = e.jsonObject
            BreakdownTemplate(t.str("chapter").toIntOrNull() ?: (i + 1), clean(t.str("pattern"), bannedNames))
        }
        val notes = (o["notes"]?.jsonArray ?: emptyList()).map { clean(it.jsonPrimitive.content, bannedNames) }
        BreakdownResult(name = name, chapters = chapters, template = template, notes = notes)
    }

    /** 把输出里残留的原作人名/地名换成代称。 */
    fun clean(text: String, banned: List<String>): String {
        var out = text
        banned.filter { it.isNotBlank() }.forEachIndexed { i, n ->
            out = out.replace(n, if (i == 0) "主角" else if (i == 1) "对手" else "配角${i - 1}")
        }
        return out
    }

    /**
     * 从粘贴的样章里粗略抽取疑似人名/专名（纯本地规则，不联网、不落库），用于抹除。
     * 规则：
     * 1. 说话/动作标记前的人名（“沈砚说”“林晚皱眉”），这类最可信；
     * 2. 常见姓氏开头、在全文出现 ≥5 次的 2–3 字串（兜底，覆盖只在叙述里出现的名字）。
     * 这是启发式：宁可多抹一点，也不漏掉原作人名。
     */
    fun extractNames(text: String, limit: Int = 40): List<String> {
        val score = HashMap<String, Int>()
        val speech = Regex("([\\u4e00-\\u9fa5]{2,3})(?:说|道|问|答|笑道|沉声|冷哼|开口|点头|摇头|皱眉|起身|转身|看向|抬头|低头|喊|叫)")
        speech.findAll(text).forEach { m ->
            val n = m.groupValues[1]
            if (n.first() in SURNAMES) score[n] = (score[n] ?: 0) + 10
        }
        // 兜底：姓氏开头的 2 字 / 3 字滑窗计数（真名重复出现，随机三字组通常只出现一两次）
        text.forEachIndexed { i, ch ->
            if (ch !in SURNAMES) return@forEachIndexed
            listOf(2, 3).forEach { len ->
                if (i + len <= text.length) {
                    val n = text.substring(i, i + len)
                    if (n.all { it in '\u4e00'..'\u9fa5' }) score[n] = (score[n] ?: 0) + 1
                }
            }
        }
        return score.entries
            .filter { it.value >= 5 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
    }

    /** 常见姓氏（本地规则用，不求完整）。 */
    private const val SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜谢邹喻柏窦章云苏潘葛范彭郎鲁韦昌马苗凤花方俞任袁柳唐罗薛伍余米贝姚孟顾尹江钟卢丁邓杜叶程傅萧汪戴宋熊纪舒屈项祝董梁乔石崔贾路温段雷黎史龙贺倪汤滕殷郝邬安常乐于时皮齐康元卜平黄和穆"

    fun parseChapters(json: String): List<BreakdownChapter> =
        runCatching { LingJson.decodeFromString(ListSerializer(BreakdownChapter.serializer()), json) }.getOrDefault(emptyList())

    fun parseTemplate(json: String): List<BreakdownTemplate> =
        runCatching { LingJson.decodeFromString(ListSerializer(BreakdownTemplate.serializer()), json) }.getOrDefault(emptyList())

    private val stringList = ListSerializer(String.serializer())

    fun parseNotes(json: String): List<String> =
        runCatching { LingJson.decodeFromString(stringList, json) }.getOrDefault(emptyList())

    /** 持久化（只存分析结果）。 */
    suspend fun save(r: BreakdownResult, bookId: Long?): Long =
        AppGraph.db.breakdowns().insert(com.lingwrite.app.data.db.BreakdownEntity(
            bookId = bookId, name = r.name,
            chapters = LingJson.encodeToString(ListSerializer(BreakdownChapter.serializer()), r.chapters),
            curve = LingJson.encodeToString(stringList, r.curve.map { it.toString() }),
            template = LingJson.encodeToString(ListSerializer(BreakdownTemplate.serializer()), r.template),
            notes = LingJson.encodeToString(stringList, r.notes),
        ))

    /** 读回一份已保存的分析（查看/套用模板用）。 */
    suspend fun load(id: Long): BreakdownResult? {
        val e = AppGraph.db.breakdowns().get(id) ?: return null
        return BreakdownResult(name = e.name, chapters = parseChapters(e.chapters), template = parseTemplate(e.template), notes = parseNotes(e.notes))
    }

    /**
     * 套用到本书章纲（ai-features §12 步骤 3）：把模板按顺序填进还没有章纲的章节，
     * 已有章纲的章节不动，返回实际写入的章数。写入的 event 标 fromAi，作者可再改。
     */
    suspend fun applyToOutline(bookId: Long, template: List<BreakdownTemplate>): Int {
        if (template.isEmpty()) return 0
        val db = AppGraph.db
        val chs = db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        val planned = db.plan().chaptersOnce(bookId).associateBy { it.chapterId }
        var n = 0
        chs.forEach { ch ->
            if (n >= template.size) return@forEach
            val p = planned[ch.id]
            if (p == null || p.isEmpty()) {
                db.plan().upsertChapter((p ?: com.lingwrite.app.data.db.ChapterPlanEntity(ch.id, bookId)).copy(event = template[n].pattern, fromAi = true))
                n++
            }
        }
        return n
    }
}
