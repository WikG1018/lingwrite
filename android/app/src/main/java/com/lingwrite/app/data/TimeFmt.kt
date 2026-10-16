// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFmt {
    private val zone get() = ZoneId.systemDefault()
    fun relative(t: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - t) / 1000
        val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val lang = UiLang.code()
        if (lang == "en" || lang == "ja") {
            val ja = lang == "ja"
            return when {
                d < 60 -> if (ja) "たった今" else "just now"
                d < 3600 -> if (ja) "${d / 60}分前" else "${d / 60} min ago"
                day == today -> if (ja) "${d / 3600}時間前" else "${d / 3600} h ago"
                day == today.minusDays(1) -> if (ja) "昨日" else "Yesterday"
                day.isAfter(today.minusDays(7)) -> (today.toEpochDay() - day.toEpochDay()).let { if (ja) "${it}日前" else "$it days ago" }
                day.year == today.year -> if (ja) "${day.monthValue}月${day.dayOfMonth}日" else DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).format(day)
                else -> if (ja) "${day.year}年${day.monthValue}月${day.dayOfMonth}日" else DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).format(day)
            }
        }
        val tw = lang == "zh-TW"
        return when {
            d < 60 -> if (tw) "剛剛" else "刚刚"
            d < 3600 -> if (tw) "${d / 60} 分鐘前" else "${d / 60} 分钟前"
            day == today -> if (tw) "${d / 3600} 小時前" else "${d / 3600} 小时前"
            day == today.minusDays(1) -> "昨天"
            day.isAfter(today.minusDays(7)) -> "${today.toEpochDay() - day.toEpochDay()} 天前"
            day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
            else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        }
    }
    fun clock(t: Long): String = DateTimeFormatter.ofPattern(if (UiLang.code() == "en") "MMM d, HH:mm" else "M月d日 HH:mm", if (UiLang.code() == "en") Locale.ENGLISH else Locale.CHINA).format(Instant.ofEpochMilli(t).atZone(zone))
    fun day(t: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochMilli(t).atZone(zone))
    fun startOfToday(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}
