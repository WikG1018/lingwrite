// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.ai.PromptContract
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
    val SYS = "你是资深网文策划编辑，熟悉起点、番茄、晋江等平台的读者口味。给出的都是候选，作者会修改。\n" + PromptContract.JSON_ONLY
    fun pitches(idea: String) = buildString {
        append("把这句灵感扩展成 3 个方向明显不同的故事梗概：题材、主角处境、核心冲突都要有差异；logline 写清主角想要什么、被什么阻挡，不要写设定百科。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append("格式示例：[{\"title\":\"断剑问天\",\"logline\":\"断剑少年为查师父死因混入宗门，却发现凶手就在身边\"}] 共 3 个。\n")
        append("灵感：").append(idea)
    }
    fun ctx(w: WizardData) = buildString {
        append("【梗概】").append(w.premise.ifBlank { w.idea }).append('\n')
        if (w.genre.isNotBlank()) append("【题材】").append(w.genre).append('\n')
        if (w.reader.isNotBlank()) append("【目标读者】").append(w.reader).append('\n')
        if (w.chars.any { it.take }) append("【人物】").append(w.chars.filter { it.take }.joinToString("；") { "${it.name}（${it.role}）${it.personality}" }).append('\n')
        if (w.world.any { it.take }) append("【世界观】").append(w.world.filter { it.take }.joinToString("；") { "${it.name}：${it.desc}" }).append('\n')
        if (w.synopsis.isNotBlank()) append("【总纲】").append(w.synopsis).append('\n')
    }
    fun chars(w: WizardData) = ctx(w) + buildString {
        append("\n为这个故事设计主角和 3–5 个核心人物。人物之间要有关系张力和利益冲突；姓名贴合题材，不要用常见网文套名。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append("格式：[{\"name\":\"姓名\",\"role\":\"主角/女主/反派/导师…\",\"aliases\":\"别名，可空\",\"identity\":\"身份\",\"personality\":\"性格（用行为特征，不要形容词堆砌）\",\"motive\":\"动机\",\"cheat\":\"金手指或特殊能力，没有则空\"}]")
        append("\n示例：[{\"name\":\"林砚\",\"role\":\"主角\",\"aliases\":\"\",\"identity\":\"青云宗外门弟子\",\"personality\":\"寡言，遇事先算退路\",\"motive\":\"查清师父死因\",\"cheat\":\"能听见断剑里的残魂说话\"}]")
    }
    fun world(w: WizardData) = ctx(w) + buildString {
        append("\n设计世界观，数量必须严格满足：1 条力量体系（type=power）、2–3 个势力（faction）、2–3 个关键地点（place）。力量体系要写清等级与代价。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append("格式：[{\"type\":\"power|faction|place\",\"name\":\"名称\",\"desc\":\"80 字以内\"}]")
        append("\n示例：[{\"type\":\"power\",\"name\":\"炼气九境\",\"desc\":\"每境需以精血温养灵脉，越往上越难，第九境要过一次心魔劫\"}]")
    }
    fun synopsis(w: WizardData) = ctx(w) + buildString {
        append("\n写全书总纲（300 字以内：主线、3–5 个阶段目标、结局走向）和第一卷卷纲（200 字以内）。卷名只写名字本身，不要带“第几卷”。\n")
        append(PromptContract.JSON_ONLY).append('\n')
        append("格式：{\"synopsis\":\"…\",\"volumeTitle\":\"山门初入\",\"volumeOutline\":\"…\"}")
        append("\n要求：总纲要能看出主线冲突和主角目标，不要写世界观说明书。")
    }
    fun titles(w: WizardData) = ctx(w) + buildString {
        append("\n给出 5 个书名（符合平台风格，好记、有卖点、彼此差异明显）和 3 版作品简介（每版 150 字以内，第一句就要抓人）。\n")
        append(PromptContract.JSON_ONLY).append('\n')
        append("格式：{\"titles\":[\"…5 个\"],\"blurbs\":[\"…3 版\"]}")
        append("\n示例书名风格：《断剑问天》《我在青云宗当扫地人》；简介不要从“天地玄黄”这类背景介绍开头。")
    }
    fun chapters(w: WizardData, golden: List<String>) = ctx(w) + buildString {
        append("\n【第一卷卷纲】").append(w.volumeOutline).append("\n\n按“黄金三章”要求写前三章章纲（必须正好 3 条，章与章之间要递进）。需满足：").append(golden.joinToString("；")).append("。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append("格式：[{\"title\":\"章名（≤8字，不剧透）\",\"event\":\"核心事件（谁做了什么、局面怎么变）\",\"hook\":\"开篇钩子\",\"cliff\":\"章尾悬念\"}]")
    }
    fun style(sample: String) = "下面是作者自己写的样章。提取可执行的文风特征：句长分布、对白比例、叙述视角、常用句式、用词偏好、标点习惯。写成一份 200 字以内的“文风卡”，用第二人称指令口吻（例如“多用短句，对话前不加‘他说’”），要具体可执行，不要“文笔细腻”这类空评价。只输出文风卡正文，不要标题、不要解释。\n\n【样章】\n${sample.take(15000)}"
}
