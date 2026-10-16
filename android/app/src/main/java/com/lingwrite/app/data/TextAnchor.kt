// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/** AI 建议绑定生成时的正文与选区，正文改过时拒绝旧结果，避免猜测位置。 */
data class TextAnchor(val base: String, val start: Int, val end: Int) {
    fun valid(text: String) = text == base && start in 0..text.length && end in start..text.length
    fun range(text: String): IntRange? = if (valid(text)) start until end else null
}
