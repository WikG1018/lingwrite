// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/**
 * 全 App 的卷 / 章命名规范（v0.2 起，见 docs/v0.2/naming-bug.md）：
 * 数据里只存作者起的名称，编号由显示时按位置计算，不写进标题。
 * - 卷号用中文数字（第一卷、第二卷……），有卷名时显示「第一卷 · 山门初入」。
 * - 章号用阿拉伯数字（第 118 章），界面显示两边留半角空格；导出默认不留空格。
 */
object CnNum {
    private val d = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val units = arrayOf("", "", "十", "百", "千")

    /** 1–9999 的中文数字；范围外退回阿拉伯数字。 */
    fun of(n: Int): String {
        if (n < 1 || n > 9999) return n.toString()
        val s = n.toString()
        val sb = StringBuilder()
        var pendingZero = false
        var started = false
        for (i in s.indices) {
            val v = s[i] - '0'
            val unit = units[s.length - i]
            if (v == 0) { pendingZero = started } else {
                if (pendingZero) sb.append('零')
                // 10–19 写「十一」不写「一十一」
                if (!(v == 1 && unit == "十" && !started)) sb.append(d[v])
                sb.append(unit)
                started = true; pendingZero = false
            }
        }
        return sb.toString()
    }
}

object VolumeLabel {
    /** 完整卷头：有卷名时「第一卷 · 山门初入」，没有时「第一卷」。[index] 从 1 起。 */
    fun full(index: Int, title: String): String {
        val label = short(index)
        return if (title.isBlank()) label else "$label · $title"
    }

    fun short(index: Int): String =
        if (index in 1..999) "第${CnNum.of(index)}卷" else "第${index}卷"
}

object ChapterLabel {
    /** 界面显示：「第 118 章 擂台余波」；无章名时只有「第 118 章」。 */
    fun full(n: Int, title: String): String =
        if (title.isBlank()) short(n) else "${short(n)} $title"

    /** 章内引用（伏笔、问题卡、设定状态等）：「第 118 章」。 */
    fun short(n: Int): String = "第 $n 章"

    fun export(n: Int, title: String, style: ExportNumStyle): String {
        val label = when (style) {
            ExportNumStyle.Chinese -> "第${CnNum.of(n)}章"
            ExportNumStyle.Spaced -> short(n)
            ExportNumStyle.Arabic -> "第${n}章"
        }
        return if (title.isBlank()) label else "$label $title"
    }
}

/** 导出时的章节号格式选项。 */
enum class ExportNumStyle(val id: String, val label: String) {
    Arabic("arabic", "第118章"),
    Spaced("spaced", "第 118 章"),
    Chinese("chinese", "第一百一十八章");

    companion object {
        fun of(id: String?): ExportNumStyle = entries.firstOrNull { it.id == id } ?: Arabic
    }
}

object Naming {
    // 只剥离开头的编号前缀（含可选的分隔符），中间出现的不动；迁移和向导入库共用同一套规则。
    private val volPrefix = Regex("""^第\s*[一二三四五六七八九十百零〇0-9]+\s*卷\s*[·:：\-—]?\s*""")
    private val chPrefix = Regex("""^第\s*[一二三四五六七八九十百千零〇0-9]+\s*章\s*[·:：\-—]?\s*""")

    /** 卷标题去掉「第X卷」前缀；「第一卷 · 山门初入」→「山门初入」。 */
    fun stripVolume(title: String): String = title.replace(volPrefix, "").trim()

    /** 章标题去掉「第X章」前缀；「第 3 章 夜雨」→「夜雨」，「第一章」→""。 */
    fun stripChapter(title: String): String = title.replace(chPrefix, "").trim()
}
