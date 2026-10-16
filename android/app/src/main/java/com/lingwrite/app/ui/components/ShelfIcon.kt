// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 书架图标（对应稿中 Material Symbols "shelves"），线框与填充两种。 */
private fun shelf(filled: Boolean) = ImageVector.Builder(if (filled) "ShelfFilled" else "Shelf", 24.dp, 24.dp, 24f, 24f).apply {
    // 外框
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 3.5f); lineTo(19f, 3.5f); quadTo(20f, 3.5f, 20f, 4.5f); lineTo(20f, 19.5f); quadTo(20f, 20.5f, 19f, 20.5f)
        lineTo(5f, 20.5f); quadTo(4f, 20.5f, 4f, 19.5f); lineTo(4f, 4.5f); quadTo(4f, 3.5f, 5f, 3.5f); close()
        moveTo(4f, 12f); lineTo(20f, 12f)
    }
    if (filled) {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 6f); lineTo(9f, 6f); lineTo(9f, 12f); lineTo(7f, 12f); close()
            moveTo(10.5f, 7f); lineTo(12.5f, 7f); lineTo(12.5f, 12f); lineTo(10.5f, 12f); close()
            moveTo(7f, 15f); lineTo(13f, 15f); lineTo(13f, 20f); lineTo(7f, 20f); close()
        }
    } else {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(8f, 6.5f); lineTo(8f, 11f); moveTo(11.5f, 7.5f); lineTo(11.5f, 11f)
            moveTo(8f, 15.5f); lineTo(8f, 20f); moveTo(11.5f, 16.5f); lineTo(14.5f, 20f)
        }
    }
}.build()

val ShelfOutlined: ImageVector by lazy { shelf(false) }
val ShelfSolid: ImageVector by lazy { shelf(true) }
