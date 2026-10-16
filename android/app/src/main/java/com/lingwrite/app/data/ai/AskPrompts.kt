// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

/** 随手问（A1，ai-features §1）：回答不进正文，带出处标注。 */
object AskPrompts {
    const val SYS = """你是作者的写作顾问兼资料员。回答简洁具体（200 字以内），能列点就列点。
涉及本书设定时，只依据下面给出的资料，并在句末用【设定：卡名】或【第N章】标出处；资料里没有就直说“设定里没写”，不要编造。
涉及现实知识（历史、地理、官职等）时正常回答，但不确定的要说明。不要写小说正文。"""

    /** [history] 是最近几轮问答（问题 → 回答），最多 3 轮。 */
    fun user(bookTitle: String, chapterNo: Int, brief: String, textBefore: String, quote: String?, history: List<Pair<String, String>>, q: String): String = buildString {
        append("【书名】").append(bookTitle).append('\n')
        if (brief.isNotBlank()) append("【本书资料】\n").append(brief).append('\n')
        append("【当前章节】第 ").append(chapterNo).append(" 章，光标前 1500 字：\n").append(textBefore.takeLast(1500)).append('\n')
        if (!quote.isNullOrBlank()) append("【作者选中】「").append(quote).append("」\n")
        if (history.isNotEmpty()) {
            append("【历史问答】\n")
            history.takeLast(3).forEach { (hq, ha) -> append("问：").append(hq).append('\n').append("答：").append(ha.take(200)).append('\n') }
        }
        append("【问题】").append(q)
    }
}
