// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.data.CnNum
import com.lingwrite.app.data.ExportNumStyle
import com.lingwrite.app.data.Naming
import com.lingwrite.app.data.VolumeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 命名规范（docs/v0.2/naming-bug.md §3、§5）。 */
class NamingTest {
    @Test fun cnNum() {
        assertEquals("一", CnNum.of(1))
        assertEquals("二", CnNum.of(2))
        assertEquals("十", CnNum.of(10))
        assertEquals("十一", CnNum.of(11))
        assertEquals("十九", CnNum.of(19))
        assertEquals("二十", CnNum.of(20))
        assertEquals("二十一", CnNum.of(21))
        assertEquals("九十九", CnNum.of(99))
        assertEquals("一百", CnNum.of(100))
        assertEquals("一百零一", CnNum.of(101))
        assertEquals("一百一十", CnNum.of(110))
        assertEquals("一百一十一", CnNum.of(111))
        assertEquals("九百九十九", CnNum.of(999))
        assertEquals("一千", CnNum.of(1000))
        assertEquals("一千零一", CnNum.of(1001))
        assertEquals("一千零一十", CnNum.of(1010))
        assertEquals("一千一百", CnNum.of(1100))
        assertEquals("九千九百九十九", CnNum.of(9999))
        // 范围外退回阿拉伯数字
        assertEquals("0", CnNum.of(0))
        assertEquals("10000", CnNum.of(10000))
    }

    @Test fun volumeLabel() {
        assertEquals("第一卷", VolumeLabel.full(1, ""))
        assertEquals("第一卷 · 山门初入", VolumeLabel.full(1, "山门初入"))
        assertEquals("第二卷", VolumeLabel.short(2))
        assertEquals("第十一卷 · 风起", VolumeLabel.full(11, "风起"))
        // 超过 999 卷退回阿拉伯数字
        assertEquals("第1000卷", VolumeLabel.short(1000))
    }

    @Test fun chapterLabel() {
        assertEquals("第 118 章", ChapterLabel.short(118))
        assertEquals("第 118 章", ChapterLabel.full(118, ""))
        assertEquals("第 118 章 擂台余波", ChapterLabel.full(118, "擂台余波"))
        assertEquals("第118章 擂台余波", ChapterLabel.export(118, "擂台余波", ExportNumStyle.Arabic))
        assertEquals("第 118 章 擂台余波", ChapterLabel.export(118, "擂台余波", ExportNumStyle.Spaced))
        assertEquals("第一百一十八章 擂台余波", ChapterLabel.export(118, "擂台余波", ExportNumStyle.Chinese))
        assertEquals("第3章", ChapterLabel.export(3, "", ExportNumStyle.Arabic))
    }

    @Test fun stripPrefixes() {
        assertEquals("", Naming.stripVolume("第一卷"))
        assertEquals("", Naming.stripVolume("第2卷"))
        assertEquals("风起", Naming.stripVolume("第2卷 · 风起"))
        assertEquals("山门初入", Naming.stripVolume("第一卷 · 山门初入"))
        assertEquals("山门初入", Naming.stripVolume("第一卷·山门初入"))
        assertEquals("风起", Naming.stripVolume("第一卷：风起"))
        // 用户自己写的“第X卷”不在开头时不动
        assertEquals("风起 · 第二卷", Naming.stripVolume("风起 · 第二卷"))
        assertEquals("", Naming.stripChapter("第一章"))
        assertEquals("", Naming.stripChapter("第 2 章"))
        assertEquals("夜雨", Naming.stripChapter("第 3 章 夜雨"))
        assertEquals("夜雨", Naming.stripChapter("第3章 夜雨"))
        // 中间出现的编号不动
        assertEquals("回到第 3 章", Naming.stripChapter("回到第 3 章"))
    }
}
