// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import kotlinx.coroutines.flow.first

/** 两种写作模式（docs/v0.2/modes.md）：区别在于 AI 能否把文字写进正文、默认产出什么。 */
enum class WriteMode(val id: String, val label: String) {
    Assist("assist", "辅助"), Generate("generate", "生成");

    companion object {
        fun of(id: String?): WriteMode = if (id == "generate") Generate else Assist
    }
}

/** 本书设置优先，没有则用全局（MD2）。 */
suspend fun modeFor(bookId: Long): WriteMode {
    val book = AppGraph.db.books().get(bookId)?.writeMode
    return WriteMode.of(book ?: AppGraph.prefs.flow.first().writeMode)
}

/** 模式相关的集中文案。 */
object ModeCopy {
    const val ASSIST_ONE_LINER = "你写，AI 陪"
    const val GENERATE_ONE_LINER = "你定，AI 起草"
    const val ASSIST_DESC = "正文每个字出自你手；AI 给方向、提问题、指出问题，并帮你改你选中的句子"
    const val GENERATE_DESC = "你定章纲，AI 起草段落或整章，你来挑、改、定稿"
    const val ASSIST_RESULT = "AI 不会往正文里写字，给你的都是方向、问题和对你选中句子的改法。"
    const val GENERATE_RESULT = "AI 会起草正文候选，采纳前可以先改；采纳后的段落会标出来，导出时不带标记。"
    const val ASSIST_EMPTY = "从这里开始写。卡住了可以点「灵感」，AI 会给方向，不会替你写。"
    const val GENERATE_EMPTY = "先在章纲里写好本章要发生什么，再点「AI 起草」。"
    /** 首次进入编辑器的一次性提示（modes.md §3.5） */
    const val ASSIST_TIP = "辅助模式：AI 不会往正文里写字。方向和问题都会进「本章便签」，改句子只对你选中的部分生效。"
    const val GENERATE_TIP = "生成模式：AI 会起草候选，采纳前可以微调；采纳的段落会带 AI 标记，导出时可去掉。"
    const val SWITCH_WITH_CANDIDATE = "切到辅助模式后，当前未采纳的候选会存进本章便签。"
}
