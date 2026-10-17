// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 数据库升级前的原始库快照。
 *
 * Room 在 Application.onCreate 里打开数据库，迁移一旦失败，原始数据虽然通常还能保留，
 * 但作者没有可导出的副本。这里在 Room 打开之前直接读 SQLite 文件头的 user_version：
 * 低于当前版本就把主库 + WAL + SHM 打成一个 zip，放到备份目录，保留最近 2 份。
 *
 * 只备份，不自动恢复；快照可以在备份页导出。
 */
object UpgradeGuard {
    /** SQLite 文件头偏移 60 起的 4 字节大端整数即 user_version。 */
    fun readUserVersion(file: File): Int {
        if (!file.isFile || file.length() < 64) return 0
        return runCatching {
            file.inputStream().use { input ->
                var skipped = 0L
                while (skipped < 60) {
                    val n = input.skip(60 - skipped)
                    if (n <= 0) return 0
                    skipped += n
                }
                var v = 0
                repeat(4) { v = (v shl 8) or (input.read() and 0xFF) }
                v
            }
        }.getOrDefault(0)
    }

    /** 供测试和启动流程共用；版本 0（新装）、等于/高于当前版本时不备份。 */
    fun snapshotIfNeeded(ctx: Context, currentVersion: Int = com.lingwrite.app.data.db.DB_VERSION): File? {
        val dbFile = ctx.getDatabasePath("lingwrite.db")
        if (!dbFile.isFile) return null
        val old = readUserVersion(dbFile)
        if (old <= 0 || old >= currentVersion) return null
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val out = File(dir, "upgrade-v$old-to-v$currentVersion.zip")
        if (out.isFile) return out
        val ok = runCatching {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { f ->
                    if (!f.isFile) return@forEach
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            out
        }.getOrNull()
        if (ok == null) { out.delete(); return null }
        dir.listFiles { f -> f.name.startsWith("upgrade-") }?.sortedByDescending { it.name }?.drop(2)?.forEach { it.delete() }
        return out
    }

    fun snapshots(ctx: Context): List<File> =
        File(ctx.filesDir, "backups").listFiles { f -> f.isFile && f.name.startsWith("upgrade-") }?.sortedByDescending { it.name }.orEmpty()
}
