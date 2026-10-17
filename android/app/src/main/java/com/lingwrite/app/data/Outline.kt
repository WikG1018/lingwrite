// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.ChapterEntity
import com.lingwrite.app.data.db.ChapterPlanEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

/** 取出模型回复里的第一个完整 JSON 数组 / 对象（容忍代码块、前后文字、字符串内括号与截断）。 */
fun extractJson(raw: String, array: Boolean = true): JsonElement? =
    com.lingwrite.app.data.ai.JsonExtract.extract(raw, array)

fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

private val strList = ListSerializer(String.serializer())
private val strMap = MapSerializer(String.serializer(), String.serializer())
fun parseList(s: String): List<String> = runCatching { LingJson.decodeFromString(strList, s) }.getOrDefault(emptyList())
fun encodeList(l: List<String>) = LingJson.encodeToString(strList, l)
fun parseMap(s: String): Map<String, String> = runCatching { LingJson.decodeFromString(strMap, s) }.getOrDefault(emptyMap())
fun encodeMap(m: Map<String, String>) = LingJson.encodeToString(strMap, m)

val DEFAULT_GOLDEN = listOf(
    "主角在第一章出场",
    "开篇有冲突或事件，不是纯铺垫",
    "第一章前 500 字有钩子",
    "金手指或核心卖点已经露出",
    "第三章给出追读理由",
    "没有一次交代过多设定",
)

@Serializable data class GoldenResult(val item: String, val pass: Boolean, val evidence: String = "", val chapter: Int = 0)

fun parseGolden(s: String): List<GoldenResult> = runCatching { LingJson.decodeFromString(ListSerializer(GoldenResult.serializer()), s) }.getOrDefault(emptyList())
fun encodeGolden(l: List<GoldenResult>) = LingJson.encodeToString(ListSerializer(GoldenResult.serializer()), l)

/** 网文模板：给章纲加字段、写作提示，并可改写检查表。 */
data class OutlineTemplate(val id: String, val name: String, val desc: String, val fields: List<String>, val hint: String)

val OutlineTemplates = listOf(
    OutlineTemplate("golden3", "黄金三章", "前三章快速立人设、抛冲突、亮卖点，留住读者", listOf("卖点露出"), "前三章每章都要推进主冲突，第一章 500 字内出现钩子，第三章结尾给出追读理由。"),
    OutlineTemplate("hook", "开篇钩子", "每章开头 200 字内抓住读者", listOf(), "开篇钩子写成具体画面或悬念句，不要用环境描写慢热开场。"),
    OutlineTemplate("cliff", "章尾悬念", "每章结尾留一个让人想点下一章的问题", listOf("悬念类型"), "章尾悬念可以是危机、反转、未揭晓的信息或情感抉择，避免平淡收尾。"),
    OutlineTemplate("romance", "感情线推进", "感情线按阶段推进，避免长时间停滞", listOf("感情进度"), "感情线每 3–5 章要有一次可感知的推进（靠近、误会、表白、考验）。"),
)

/** 章纲完成度。 */
enum class PlanProgress(val label: String) { Unwritten("未写"), Writing("写作中"), Done("已完成") }

fun progressOf(c: ChapterEntity) = when { c.state == "done" -> PlanProgress.Done; c.wordCount > 0 -> PlanProgress.Writing; else -> PlanProgress.Unwritten }

/** 落实情况：三个固定字段在正文中是否写到（由 AI 检查写入，或为空）。 */
data class Landed(val event: Boolean?, val hook: Boolean?, val cliff: Boolean?, val note: String)

fun parseLanded(s: String): Landed? {
    if (s.isBlank()) return null
    val o = runCatching { LingJson.parseToJsonElement(s).jsonObject }.getOrNull() ?: return null
    fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
    return Landed(b("event"), b("hook"), b("cliff"), o.str("note"))
}

/** 线索中断：返回每条线索最近一次推进的章号与已中断章数（相对 [current]）。 */
data class ThreadGap(val thread: String, val chapters: List<Int>, val lastAt: Int?, val gap: Int)

fun threadGaps(threads: List<String>, plans: List<Pair<Int, ChapterPlanEntity>>, current: Int): List<ThreadGap> = threads.map { t ->
    val at = plans.filter { (_, p) -> p.threads.split(',').map(String::trim).contains(t) }.map { it.first }.sorted()
    val last = at.lastOrNull { it <= current }
    ThreadGap(t, at, last, if (last == null) current else current - last)
}

