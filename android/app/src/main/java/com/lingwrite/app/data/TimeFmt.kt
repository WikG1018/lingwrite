// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFmt {
    private val zone get() = ZoneId.systemDefault()
    fun relative(t: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - t) / 1000
        val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            day == today -> "${d / 3600} 小时前"
            day == today.minusDays(1) -> "昨天"
            day.isAfter(today.minusDays(7)) -> "${today.toEpochDay() - day.toEpochDay()} 天前"
            day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
            else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        }
    }
    fun clock(t: Long): String = DateTimeFormatter.ofPattern("M月d日 HH:mm").format(Instant.ofEpochMilli(t).atZone(zone))
    fun day(t: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochMilli(t).atZone(zone))
    fun startOfToday(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}
