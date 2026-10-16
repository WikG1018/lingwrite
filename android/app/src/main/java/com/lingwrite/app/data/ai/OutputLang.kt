// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import com.lingwrite.app.AppGraph

/**
 * AI 输出语言。提示词本身保持中文；这里只决定“要求模型用什么语言写”。
 * 偏好 [com.lingwrite.app.data.AppPrefs.aiOutputLang]：auto（默认，跟随作品语言）/ zh-CN / zh-TW / en / ja。
 */
object OutputLang {
    val choices = listOf("auto", "zh-CN", "zh-TW", "en", "ja")

    // 只在繁体里出现的常用字（简体写法不同），用来区分简繁。
    private const val TRAD = "們這說會來時為與個門對後關開見讓還應進過裡麼當從無話樣實點問題經發現長頭邊將馬學電車劍聲體變間"
    private const val SIMP = "们这说会来时为与个门对后关开见让还应进过里么当从无话样实点问题经发现长头边将马学电车剑声体变间"

    /** 按文字粗判语言：有假名 → ja；拉丁字母为主 → en；繁体特征字多于简体 → zh-TW；其余 zh-CN。空文本返回 null。 */
    fun detect(text: String): String? {
        var kana = 0; var han = 0; var latin = 0; var trad = 0; var simp = 0
        for (ch in text) {
            when {
                ch in '\u3040'..'\u30ff' -> kana++
                ch in '\u4e00'..'\u9fff' -> { han++; if (TRAD.indexOf(ch) >= 0) trad++ else if (SIMP.indexOf(ch) >= 0) simp++ }
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        if (kana + han + latin < 8) return null
        return when {
            kana > 0 && kana * 20 >= kana + han -> "ja"
            latin > (han + kana) * 3 -> "en"
            trad > simp -> "zh-TW"
            else -> "zh-CN"
        }
    }

    private val cache = HashMap<Long, Pair<Long, String?>>()

    /** 作品语言：书名 + 简介 + 最近几章开头的样本。结果按作品缓存 10 分钟。 */
    suspend fun ofBook(bookId: Long): String? {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[bookId]?.let { if (now - it.first < 600_000) return it.second } }
        val db = AppGraph.db
        val b = db.books().get(bookId) ?: return null
        val sample = buildString {
            append(b.title).append('\n').append(b.blurb).append('\n')
            db.chapters().chaptersOnce(bookId).takeLast(3).forEach { append(it.content.take(800)) }
        }
        val lang = detect(sample)
        synchronized(cache) { cache[bookId] = now to lang }
        return lang
    }

    /** 实际要的输出语言；null 表示不加要求（简体中文作品或无从判断）。 */
    suspend fun resolve(pref: String, bookId: Long?): String? {
        val lang = if (pref == "auto" || pref.isBlank()) bookId?.let { runCatching { ofBook(it) }.getOrNull() } else pref
        return lang?.takeIf { it != "zh-CN" }
    }

    /** 附在系统指令末尾的中文要求。 */
    fun directive(lang: String): String {
        val name = when (lang) { "en" -> "英语"; "ja" -> "日语"; "zh-TW" -> "繁体中文（台湾用字）"; else -> "简体中文" }
        return "\n【输出语言】以上说明是中文，但你的输出（正文、建议、JSON 里的文字字段）一律用$name。人名、地名等专名保持作品里已有的写法；JSON 的键名不变。"
    }
}
