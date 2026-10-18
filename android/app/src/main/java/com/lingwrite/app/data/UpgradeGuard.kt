// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据库升级前的原始库快照。
 *
 * 迁移一旦失败，原始数据虽然通常还能保留，但作者没有可导出的副本。这里在 Room 第一次打开数据库之前
 * 直接读 SQLite 文件头的 user_version：低于当前版本就把主库 + WAL + SHM 打成一个 zip，放到应用私有的
 * 备份目录，保留最近 [KEEP] 份。
 *
 * - 不在主线程做：[start] 只读 64 字节文件头（可在主线程），真正的复制放到后台线程；
 *   Room 通过 [gate] 包装的 OpenHelper 在第一次 getWritableDatabase 时等待快照完成（Room 的查询本来就不在主线程），
 *   界面用 [busy] 显示「正在备份数据…」。
 * - 先写 `.tmp`，成功后原子 rename；启动时清理被杀进程留下的 `.tmp`，不会把半截 zip 当成完成的快照。
 * - 只备份，不自动恢复。快照文件留在应用私有目录；从备份页导出时走 [exportSanitized]，
 *   导出的副本里 `providers.keyCipher` 会被清空，与「Key 不会写进备份文件」一致。
 *
 * 注：[readUserVersion] 读主库文件头。WAL 没 checkpoint 时文件头可能是旧版本号，最坏结果是多做一次快照，无害。
 */
object UpgradeGuard {
    const val KEEP = 2
    private const val PREFIX = "upgrade-"
    private val nameRe = Regex("""upgrade-v(\d+)-to-v(\d+)\.zip""")

    private val _busy = MutableStateFlow(false)
    /** 正在做升级快照（界面显示「正在备份数据…」，Room 打开会等待它）。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    @Volatile private var latch: CountDownLatch? = null

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

    private fun dir(ctx: Context) = File(ctx.filesDir, "backups")

    /** 是否需要快照：版本 0（新装）、等于/高于当前版本时不需要。只读文件头，开销可忽略。 */
    fun needsSnapshot(ctx: Context, currentVersion: Int = com.lingwrite.app.data.db.DB_VERSION): Boolean {
        val dbFile = ctx.getDatabasePath("lingwrite.db")
        if (!dbFile.isFile) return false
        val old = readUserVersion(dbFile)
        if (old <= 0 || old >= currentVersion) return false
        return !File(dir(ctx), "$PREFIX" + "v$old-to-v$currentVersion.zip").isFile
    }

    /**
     * 启动入口（主线程调用安全）：清理残留 .tmp；需要快照时在后台线程执行，并让 [awaitReady] / [gate] 等待。
     * 必须在 Room 构建之前调用。返回是否启动了后台快照。
     */
    fun start(ctx: Context, currentVersion: Int = com.lingwrite.app.data.db.DB_VERSION): Boolean {
        val app = ctx.applicationContext
        cleanupTmp(app)
        if (!needsSnapshot(app, currentVersion)) return false
        val l = CountDownLatch(1)
        latch = l
        _busy.value = true
        Thread({
            try { snapshotIfNeeded(app, currentVersion) } catch (_: Throwable) { } finally {
                _busy.value = false
                l.countDown()
            }
        }, "lingwrite-upgrade-snapshot").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        return true
    }

    /** 阻塞到快照完成（没有进行中的快照时立即返回）。只能在后台线程调用。 */
    fun awaitReady() { latch?.await() }

