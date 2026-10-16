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

/** 取出模型回复里的第一个 JSON 数组 / 对象（容忍前后多余文字和 ```json 包裹）。 */
fun extractJson(raw: String, array: Boolean = true): JsonElement? {
    val (o, c) = if (array) '[' to ']' else '{' to '}'
    val a = raw.indexOf(o); val b = raw.lastIndexOf(c)
    if (a < 0 || b <= a) return null
    return runCatching { LingJson.parseToJsonElement(raw.substring(a, b + 1)) }.getOrNull()
}

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
    const val SYS = "你是资深网文策划编辑。只输出要求的内容，不要解释。"
    fun volumes(synopsis: String, n: Int) = "根据下面的总纲，拆出 $n 卷的卷纲。严格只输出 JSON 数组：[{\"title\":\"卷名\",\"outline\":\"150 字以内卷纲\"}]\n\n【总纲】\n$synopsis"
    fun chapters(synopsis: String, volume: String, prev: String, n: Int, fields: List<String>, hint: String) = buildString {
        append("根据卷纲拆出接下来 $n 章的章纲。每章写清核心事件、开篇钩子、章尾悬念")
        if (fields.isNotEmpty()) append("，以及：").append(fields.joinToString("、"))
        append("。严格只输出 JSON 数组：[{\"title\":\"章名\",\"event\":\"…\",\"hook\":\"…\",\"cliff\":\"…\"")
        fields.forEach { append(",\"").append(it).append("\":\"…\"") }
        append("}]\n")
        if (hint.isNotBlank()) append("【写法要求】").append(hint).append('\n')
        if (synopsis.isNotBlank()) append("\n【总纲】\n").append(synopsis).append('\n')
        append("\n【卷纲】\n").append(volume).append('\n')
        if (prev.isNotBlank()) append("\n【已有章纲（接着往后拆）】\n").append(prev).append('\n')
    }
    fun landed(plan: String, text: String) = "对照章纲检查正文是否写到。严格只输出 JSON：{\"event\":true/false,\"hook\":true/false,\"cliff\":true/false,\"note\":\"若有偏离，30 字内说明\"}\n\n【章纲】\n$plan\n\n【正文】\n${text.take(12000)}"
    fun golden(items: List<String>, chapters: List<ChapterEntity>) = buildString {
        append("你在审读一部网文的前三章。逐项判断下列检查项是否满足，并引用原文作为依据（20 字以内原句）。严格只输出 JSON 数组：[{\"item\":\"检查项原文\",\"pass\":true/false,\"evidence\":\"原文依据或缺什么\",\"chapter\":章号}]\n\n【检查项】\n")
        items.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
        chapters.forEach { append("\n【第 ${it.number} 章 ${it.title}】\n").append(it.content.take(6000)).append('\n') }
    }
}
