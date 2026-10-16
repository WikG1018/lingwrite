// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import kotlinx.serialization.Serializable

@Serializable data class Pitch(val title: String = "", val logline: String = "")
@Serializable data class CharDraft(val name: String = "", val role: String = "", val aliases: String = "", val identity: String = "", val personality: String = "", val motive: String = "", val cheat: String = "", val take: Boolean = true)
@Serializable data class WorldDraft(val type: String = "power", val name: String = "", val desc: String = "", val take: Boolean = true)
@Serializable data class ChapterDraft(val title: String = "", val event: String = "", val hook: String = "", val cliff: String = "")

/** 向导草稿：每步的输入与 AI 建议（可微调），随时保存，可回头修改。 */
@Serializable
data class WizardData(
    val idea: String = "",
    val pitches: List<Pitch> = emptyList(),
    val premise: String = "",
    val genre: String = "",
    val templates: List<String> = listOf("golden3", "hook", "cliff"),
    val reader: String = "",
    val style: String = "",
    val chars: List<CharDraft> = emptyList(),
    val world: List<WorldDraft> = emptyList(),
    val synopsis: String = "",
    val volumeTitle: String = "",
    val volumeOutline: String = "",
    val titles: List<String> = emptyList(),
    val blurbs: List<String> = emptyList(),
    val title: String = "",
    val blurb: String = "",
    val chapters: List<ChapterDraft> = emptyList(),
) {
    companion object {
        fun parse(s: String) = runCatching { LingJson.decodeFromString(serializer(), s) }.getOrNull() ?: WizardData()
    }
    fun encode() = LingJson.encodeToString(serializer(), this)
}

object WizardPrompts {
    const val SYS = "你是资深网文策划编辑，熟悉起点、番茄、晋江等平台的读者口味。只输出要求的 JSON，不要解释。"
    fun pitches(idea: String) = "把这句灵感扩展成 3 个方向明显不同的故事梗概（题材、主角处境、核心冲突要有差异）。严格只输出 JSON 数组：[{\"title\":\"暂定名\",\"logline\":\"120 字以内梗概\"}]\n灵感：$idea"
    fun ctx(w: WizardData) = buildString {
        append("【梗概】").append(w.premise.ifBlank { w.idea }).append('\n')
        if (w.genre.isNotBlank()) append("【题材】").append(w.genre).append('\n')
        if (w.reader.isNotBlank()) append("【目标读者】").append(w.reader).append('\n')
        if (w.chars.any { it.take }) append("【人物】").append(w.chars.filter { it.take }.joinToString("；") { "${it.name}（${it.role}）${it.personality}" }).append('\n')
        if (w.world.any { it.take }) append("【世界观】").append(w.world.filter { it.take }.joinToString("；") { "${it.name}：${it.desc}" }).append('\n')
        if (w.synopsis.isNotBlank()) append("【总纲】").append(w.synopsis).append('\n')
    }
    fun chars(w: WizardData) = ctx(w) + "\n为这个故事设计主角和 3–5 个核心人物。严格只输出 JSON 数组：[{\"name\":\"姓名\",\"role\":\"主角/女主/反派/导师…\",\"aliases\":\"别名，可空\",\"identity\":\"身份\",\"personality\":\"性格\",\"motive\":\"动机\",\"cheat\":\"金手指或特殊能力，没有则空\"}]"
    fun world(w: WizardData) = ctx(w) + "\n设计世界观：1 套力量体系（type=power）、2–3 个势力（type=faction）、2–3 个关键地点（type=place）。严格只输出 JSON 数组：[{\"type\":\"power|faction|place\",\"name\":\"名称\",\"desc\":\"80 字以内\"}]"
    fun synopsis(w: WizardData) = ctx(w) + "\n写全书总纲（300 字以内，写清主线、阶段目标和结局走向）和第一卷卷纲（200 字以内）。卷名只写名字本身，不要带“第几卷”。严格只输出 JSON：{\"synopsis\":\"…\",\"volumeTitle\":\"卷名，不带编号\",\"volumeOutline\":\"…\"}"
    fun titles(w: WizardData) = ctx(w) + "\n给出 5 个书名（符合平台风格，好记、有卖点）和 3 版作品简介（每版 150 字以内，开头抓人）。严格只输出 JSON：{\"titles\":[\"…\"],\"blurbs\":[\"…\"]}"
    fun chapters(w: WizardData, golden: List<String>) = ctx(w) + "\n【第一卷卷纲】${w.volumeOutline}\n\n按“黄金三章”要求写前三章章纲，需满足：${golden.joinToString("；")}。严格只输出 JSON 数组：[{\"title\":\"章名\",\"event\":\"核心事件\",\"hook\":\"开篇钩子\",\"cliff\":\"章尾悬念\"}]"
    fun style(sample: String) = "下面是作者自己写的样章。提取文风：用词偏好、句长分布、对白比例、常用句式、叙述视角和语气。写成一份 200 字以内的“文风卡”，用第二人称指令口吻（例如“多用短句……”），可以直接附在写作提示词里。只输出文风卡正文。\n\n【样章】\n${sample.take(15000)}"
}
