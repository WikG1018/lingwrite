// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import java.io.File

/** 书面图片：filesDir/covers/{bookId}.png。没有文件时界面用纯色封面。 */
object BookCovers {
    fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun file(ctx: Context, bookId: Long) = File(dir(ctx), "$bookId.png")
}
