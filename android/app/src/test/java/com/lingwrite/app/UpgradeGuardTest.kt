// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.UpgradeGuard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** 审查 #7：升级快照的数字排序、tmp + rename、后台执行和导出脱敏。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UpgradeGuardTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val dir get() = File(ctx.filesDir, "backups")
    private val dbFile get() = ctx.getDatabasePath("lingwrite.db")

    @Before fun clean() { dir.deleteRecursively(); dbFile.parentFile?.listFiles()?.forEach { it.delete() } }
    @After fun tearDown() { dir.deleteRecursively(); dbFile.parentFile?.listFiles()?.forEach { it.delete() } }

    private fun header(version: Int) = ByteArray(128).also { it[63] = version.toByte() }

    @Test fun pruneKeepsNewestByVersionNumberNotString() {
        dir.mkdirs()
        // 字典序下 v10-to-v11 排在 v9-to-v10、v8-to-v9 之后，旧实现会把它删掉。
        listOf("upgrade-v8-to-v9.zip", "upgrade-v9-to-v10.zip", "upgrade-v10-to-v11.zip").forEachIndexed { i, n ->
            File(dir, n).apply { writeText("x"); setLastModified(1_000_000L - i * 1000) } // mtime 故意倒序，确认按版本号排
        }
        UpgradeGuard.prune(dir)
        assertEquals(setOf("upgrade-v10-to-v11.zip", "upgrade-v9-to-v10.zip"), dir.list()!!.toSet())
        assertEquals(listOf("upgrade-v10-to-v11.zip", "upgrade-v9-to-v10.zip"), UpgradeGuard.snapshots(ctx).map { it.name })
        assertEquals("v10 → v11", UpgradeGuard.label(UpgradeGuard.snapshots(ctx).first()))
    }

    @Test fun newSnapshotSurvivesPruneAcrossV10() {
        dir.mkdirs()
        File(dir, "upgrade-v8-to-v9.zip").writeText("x"); File(dir, "upgrade-v9-to-v10.zip").writeText("x")
        dbFile.apply { parentFile?.mkdirs(); writeBytes(header(10)) }
        val out = UpgradeGuard.snapshotIfNeeded(ctx, currentVersion = 11)
        assertNotNull(out)
        assertTrue("刚生成的最新快照不能被清理", out!!.isFile)
        assertEquals(listOf("upgrade-v10-to-v11.zip", "upgrade-v9-to-v10.zip"), UpgradeGuard.snapshots(ctx).map { it.name })
    }

    @Test fun leftoverTmpIsCleanedAndNeverListed() {
        dir.mkdirs()
        val half = File(dir, "upgrade-v7-to-v8.zip.tmp").apply { writeText("half") }
        assertTrue(UpgradeGuard.snapshots(ctx).isEmpty())
        dbFile.apply { parentFile?.mkdirs(); writeBytes(header(7)) }
        assertTrue(UpgradeGuard.start(ctx, currentVersion = 8))
        assertFalse("启动时清掉被杀进程留下的 .tmp", half.exists())
        UpgradeGuard.awaitReady()
        assertFalse(UpgradeGuard.busy.value)
        assertEquals(listOf("upgrade-v7-to-v8.zip"), dir.list()!!.toList())
        // 已有快照：不再启动
        assertFalse(UpgradeGuard.start(ctx, currentVersion = 8))
    }

    @Test fun startDoesNothingForFreshOrCurrentDatabase() {
        assertFalse("新装没有库", UpgradeGuard.start(ctx, currentVersion = 8))
        dbFile.apply { parentFile?.mkdirs(); writeBytes(header(8)) }
        assertFalse(UpgradeGuard.start(ctx, currentVersion = 8))
        assertFalse(UpgradeGuard.busy.value)
    }

    @Test fun exportedSnapshotHasNoKeyCipher() {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { d ->
            d.execSQL("CREATE TABLE providers (id INTEGER PRIMARY KEY, name TEXT NOT NULL, keyCipher TEXT NOT NULL)")
            d.execSQL("INSERT INTO providers (name, keyCipher) VALUES ('p', 'SECRET-CIPHER-TEXT-0123456789')")
            d.version = 9
        }
        val snap = UpgradeGuard.snapshotIfNeeded(ctx, currentVersion = 10)!!
        val bytes = ByteArrayOutputStream().also { UpgradeGuard.exportSanitized(snap, it, ctx.cacheDir) }.toByteArray()
        val out = File(ctx.cacheDir, "exported.db")
        ZipInputStream(bytes.inputStream()).use { z ->
            val e = z.nextEntry!!
            assertEquals("lingwrite.db", e.name)
            out.outputStream().use { z.copyTo(it) }
            assertNull("导出只含单个数据库文件", z.nextEntry)
        }
        SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT keyCipher, name FROM providers", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("", c.getString(0)); assertEquals("p", c.getString(1))
            }
            assertEquals(9, d.version)
        }
        assertFalse("密文不应残留在文件页里", String(out.readBytes(), Charsets.ISO_8859_1).contains("SECRET-CIPHER"))
        // 原快照不变（只留在应用私有目录，用于恢复）
        assertTrue(snap.isFile)
    }
}
