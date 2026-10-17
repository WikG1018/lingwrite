// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

/**
 * 导出模板。平台后台格式会变，这里只提供可编辑的起点，不宣称是官方格式。
 * token：{n}=章号，{cn}=中文卷号，{title}=名称，{book}=书名。
 */
data class ExportPreset(
    val id: String,
    val name: String,
    val chapterTitle: String = "第{n}章 {title}",
    val volumeTitle: String = "第{cn}卷 {title}",
    val indent: Boolean = true,
    val blankLine: Boolean = false,
    val includeBlurb: Boolean = true,
)

val ExportPresets = listOf(
    ExportPreset("web", "通用网文"),
    ExportPreset("qidian", "起点", blankLine = true),
    ExportPreset("fanqie", "番茄"),
    ExportPreset("jinjiang", "晋江", blankLine = true),
    ExportPreset("plain", "纯文本", chapterTitle = "{title}", volumeTitle = "{title}", indent = false, blankLine = true),
    ExportPreset("markdown", "Markdown", chapterTitle = "### {n}. {title}", volumeTitle = "## {cn}. {title}", indent = false, blankLine = true),
)

fun exportPresetById(id: String?): ExportPreset = ExportPresets.firstOrNull { it.id == id } ?: ExportPresets.first()

/** 替换模板 token；空名称不留下多余空格。 */
fun renderExportTemplate(format: String, n: Int, cn: String, title: String, book: String): String =
    format.replace("{n}", n.toString())
        .replace("{cn}", cn)
        .replace("{title}", title)
        .replace("{book}", book)
        .replace(Regex(" {2,}"), " ")
        .trim()