/** 章纲作为提示词片段。 */
fun ChapterPlanEntity.asPrompt(): String = buildString {
    if (event.isNotBlank()) append("核心事件：").append(event).append('\n')
    if (hook.isNotBlank()) append("开篇钩子：").append(hook).append('\n')
    if (cliff.isNotBlank()) append("章尾悬念：").append(cliff).append('\n')
    parseMap(custom).filterValues { it.isNotBlank() }.forEach { (k, v) -> append(k).append('：').append(v).append('\n') }
    if (note.isNotBlank()) append("备注：").append(note).append('\n')
}.trim()

fun ChapterPlanEntity.isEmpty() = event.isBlank() && hook.isBlank() && cliff.isBlank() && note.isBlank() && parseMap(custom).values.all { it.isBlank() }

object OutlinePrompts {
    val SYS = "你是资深网文策划编辑。只输出要求的内容，不要解释。\n" + com.lingwrite.app.data.ai.PromptContract.JSON_ONLY
    fun volumes(synopsis: String, n: Int) = buildString {
        append("根据下面的总纲，拆出 $n 卷的卷纲：卷与卷之间要递进（舞台/对手/主角实力或关系至少一项变化），卷名只写名字，不要“第几卷”。\n")
        append(com.lingwrite.app.data.ai.PromptContract.JSON_ARRAY).append('\n')
        append("格式：[{\"title\":\"卷名\",\"outline\":\"150 字以内卷纲：本卷目标、主要冲突、卷末状态\"}]，数组长度必须正好 $n。\n\n")
        append("【总纲】\n").append(synopsis)
    }
    fun chapters(synopsis: String, volume: String, prev: String, n: Int, fields: List<String>, hint: String) = buildString {
        append("根据卷纲拆出接下来 $n 章的章纲。每章写清核心事件、开篇钩子、章尾悬念")
        if (fields.isNotEmpty()) append("，以及：").append(fields.joinToString("、"))
        append("。\n")
        append(com.lingwrite.app.data.ai.PromptContract.JSON_ARRAY).append('\n')
        append("格式：[{\"title\":\"章名（≤8字）\",\"event\":\"核心事件（谁做了什么、局面怎么变）\",\"hook\":\"…\",\"cliff\":\"…\"")
        fields.forEach { append(",\"").append(it).append("\":\"…\"") }
        append("}]\n")
        if (hint.isNotBlank()) append("【写法要求】").append(hint).append('\n')
        if (synopsis.isNotBlank()) append("\n【总纲】\n").append(synopsis).append('\n')
        append("\n【卷纲】\n").append(volume).append('\n')
        if (prev.isNotBlank()) append("\n【已有章纲（接着往后拆）】\n").append(prev).append('\n')
    }
    fun landed(plan: String, text: String) = "对照章纲检查正文是否写到。\n" + com.lingwrite.app.data.ai.PromptContract.JSON_ONLY +
        "\n格式：{\"event\":true/false,\"hook\":true/false,\"cliff\":true/false,\"note\":\"若有偏离，30 字内说明；没偏离给空串\"}" +
        "\n判断标准：只看“是否写到”，不评价写得好不好；正文明确写到了才给 true。\n\n【章纲】\n$plan\n\n【正文】\n${text.take(12000)}"
    fun golden(items: List<String>, chapters: List<ChapterEntity>) = buildString {
        append("你在审读一部网文的前三章。逐项判断下列检查项是否满足：满足时引用 20 字以内原文作依据，不满足时写清缺什么。\n")
        append(com.lingwrite.app.data.ai.PromptContract.JSON_ARRAY).append('\n')
        append(com.lingwrite.app.data.ai.PromptContract.VERBATIM_QUOTE).append('\n')
        append("格式：[{\"item\":\"检查项原文（照抄下面的条目）\",\"pass\":true/false,\"evidence\":\"原文依据或缺什么\",\"chapter\":章号}]，条目数量必须与检查项一致。\n\n【检查项】\n")
        items.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
        chapters.forEach { append("\n【第 ${it.number} 章 ${it.title}】\n").append(it.content.take(6000)).append('\n') }
    }
}
