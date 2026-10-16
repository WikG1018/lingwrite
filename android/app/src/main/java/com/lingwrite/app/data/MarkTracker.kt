// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/**
 * 正文被编辑时同步移动 AI 段落标记。
 * 用最长公共前后缀定位单次修改区间：区间前的标记不动，区间后的整体平移；
 * 修改落在某个 AI 标记内部时，该标记改为 "edited"（AI 建议 + 人工修改）。
 */
object MarkTracker {
    fun adjust(old: String, new: String, marks: List<AiMark>): List<AiMark> {
        if (old == new || marks.isEmpty()) return marks
        var pre = 0
        val max = minOf(old.length, new.length)
        while (pre < max && old[pre] == new[pre]) pre++
        var suf = 0
        while (suf < max - pre && old[old.length - 1 - suf] == new[new.length - 1 - suf]) suf++
        val oldEnd = old.length - suf
        val delta = new.length - old.length
        return marks.mapNotNull { m ->
            when {
                m.e <= pre -> m
                m.s >= oldEnd -> m.copy(s = m.s + delta, e = m.e + delta)
                else -> {
                    val s = minOf(m.s, pre)
                    val e = maxOf(m.e + delta, pre)
                    if (e <= s) null else AiMark(s, e, "edited")
                }
            }
        }.filter { it.e > it.s && it.s >= 0 && it.e <= new.length }
    }
}

/** 段落级 diff（LCS），用于版本对比。 */
object ParaDiff {
    enum class Op { Same, Add, Del }
    data class Line(val op: Op, val text: String)

    fun diff(a: String, b: String): List<Line> {
        val x = a.split('\n').filter { it.isNotBlank() }
        val y = b.split('\n').filter { it.isNotBlank() }
        val n = x.size; val m = y.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = mutableListOf<Line>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                x[i] == y[j] -> { out += Line(Op.Same, x[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += Line(Op.Del, x[i]); i++ }
                else -> { out += Line(Op.Add, y[j]); j++ }
            }
        }
        while (i < n) out += Line(Op.Del, x[i++])
        while (j < m) out += Line(Op.Add, y[j++])
        return out
    }
}