    /** 包装 Room 的 OpenHelper：第一次拿数据库连接前等待快照完成，保证快照拍的是迁移前的原始库。 */
    fun gate(delegate: SupportSQLiteOpenHelper.Factory): SupportSQLiteOpenHelper.Factory =
        SupportSQLiteOpenHelper.Factory { config ->
            val helper = delegate.create(config)
            object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = run { awaitReady(); helper.writableDatabase }
                override val readableDatabase get() = run { awaitReady(); helper.readableDatabase }
            }
        }

    fun cleanupTmp(ctx: Context) {
        dir(ctx).listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /** 同步执行快照（后台线程 / 测试用）。版本 0（新装）、等于/高于当前版本时不备份。 */
    fun snapshotIfNeeded(ctx: Context, currentVersion: Int = com.lingwrite.app.data.db.DB_VERSION): File? {
        val dbFile = ctx.getDatabasePath("lingwrite.db")
        if (!dbFile.isFile) return null
        val old = readUserVersion(dbFile)
        if (old <= 0 || old >= currentVersion) return null
        val dir = dir(ctx).apply { mkdirs() }
        val out = File(dir, "${PREFIX}v$old-to-v$currentVersion.zip")
        if (out.isFile) return out
        val tmp = File(dir, out.name + ".tmp")
        val ok = runCatching {
            ZipOutputStream(tmp.outputStream().buffered(1 shl 16)).use { zip ->
                // 数据库页大多已是紧凑的二进制，压缩收益小、CPU 开销大：只打包不压缩。
                zip.setLevel(Deflater.NO_COMPRESSION)
                listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { f ->
                    if (!f.isFile) return@forEach
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
            check(tmp.renameTo(out)) { "rename failed" }
            out
        }.getOrNull()
        if (ok == null) { tmp.delete(); out.delete(); return null }
        prune(dir)
        return out
    }

    /** 排序键：目标版本、源版本按整数比较（字符串排序会把 v10 排在 v9 后面），解析不了的按修改时间排在后面。 */
    private fun order(f: File): Triple<Int, Int, Long> {
        val m = nameRe.matchEntire(f.name)
        return Triple(m?.groupValues?.get(2)?.toIntOrNull() ?: -1, m?.groupValues?.get(1)?.toIntOrNull() ?: -1, f.lastModified())
    }

    private val newestFirst = compareByDescending<File> { order(it).first }.thenByDescending { order(it).second }.thenByDescending { order(it).third }

    /** 只保留最新的 [KEEP] 份。 */
    fun prune(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }
            ?.sortedWith(newestFirst)?.drop(KEEP)?.forEach { it.delete() }
    }

    fun snapshots(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }?.sortedWith(newestFirst).orEmpty()

    /** 快照显示名，如 "v9 → v10"。 */
    fun label(f: File): String = nameRe.matchEntire(f.name)?.let { "v${it.groupValues[1]} → v${it.groupValues[2]}" }
        ?: f.name.removePrefix(PREFIX).removeSuffix(".zip")

    /**
     * 导出快照：解到临时目录，用 SQLite 打开副本（会合并 WAL），开 secure_delete 后清空 `providers.keyCipher`，
     * 再把单个数据库文件写进 zip。原快照不变；临时文件用完即删。
     */
    fun exportSanitized(snapshot: File, out: OutputStream, workDir: File) {
        val tmpDir = File(workDir, "snap-export-${System.nanoTime()}").apply { mkdirs() }
        try {
            ZipInputStream(snapshot.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = File(e.name).name
                    if (e.isDirectory || name !in setOf("lingwrite.db", "lingwrite.db-wal", "lingwrite.db-shm")) continue
                    File(tmpDir, name).outputStream().use { zin.copyTo(it) }
                }
            }
            val db = File(tmpDir, "lingwrite.db")
            check(db.isFile) { "snapshot has no database" }
            sanitize(db)
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(db.name)); db.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        } finally { tmpDir.deleteRecursively() }
    }

    /** 清空副本里的 API Key 密文；没有 providers 表或列（极老版本）就跳过。 */
    fun sanitize(db: File) {
        val d = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            d.rawQuery("PRAGMA secure_delete = ON", null).close()
            val hasCol = d.rawQuery("PRAGMA table_info(providers)", null).use { c ->
                var found = false
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) if (c.getString(idx) == "keyCipher") found = true
                found
            }
            if (hasCol) d.execSQL("UPDATE providers SET keyCipher = ''")
            // 把 WAL 合进主库，导出只需单个文件。
            d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            d.execSQL("VACUUM")
        } finally { d.close() }
        File(db.path + "-wal").delete(); File(db.path + "-shm").delete(); File(db.path + "-journal").delete()
    }
}
