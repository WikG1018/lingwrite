// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/** 进程内的一次性跳转目标：检查结果点「去原文」时用，避免改现有导航路由。 */
object EditorJump {
    private var target: Pair<Long, Int>? = null

    @Synchronized
    fun put(chapterId: Long, offset: Int) { target = chapterId to offset.coerceAtLeast(0) }

    /** 只在章节匹配时消费；不匹配会保留，避免跳到别的章时误用。 */
    @Synchronized
    fun consume(chapterId: Long): Int? {
        val t = target ?: return null
        if (t.first != chapterId) return null
        target = null
        return t.second
    }

    @Synchronized
    fun clear() { target = null }
}
