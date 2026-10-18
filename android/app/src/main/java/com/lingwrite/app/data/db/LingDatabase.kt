// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lingwrite.app.data.Naming

/** 当前 schema 版本；迁移时同步改这里和 [LingDatabase] 的 version 注解。 */
const val DB_VERSION = 10

/**
 * v0.2 起命名规范：卷 / 章标题只存名称，编号显示时计算（docs/v0.2/naming-bug.md）。
 * 旧数据里写死的「第X卷」「第X章」前缀在迁移时剥离。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, title FROM volumes").use { c ->
            val rows = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
            rows.forEach { (id, old) ->
                val new = Naming.stripVolume(old)
                if (new != old) db.execSQL("UPDATE volumes SET title = ? WHERE id = ?", arrayOf(new, id))
            }
        }
        db.query("SELECT id, title FROM chapters").use { c ->
            val rows = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
            rows.forEach { (id, old) ->
                val new = Naming.stripChapter(old)
                if (new != old) db.execSQL("UPDATE chapters SET title = ? WHERE id = ?", arrayOf(new, id))
            }
        }
    }
}

/**
 * M2（docs/v0.2/modes.md §6）：books.writeMode（本书模式，空=跟随全局）、
 * chapter_notes 新表（本章便签）、call_logs.candidates/chosen（多候选）、ideas.chapterId（随手问历史）。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN writeMode TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chapter_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chapterId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_notes_chapterId` ON `chapter_notes` (`chapterId`)")
        db.execSQL("ALTER TABLE call_logs ADD COLUMN candidates INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE call_logs ADD COLUMN chosen INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE ideas ADD COLUMN chapterId INTEGER")
    }
}

/**
 * M3（docs/v0.2/outline-chain.md §2）：book_plans 加链路列（idea/logline/sellingPoints/chainStep/chain，
 * 迁移时把 wizard JSON 里的 idea/premise 拷进新列）；volume_plans 加 goal/climax/chapterCount；
 * chapter_plans 加 beats/titleIdeas。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_plans ADD COLUMN idea TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE book_plans ADD COLUMN logline TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE book_plans ADD COLUMN sellingPoints TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE book_plans ADD COLUMN chainStep INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE book_plans ADD COLUMN chain TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("ALTER TABLE volume_plans ADD COLUMN goal TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE volume_plans ADD COLUMN climax TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE volume_plans ADD COLUMN chapterCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chapter_plans ADD COLUMN beats TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE chapter_plans ADD COLUMN titleIdeas TEXT NOT NULL DEFAULT '[]'")
        // 旧向导草稿里的内容拷进新列；已有总纲的书 chainStep 记到 3（总纲完成）
        db.query("SELECT bookId, wizard, synopsis FROM book_plans WHERE wizard != '' OR synopsis != ''").use { c ->
            val rows = ArrayList<Triple<Long, String, String>>()
            while (c.moveToNext()) rows += Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
            rows.forEach { (id, wizard, synopsis) ->
                val idea = extractWizardField(wizard, "idea")
                val logline = extractWizardField(wizard, "premise").ifBlank { extractWizardField(wizard, "logline") }
                if (idea.isNotBlank() || logline.isNotBlank()) {
                    db.execSQL("UPDATE book_plans SET idea = ?, logline = ? WHERE bookId = ?", arrayOf(idea, logline, id))
                }
                if (synopsis.isNotBlank()) db.execSQL("UPDATE book_plans SET chainStep = 3 WHERE bookId = ? AND chainStep = 0", arrayOf(id))
            }
        }
    }

    /** 从 WizardData JSON 里取一个字符串字段（容忍转义；只用于迁移粗拷贝）。 */
    private fun extractWizardField(json: String, field: String): String {
        val i = json.indexOf("\"$field\"")
        if (i < 0) return ""
        val j = json.indexOf(':', i + field.length + 2)
        if (j < 0) return ""
        var k = j + 1
        while (k < json.length && json[k].isWhitespace()) k++
        if (k >= json.length || json[k] != '"') return ""
        val sb = StringBuilder()
        k++
        while (k < json.length) {
            val ch = json[k]
            if (ch == '\\') { if (k + 1 < json.length) { sb.append(when (json[k + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> json[k + 1] }) }; k += 2; continue }
            if (ch == '"') break
            sb.append(ch); k++
        }
        return sb.toString()
    }
}

