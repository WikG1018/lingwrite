// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/** 网文口径字数：每个汉字/全角标点计 1，连续拉丁字母或数字计 1 个词，空白不计。 */
object Words {
    fun count(text: String): Int {
        var n = 0; var inWord = false
        for (ch in text) {
            when {
                ch.isWhitespace() -> inWord = false
                ch.code < 128 && ch.isLetterOrDigit() -> { if (!inWord) n++; inWord = true }
                ch.code < 128 -> inWord = false
                else -> { n++; inWord = false }
            }
        }
        return n
    }

    fun format(n: Int): String = when {
        n >= 10_000 -> "%.1f 万字".format(n / 10_000f)
        else -> "%,d 字".format(n)
    }

    fun grouped(n: Int): String = "%,d".format(n)
}
