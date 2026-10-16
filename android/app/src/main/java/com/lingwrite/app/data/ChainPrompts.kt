// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 总纲链路提示词（C2，docs/v0.2/outline-chain.md §3）。
 * 系统提示统一：候选制、贴平台、只输出 JSON。每一步带入上游已采纳的内容（{U}）。
 */
object ChainPrompts {
    const val SYS = """你是资深网文主编，正在和作者一起从零搭一本长篇。你给出的都是候选，作者会挑选和修改。
贴合作者的题材和平台习惯，具体、有卖点，不写空话。严格只输出 JSON。"""

    /** 上游已采纳内容（{U}）。 */
    fun upstream(bp: com.lingwrite.app.data.db.BookPlanEntity, genre: String = ""): String = buildString {
        if (bp.idea.isNotBlank()) append("【创意】").append(bp.idea).append('\n')
        if (bp.logline.isNotBlank()) append("【梗概】").append(bp.logline).append('\n')
        if (genre.isNotBlank()) append("【题材】").append(genre).append('\n')
        val sp = parseList(bp.sellingPoints)
        if (sp.isNotEmpty()) append("【卖点】").append(sp.joinToString("；")).append('\n')
        if (bp.synopsis.isNotBlank()) append("【总纲】\n").append(bp.synopsis).append('\n')
    }

    // ① 创意 → 梗概（与向导第 1 步共用；outline-chain §3.①）
    fun pitches(idea: String, genre: String, platform: String): String = buildString {
        append("把这句灵感扩展成 3 个方向明显不同的故事梗概（题材、主角处境、核心冲突要有差异，如：爽文升级 / 悬疑反转 / 群像）。")
        append("严格只输出 JSON 数组：[{\"title\":\"暂定书名\",\"logline\":\"≤60字梗概\",\"hook\":\"≤20字最大卖点\"}] 共 3 个。\n\n")
        append("【创意】").append(idea).append('\n')
        if (genre.isNotBlank()) append("【题材】").append(genre).append('\n')
        if (platform.isNotBlank()) append("【目标平台】").append(platform).append('\n')
    }

    fun parsePitches(raw: String): List<Triple<String, String, String>> = runCatching {
        val arr = extractJson(raw) ?: return emptyList()
        arr.jsonArray.map { e ->
            val o = e.jsonObject
            Triple(o.str("title"), o.str("logline"), o.str("hook"))
        }
    }.getOrDefault(emptyList())

    // ② 卖点
    fun sellingPoints(u: String): String = u + "\n给这个故事提炼 3–5 条卖点（读者为什么追这本书）。严格只输出 JSON 数组：[\"卖点\"]。"

    fun parseListReply(raw: String): List<String> = runCatching {
        (extractJson(raw) ?: return emptyList()).jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())

    // ③ 总纲（分段）
    fun synopsis(u: String): String = u + "\n写全书总纲，分三段输出。严格只输出 JSON：{\"spine\":\"主线一句话≤40字\",\"stages\":[{\"name\":\"阶段名≤8字\",\"goal\":\"目标\",\"conflict\":\"主要冲突\",\"end\":\"阶段结局\"}]（3–6 个阶段）,\"ending\":\"结局走向≤120字\"}"

    fun parseSynopsis(raw: String): Triple<String, List<SynopsisFormat.Stage>, String>? = runCatching {
        val o = (extractJson(raw, array = false) ?: return null).jsonObject
        val stages = o.get("stages")?.jsonArray?.map { s ->
            val so = s.jsonObject
            SynopsisFormat.Stage(so.str("name"), so.str("goal"), so.str("conflict"), so.str("end"))
        } ?: emptyList()
        Triple(o.str("spine"), stages, o.str("ending"))
    }.getOrNull()

    // ④ 卷纲（启用并扩展现有 OutlinePrompts.volumes）
    fun volumes(u: String, n: Int): String = u + "\n【卷数】$n\n拆出 $n 卷的卷纲。卷名只写名字本身（不要写“第几卷”）。严格只输出 JSON 数组：[{\"name\":\"卷名≤8字\",\"goal\":\"本卷目标\",\"climax\":\"本卷高潮\",\"outline\":\"≤150字卷纲\",\"chapters\":预计章数}]"

    data class VolumeDraft(val name: String, val goal: String, val climax: String, val outline: String, val chapters: Int)

    fun parseVolumes(raw: String): List<VolumeDraft> = runCatching {
        (extractJson(raw) ?: return emptyList()).jsonArray.map { e ->
            val o = e.jsonObject
            VolumeDraft(
                Naming.stripVolume(o.str("name").ifBlank { o.str("title") }),
                o.str("goal"), o.str("climax"), o.str("outline").ifBlank { o.str("outline") },
                (o["chapters"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    // ⑥ 节拍（辅助 / 生成共用）
    fun beats(u: String, plan: String, style: String): String = buildString {
        append(u)
        if (style.isNotBlank()) append("【文风卡】\n").append(style).append('\n')
        append("【本章章纲】\n").append(plan).append("\n\n")
        append("为第一章生成 5–8 条场景节拍，每条 ≤30 字，第一条必须在 300 字内抛出钩子。严格只输出 JSON 数组：[\"节拍\"]。")
    }

    fun parseBeats(raw: String): List<String> = parseListReply(raw)

    /** ⑤ 章纲候选（与 ChainRunner.chapters 配套；UI 与测试共用）。[fields] 是作品级自定义字段。 */
    fun parseChapterPlans(raw: String, fields: List<String> = emptyList()): List<ChapterPlanDraft> = runCatching {
        (extractJson(raw) ?: return emptyList()).jsonArray.map { e ->
            val o = e.jsonObject
            ChapterPlanDraft(
                title = o.str("title").ifBlank { o.str("name") },
                event = o.str("event"), hook = o.str("hook"), cliff = o.str("cliff"),
                custom = fields.associateWith { o.str(it) }.filterValues { v -> v.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    // ⑥ 开篇示意（辅助模式）：3 种写法示意
    fun openings(u: String, beats: List<String>): String = buildString {
        append(u)
        append("【本章节拍】\n").append(beats.joinToString("\n")).append("\n\n")
        append("给出 3 种开篇写法示意，每种 ≤80 字，并说明这种写法的效果。严格只输出 JSON 数组：[{\"text\":\"≤80字\",\"effect\":\"≤15字效果\"}]。")
    }

    // ⑥ 起草（生成模式）：按节拍写正文
    fun draft(u: String, beats: List<String>, style: String, part: Int, total: Int, drafted: String): String = buildString {
        append("你是资深中文网络小说编辑兼写手。只输出正文内容本身，不要解释、不要标题。\n")
        if (style.isNotBlank()) append(style).append('\n')
        append(u)
        append("【本章节拍（本次写第 ").append(part).append("–").append(part).append(" 条，共 ").append(total).append(" 条）】\n")
        append(beats.getOrNull(part - 1)?.let { "第 $part 条：$it" } ?: "").append('\n')
        if (drafted.isNotBlank()) append("\n【已起草部分（结尾 800 字）】\n").append(drafted.takeLast(800)).append('\n')
        append("\n写 1200–1500 字正文，紧接已起草部分（如有）。这是第一章，前 500 字必须出现钩子；不要大段交代背景，设定在冲突中带出。")
    }
}
