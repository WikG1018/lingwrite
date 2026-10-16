// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.ChapterNoteEntity

/**
 * 采纳去向（MD1 红线，modes.md §2/§5）：辅助模式下 AI 的产出绝不自动写进正文。
 * 这里是从 EditorAi.accept / saveNote 抽出来的同一套判断与落库逻辑，UI 和测试共用，
 * 保证「辅助模式不写正文」这条红线有真实回归保护（而不是只在测试里模拟）。
 */
enum class AdoptTo {
    /** 只进本章便签（给方向；辅助模式的所有输出）。 */
    Note,

    /** 替换作者选中的文字（唯一的正文写入例外：用户主动对选区发起的改写）。 */
    ReplaceSelection,

    /** 插入到光标处（生成模式的候选）。 */
    InsertAtCursor,
}

object AiAdopt {
    /** 按任务和选区决定采纳去向。`directions`（给方向）永远只进便签。 */
    fun adoptTo(taskId: String, hasSelection: Boolean): AdoptTo = when {
        taskId == "directions" -> AdoptTo.Note
        hasSelection -> AdoptTo.ReplaceSelection
        else -> AdoptTo.InsertAtCursor
    }

    /** 辅助模式落地：写本章便签 + 把 call_logs 记成 noted。返回便签 id。 */
    suspend fun saveNote(chapterId: Long, kind: String, text: String, logId: Long? = null): Long {
        val id = AppGraph.db.notes().insert(ChapterNoteEntity(chapterId = chapterId, kind = kind, text = text))
        logId?.let { AppGraph.ai.setOutcome(it, "noted") }
        return id
    }
}
