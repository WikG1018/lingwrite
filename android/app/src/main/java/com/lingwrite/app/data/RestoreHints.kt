// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 回收站恢复位置：删章时记录左右邻居章节 id，恢复时据此插回原位。
 *
 * 为什么不加数据库列：任何 schema 变更都要 DB 版本 +1 + 迁移 + 新 schema JSON，代价和风险都更高；
 * 这里用 SharedPreferences 持久化，未初始化时退化为内存（单元测试可无 Context 直接跑）。
 * 升级前就已删除的章节没有锚点，[BookRepository.restoreChapter] 会回退到旧的章号推断。
 */
object RestoreHints {
    private const val FILE = "chapter_restore_hints"
    private var prefs: SharedPreferences? = null
    private val mem = HashMap<Long, Pair<Long?, Long?>>()

    fun init(context: Context) { prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private fun key(id: Long) = "h_$id"

    fun put(chapterId: Long, prev: Long?, next: Long?) {
        mem[chapterId] = prev to next
        prefs?.edit()?.putString(key(chapterId), "${prev ?: 0L},${next ?: 0L}")?.apply()
    }

    fun get(chapterId: Long): Pair<Long?, Long?>? {
        mem[chapterId]?.let { return it }
        val raw = prefs?.getString(key(chapterId), null) ?: return null
        val parts = raw.split(',')
        val pair = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it != 0L } to
            parts.getOrNull(1)?.toLongOrNull()?.takeIf { it != 0L }
        mem[chapterId] = pair
        return pair
    }

    fun remove(chapterId: Long) {
        mem.remove(chapterId)
        prefs?.edit()?.remove(key(chapterId))?.apply()
    }

    fun clear() {
        mem.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