/**
 * M4（docs/v0.2/ai-features §2/§6/§8）：lore_suggestions 新表（AI 设定整理建议）；
 * issues 加 fix（给改法缓存）；foreshadows 加 payoffIdeas（回收建议缓存）。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lore_suggestions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`chapterId` INTEGER NOT NULL, `number` INTEGER NOT NULL, `kind` TEXT NOT NULL, `cardId` INTEGER, `payload` TEXT NOT NULL, " +
                "`quote` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL DEFAULT 'pending', `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lore_suggestions_bookId_status` ON `lore_suggestions` (`bookId`, `status`)")
        db.execSQL("ALTER TABLE issues ADD COLUMN fix TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE foreshadows ADD COLUMN payoffIdeas TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * M5（docs/v0.2/ai-features §11/§14）：books 加 bufferTarget/publishedUpTo（连载看板）；
 * ideas 加 expansions（灵感扩展）。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN bufferTarget INTEGER NOT NULL DEFAULT 5")
        db.execSQL("ALTER TABLE books ADD COLUMN publishedUpTo INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ideas ADD COLUMN expansions TEXT NOT NULL DEFAULT '[]'")
    }
}

/**
 * v6 → v7（M6）：新增 breakdowns（A12 拆文，只存分析结果不存原文）
 * 和 reader_reports（A16 读者反馈，contentHash 用于改稿前后对比）。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `breakdowns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER, `name` TEXT NOT NULL, `chapters` TEXT NOT NULL, `curve` TEXT NOT NULL, `template` TEXT NOT NULL, `notes` TEXT NOT NULL DEFAULT '[]', `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reader_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `fromChapter` INTEGER NOT NULL, `toChapter` INTEGER NOT NULL, `persona` TEXT NOT NULL, `result` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reader_reports_bookId` ON `reader_reports` (`bookId`)")
    }
}

/** 显式迁移列表：Migration(1, 2) { db -> ... } 依次追加。 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE lore_cards SET type = 'term' WHERE type = 'concept'")
    }
}

/** v8 → v9（v0.5）：追读数据、章纲回写快照、故事内时间。 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `read_metrics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`chapterNumber` INTEGER NOT NULL, `platform` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, `reads` INTEGER NOT NULL, " +
                "`completion` INTEGER NOT NULL, `favorites` INTEGER NOT NULL, `comments` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_read_metrics_bookId_chapterNumber_recordedAt` ON `read_metrics` (`bookId`, `chapterNumber`, `recordedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chapter_plan_versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chapterId` INTEGER NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `planJson` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_plan_versions_chapterId` ON `chapter_plan_versions` (`chapterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_plan_versions_bookId` ON `chapter_plan_versions` (`bookId`)")
        db.execSQL("ALTER TABLE `chapter_plans` ADD COLUMN `storyTime` TEXT NOT NULL DEFAULT ''")
    }
}

/** v9 → v10（v0.6）：系列共享设定。 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `series` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `series_lore` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seriesId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `name` TEXT NOT NULL, `aliases` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`fields` TEXT NOT NULL, `states` TEXT NOT NULL, `relations` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`seriesId`) REFERENCES `series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_lore_seriesId` ON `series_lore` (`seriesId`)")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `seriesId` INTEGER REFERENCES `series`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_seriesId` ON `books` (`seriesId`)")
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
)

@Database(
    entities = [BookEntity::class, VolumeEntity::class, ChapterEntity::class, ChapterVersionEntity::class, ProviderEntity::class, CallLogEntity::class,
        LoreCardEntity::class, LoreStateEntity::class, LoreRelationEntity::class, MapEntity::class, MapMarkEntity::class,
        BookPlanEntity::class, VolumePlanEntity::class, ChapterPlanEntity::class,
        SummaryEntity::class, VolumeSummaryEntity::class, PassageEntity::class, PassageFts::class, ChapterIndexEntity::class,
        IssueEntity::class, ForeshadowEntity::class,
        WordDayEntity::class, IdeaEntity::class, ChapterNoteEntity::class, LoreSuggestionEntity::class,
        BreakdownEntity::class, ReaderReportEntity::class, ReadMetricEntity::class, ChapterPlanVersionEntity::class,
        SeriesEntity::class, SeriesLoreEntity::class],
    version = DB_VERSION,
    exportSchema = true,
)
abstract class LingDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun ai(): AiDao
    abstract fun lore(): LoreDao
    abstract fun plan(): PlanDao
    abstract fun context(): ContextDao
    abstract fun checks(): CheckDao
    abstract fun stats(): StatsDao
    abstract fun notes(): NoteDao
    abstract fun loreSuggest(): LoreSuggestDao
    abstract fun breakdowns(): BreakdownDao
    abstract fun readerReports(): ReaderReportDao
    abstract fun readStats(): ReadStatsDao
    abstract fun series(): SeriesDao

    companion object {
        fun build(context: Context, inMemory: Boolean = false): LingDatabase {
            val b = if (inMemory) Room.inMemoryDatabaseBuilder(context, LingDatabase::class.java)
            else Room.databaseBuilder(context, LingDatabase::class.java, "lingwrite.db")
            // v0.1.0 起 schema 以 version 1 为基线（schemas/1.json）。之后每次改表：version + 1，
            // 在 MIGRATIONS 里补一个显式 Migration，并提交新导出的 schema。不再允许破坏性重建。
            b.addMigrations(*MIGRATIONS)
            // 升级快照在后台线程进行；第一次打开（随后迁移）前等它完成，见 UpgradeGuard。
            if (!inMemory) b.openHelperFactory(com.lingwrite.app.data.UpgradeGuard.gate(androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()))
            // 只对开发版：旧开发库的版本号（≤8）高于基线，降级时重建，避免装 debug 包的机器打不开。
            if (com.lingwrite.app.BuildConfig.DEBUG) b.fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            return b.build()
        }
    }
}
