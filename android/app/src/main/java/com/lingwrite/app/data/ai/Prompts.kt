// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import com.lingwrite.app.data.AppPrefs

/** 上下文与文风的扩展点（长篇上下文、自然文风模块会替换实现）。 */
object PromptHooks {
    /** 返回放在用户消息前的“背景资料”（大纲、设定、前情等）。 */
    var context: suspend (bookId: Long, chapterId: Long, cursor: Int, text: String) -> String = { _, _, cursor, text ->
        val before = text.substring(0, cursor.coerceIn(0, text.length))
        if (before.isBlank()) "" else "【本章已写内容（末尾）】\n" + before.takeLast(1500)
    }
    /** 附加到系统指令后的文风卡。 */
    var style: suspend (bookId: Long, prefs: AppPrefs) -> String = { _, p ->
        buildString {
            if (p.stylePrompt.isNotBlank()) append("【文风要求】").append(p.stylePrompt).append('\n')
            if (p.bannedWords.isNotBlank()) append("【尽量不用这些词】").append(p.bannedWords).append('\n')
        }
    }
}

object Prompts {
    const val BASE = """你是一位资深中文网络小说编辑兼写手，正在协助作者写长篇连载。
要求：
- 只输出正文内容本身，不要解释、不要标题、不要引号包裹、不要“好的”。
- 延续作者已有的人称、时态、语气和用词习惯；对话用中文引号“”。
- 具体、有画面，动作和细节优先于形容词堆砌；避免空泛的总结句和说教。
- 不要擅自引入设定里没有的重要人物或能力；不确定时保持克制。"""

    fun lengthHint(len: String) = when (len) { "sentence" -> "一到两句"; "long" -> "三到五段，约 500 字"; else -> "一段，约 120–200 字" }

    data class Task(val id: String, val label: String, val needsSelection: Boolean, val instruction: (String, AppPrefs) -> String, val maxTokens: Int = 900)

    val continueTask = Task("continue", "续写", false, { _, p -> "紧接【本章已写内容】的最后一句往下续写${lengthHint(p.continueLength)}。不要重复已有内容。" })
    val directions = Task("directions", "给方向", false, { _, _ -> "不要写正文。基于已写内容，给出 3 个接下来可以怎么写的方向，每个一行，以“1. ”“2. ”“3. ”开头，每条 40 字以内，写清冲突或转折点。" }, 400)
    val rewrite = Task("rewrite", "改写", true, { s, _ -> "改写下面这段，保留情节和信息，换一种更有张力的写法，长度相近：\n「$s」" })
    val expand = Task("expand", "扩写", true, { s, _ -> "扩写下面这段，补充动作、环境和人物反应，扩到原来的 2 倍左右，不改变情节：\n「$s」" }, 1400)
    val polish = Task("polish", "润色", true, { s, _ -> "润色下面这段：修顺语句、删掉冗余和套话，保持作者风格，不改情节：\n「$s」" })
    val deai = Task("deai", "去 AI 腔", true, { s, _ -> "下面这段读起来有模板化的“AI 腔”（空泛形容、排比套话、总结式结尾、过度比喻等）。请改成自然、具体、口语化的网文叙述，保留情节：\n「$s」" })
    val dialog = Task("dialog", "对话", false, { _, _ -> "接着当前场景写一段人物对话（4–8 句），符合各自性格，对话之间夹少量动作描写。" })
    // MD1：描写在生成模式无选区时也常驻（modes.md §5），所以它不强制要求选区，提示词按有无选区分支。
    val describe = Task("describe", "描写", false, { s, _ ->
        if (s.isBlank()) "为当前场景补一段细致的感官描写（视觉、声音、气味、触感择二三），融入上文语境，约 120 字，只写这一段。"
        else "为下面这句/段补一段细致的感官描写（视觉、声音、气味、触感择二三），融入原文语境：\n「$s」"
    })
    val shorten = Task("shorten", "缩写", true, { s, _ -> "把下面这段压缩到一半左右，只保留关键动作和信息：\n「$s」" }, 600)
    val draftTask = Task("draft", "起草本章", false, { _, _ -> "按【本章节拍】起草正文。这是第一章，前 500 字必须出现钩子；不要大段交代背景，设定在冲突中带出。" }, 2000)

    /** 选中文字时的工具（两种模式同一套数据，顺序按 modes.md §5）。 */
    val selectionTools = listOf(rewrite, expand, polish, deai, describe, shorten)

    /**
     * 工具条实际可见的工具（MD1 回归）：辅助模式无选区只有「给方向」；生成模式无选区是
     * 续写 / 起草本章 / 对话 / 描写；有选区时按 [selectionTools]（辅助模式去掉扩写和描写）。
     * 真实路径就是这里，不再另设一份只在测试里用的清单。
     */
    fun toolbarTools(mode: String, hasSelection: Boolean): List<Task> = when {
        hasSelection && mode == "generate" -> selectionTools
        hasSelection -> listOf(polish, deai, rewrite, shorten)
        mode == "generate" -> listOf(continueTask, draftTask, dialog, describe)
        else -> listOf(directions)
    }

    /** 多候选任务（A3）：生成模式下的续写、对话、描写，以及选中后的改写、扩写。 */
    fun multiCandidateTask(id: String, mode: String, hasSelection: Boolean): Boolean =
        mode == "generate" && (id == "continue" || id == "dialog" || id == "describe" || ((id == "rewrite" || id == "expand") && hasSelection))

    /** 采纳动作的归类：给方向的结果绝不进正文（复制给作者，或存便签，由调用方决定）。 */
    fun acceptKind(taskId: String): AcceptKind = if (taskId == "directions") AcceptKind.Copy else AcceptKind.Insert

    enum class AcceptKind { Copy, Insert }

    fun annotateInstruction(items: List<Pair<String, String>>) = buildString {
        append("作者在正文里圈出了几处并写了批注。请逐条按批注修改对应原文，只改被圈出的部分。\n")
        append("严格只输出 JSON 数组，不要其他文字，格式：[{\"id\":1,\"revised\":\"修改后的原文\"}]\n\n")
        items.forEachIndexed { i, (q, note) -> append("${i + 1}. 原文：「$q」\n   批注：$note\n") }
    }
}
