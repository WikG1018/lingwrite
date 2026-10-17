// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap

class BookRepository(private val db: LingDatabase, private val filesDir: java.io.File? = null) {
    private val books = db.books()
    private val ch = db.chapters()
    private val plan = db.plan()
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private data class Pending(val text: String, val marks: String, val rev: Long)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val latestRev = ConcurrentHashMap<Long, AtomicLong>()
    private val revisions = AtomicLong()
    private val saveErrors = ConcurrentHashMap<Long, String>()
    fun saveFailure(id: Long) = saveErrors[id]
    private fun lock(id: Long) = locks.getOrPut(id) { Mutex() }

    /** 只有更新的修订号能成为当前稿。失败表示已有更新的保存，调用方必须放弃这次正文。 */
    private fun claim(id: Long, rev: Long): Boolean {
        val slot = latestRev.computeIfAbsent(id) { AtomicLong() }
        while (true) {
            val cur = slot.get()
            if (rev < cur) return false
            if (cur == rev || slot.compareAndSet(cur, rev)) return true
        }
    }

    /** 调用方在延迟保存之前取号，后发生的离开保存不会被这次旧正文盖掉。 */
    fun captureRevision(): Long = revisions.incrementAndGet()

    /** 离开/后台的草稿先同步登记，重进编辑器绝不读回未保存的旧正文。 */
    fun queueSave(id: Long, text: String, marks: String): Job {
        val rev = captureRevision()
        val draft = Pending(text, marks, rev)
        if (!claim(id, rev)) return com.lingwrite.app.AppGraph.scope.launch { }
        pending[id] = draft
        return com.lingwrite.app.AppGraph.scope.launch {
            lock(id).withLock {
                if (latestRev[id]?.get() != rev || pending[id]?.rev != rev) return@withLock
                try {
                    saveContentLocked(id, text, marks, 5 * 60_000)
                    pending.remove(id, draft)
                    saveErrors.remove(id)
                } catch (e: Exception) {
                    if (pending[id]?.rev == rev) saveErrors[id] = "后台保存未完成，请重试：${e.message}"
                }
            }
        }
    }

    suspend fun editorChapter(id: Long): ChapterEntity? = lock(id).withLock {
        ch.get(id)?.takeIf { it.deletedAt == 0L }?.let { c ->
            pending[id]?.let { c.copy(content = it.text, aiMarks = it.marks, wordCount = Words.count(it.text)) } ?: c
        }
    }

    fun deletedChapters(bookId: Long) = ch.deletedChapters(bookId)

    fun summaries(): Flow<List<BookSummary>> = books.summaries()
    fun book(id: Long) = books.book(id)
    fun volumes(bookId: Long) = ch.volumes(bookId)
    fun chapterHeaders(bookId: Long) = ch.chapterHeaders(bookId)
    fun chapter(id: Long) = ch.chapter(id)
    fun versions(chapterId: Long) = ch.versions(chapterId)

    suspend fun createBook(title: String, genre: String = "", status: String = "draft"): Long = db.withTransaction {
        val count = books.all().size
        val id = books.insert(BookEntity(title = title.ifBlank { "未命名作品" }, genre = genre, status = status, coverIndex = count % 6))
        val vol = ch.insertVolume(VolumeEntity(bookId = id, title = "", sort = 0))
        ch.insert(ChapterEntity(bookId = id, volumeId = vol, number = 1, title = ""))
        id
    }

    suspend fun rename(id: Long, t: String) { books.get(id)?.let { updateBook(it.copy(title = t.ifBlank { it.title })) } }
    suspend fun setStatus(id: Long, st: String) { books.get(id)?.let { updateBook(it.copy(status = st)) } }

    fun todayWords(): Flow<Int> = ch.todayWords(TimeFmt.startOfToday())

    /** 书架顶部“继续写作”：最近编辑的作品里最近编辑的一章。 */
    suspend fun hero(bookId: Long): HeroInfo? {
        val b = books.get(bookId) ?: return null
        // 只取章节元数据；正文只取最近编辑的一章，避免长篇把全部正文读进内存。
        val all = ch.headersOnce(bookId)
        val last = all.maxByOrNull { it.updatedAt }
        val totals = books.totals(bookId)
        val vols = ch.volumesOnce(bookId).sortedBy { it.sort }
        val vol = last?.let { l -> vols.firstOrNull { it.id == l.volumeId } }
        val volLabel = vol?.let { v -> VolumeLabel.full(vols.indexOf(v) + 1, v.title) }
        val excerpt = ch.lastContent(bookId)?.trim()?.lines()?.lastOrNull { it.isNotBlank() }?.takeLast(60)?.let { it + "……" }
            ?: b.blurb.ifBlank { "还没开写。点继续写，从第一章开始。" }
        return HeroInfo(
            bookId = b.id, chapterId = last?.id, title = b.title,
            subline = listOfNotNull(volLabel, last?.let { ChapterLabel.full(it.number, it.title) }).joinToString("　"),
            excerpt = excerpt, highlight = null,
            words = if (totals.words >= 10_000) "%.1f".format(totals.words / 10_000f) else Words.grouped(totals.words),
            wordsUnit = if (totals.words >= 10_000) "万字" else "字",
            chapters = totals.chapters,
        )
    }

    suspend fun updateBook(b: BookEntity) = books.update(b.copy(updatedAt = System.currentTimeMillis()))
    /** 删书：连带清掉 FTS（没有外键）、地图/封面文件，避免反复建删后 DB 与磁盘只增不减。 */
    suspend fun deleteBook(id: Long) {
        val dbFile = db.openHelper.writableDatabase
        val passages = ArrayList<Long>()
        dbFile.query("SELECT id FROM passages WHERE bookId = ?", arrayOf(id)).use { c ->
            while (c.moveToNext()) passages += c.getLong(0)
        }
        val mapFiles = ArrayList<java.io.File>()
        dbFile.query("SELECT image FROM maps WHERE bookId = ?", arrayOf(id)).use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(0) ?: continue
                filesDir?.let { mapFiles += java.io.File(it, rel) }
            }
        }
        db.withTransaction {
            passages.chunked(500).forEach { ids ->
                val marks = ids.joinToString(",") { "?" }
                dbFile.execSQL("DELETE FROM passages_fts WHERE rowid IN ($marks)", Array<Any?>(ids.size) { ids[it] })
            }
            listOf("ideas", "reader_reports", "breakdowns", "word_days", "call_logs").forEach {
                dbFile.execSQL("DELETE FROM $it WHERE bookId = ?", arrayOf(id))
            }
            books.delete(id)
        }
        mapFiles.forEach { runCatching { it.delete() } }
        filesDir?.let { runCatching { java.io.File(it, "covers/$id.png").delete() } }
    }

    suspend fun addVolume(bookId: Long, title: String): Long {
        val sort = ch.volumesOnce(bookId).size
        return ch.insertVolume(VolumeEntity(bookId = bookId, title = title, sort = sort))
    }

    /**
     * 新建下一章，返回章 id。标题只存作者起的名字，不写编号。
     * [afterChapterId] 非空时新章进这一章所在的卷（编辑器「写下一章」延续当前卷）；
     * 为空时进最后一卷（作品页/书架的兜底）。
     */
    suspend fun nextChapter(bookId: Long, title: String = "", afterChapterId: Long? = null): Long = db.withTransaction {
        val lastVol = ch.volumesOnce(bookId).lastOrNull()?.id ?: ch.insertVolume(VolumeEntity(bookId = bookId, title = "", sort = 0))
        val vol = afterChapterId?.let { ch.get(it)?.volumeId } ?: lastVol
        val n = (ch.maxNumber(bookId) ?: 0) + 1
        books.touch(bookId)
        val id = ch.insert(ChapterEntity(bookId = bookId, volumeId = vol, number = n, title = title.trim()))
        renumber(bookId)
        id
    }

    /**
     * 删除分卷：卷里的章节先移到相邻卷（前一卷，首卷则后一卷），再删卷和它的卷纲，
     * 卷顺序（sort）与章号连续重排。返回被移动的章节数；只剩一卷时返回 -1 拒绝。
     */
    suspend fun deleteVolume(bookId: Long, volumeId: Long): Int {
        val vols = ch.volumesOnce(bookId)
        if (vols.size <= 1) return -1
        val i = vols.indexOfFirst { it.id == volumeId }
        if (i < 0) return 0
        val host = vols[if (i > 0) i - 1 else i + 1]
        val moved = ch.chaptersOnce(bookId).filter { it.volumeId == volumeId }
        moved.forEach { ch.position(it.id, it.number, host.id) }
        // 已删除章节也保留历史，删卷前一并移出以免外键级联删除。
        db.openHelper.writableDatabase.execSQL("UPDATE chapters SET volumeId = ? WHERE volumeId = ?", arrayOf(host.id, volumeId))
        ch.deleteVolume(volumeId)
        plan.deleteVolumePlan(volumeId)
        vols.filter { it.id != volumeId }.forEachIndexed { k, v -> if (v.sort != k) ch.updateVolume(v.copy(sort = k)) }
        renumber(bookId)
        books.touch(bookId)
        return moved.size
    }

    /** 在指定卷末尾加一章并按（卷序, 原章号）重新编号，返回章 id。 */
    suspend fun addChapterTo(bookId: Long, volumeId: Long, title: String): Long = db.withTransaction {
        val id = ch.insert(ChapterEntity(bookId = bookId, volumeId = volumeId, number = (ch.maxNumber(bookId) ?: 0) + 1, title = title))
        renumber(bookId); books.touch(bookId); id
    }

    private fun invalidateIndex(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        val marks = list.joinToString(",") { "?" }
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM chapter_index WHERE chapterId IN ($marks)",
            Array<Any?>(list.size) { list[it] },
        )
    }

    /** 全书章号按卷顺序连续编号；章内容随章走。不改章节的修改时间。 */
    suspend fun renumber(bookId: Long) = db.withTransaction {
        val before = ch.chaptersOnce(bookId)
        val order = ch.volumesOnce(bookId).withIndex().associate { (i, v) -> v.id to i }
        val sorted = before.sortedWith(compareBy({ order[it.volumeId] ?: 0 }, { it.number }, { it.id }))
        val changed = ArrayList<Long>()
        sorted.forEachIndexed { i, c ->
            if (c.number != i + 1) { changed += c.id; ch.position(c.id, i + 1, c.volumeId) }
        }
        val newById = sorted.mapIndexed { i, c -> c.id to (i + 1) }.toMap()
        val oldToNew = LinkedHashMap<Int, Int>()
        before.forEach { c -> oldToNew.putIfAbsent(c.number, newById.getValue(c.id)) }
        remapChapterNumbers(bookId, newById, oldToNew)
        // 索引同时缓存章号，只有章号真的变了的章节需要重建，避免整书重索引。
        invalidateIndex(changed)
    }

    /** 已有章节的章号跟着章节走；还没写到的未来章号保持不变。 */
    private fun mapPoint(n: Int, oldToNew: Map<Int, Int>): Int {
        if (n <= 0) return n
        oldToNew[n]?.let { return it }
        val maxOld = oldToNew.keys.maxOrNull() ?: return n
        if (n > maxOld) return n
        val prior = oldToNew.keys.filter { it < n }.maxOrNull() ?: return 0
        return oldToNew.getValue(prior)
    }

    private fun mapStart(n: Int, oldToNew: Map<Int, Int>): Int {
        if (n <= 0) return n
        oldToNew[n]?.let { return it }
        val maxOld = oldToNew.keys.maxOrNull() ?: return n
        if (n > maxOld) return n
        val next = oldToNew.keys.filter { it > n }.minOrNull() ?: return n
        return oldToNew.getValue(next)
    }

    private suspend fun remapChapterNumbers(bookId: Long, newById: Map<Long, Int>, oldToNew: Map<Int, Int>) {
        books.get(bookId)?.let { b ->
            val mapped = mapPoint(b.publishedUpTo, oldToNew)
            if (mapped != b.publishedUpTo) books.update(b.copy(publishedUpTo = mapped))
        }
        db.checks().foreshadowsInBook(bookId).forEach { f ->
            val linked = f.chapterId?.let { newById[it] }
            val plant = if (f.plantNumber > 0 && linked != null) linked else if (f.chapterId != null) f.plantNumber else mapPoint(f.plantNumber, oldToNew)
            val payoff = f.payoff?.let { mapPoint(it, oldToNew) }
            val paid = f.paidAt?.let { mapPoint(it, oldToNew) }
            if (plant != f.plantNumber || payoff != f.payoff || paid != f.paidAt) {
                db.checks().updateForeshadow(f.copy(plantNumber = plant, payoff = payoff, paidAt = paid))
            }
        }
        db.readerReports().once(bookId).forEach { r ->
            val from = mapStart(r.fromChapter, oldToNew)
            val to = maxOf(from, mapPoint(r.toChapter, oldToNew))
            if (from != r.fromChapter || to != r.toChapter) db.readerReports().updateRange(r.id, from, to)
        }
        // 追读数据按章号存；插删章后跟着章节走。
        db.readStats().once(bookId).forEach { m ->
            val mapped = mapPoint(m.chapterNumber, oldToNew)
            if (mapped != m.chapterNumber) db.readStats().update(m.copy(chapterNumber = mapped))
        }
    }

    /** 章节上移 / 下移一位（可跨卷）。 */
    suspend fun moveChapter(id: Long, delta: Int) {
        val c = ch.get(id) ?: return
        val vols = ch.volumesOnce(c.bookId)
        val list = ch.chaptersOnce(c.bookId).sortedBy { it.number }
        val before = list.associate { it.id to it.number }
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (j !in list.indices) {
            // 已在首尾：移到相邻卷
            val vi = vols.indexOfFirst { it.id == c.volumeId } + delta
            if (vi in vols.indices) { ch.position(c.id, c.number, vols[vi].id); renumber(c.bookId) }
            return
        }
        val o = list[j]
        val reordered = list.toMutableList().apply { removeAt(i); add(j, c.copy(volumeId = o.volumeId)) }
        db.withTransaction { reordered.forEachIndexed { k, item -> ch.position(item.id, k + 1, item.volumeId) } }
        renumber(c.bookId)
        // 上面的 position 已先改了章号，renumber 可能看不到差异；按前后章号对比补失效。
        val after = ch.headersOnce(c.bookId)
        invalidateIndex(after.filter { before[it.id] != it.number }.map { it.id })
    }

    suspend fun moveVolumeIn(bookId: Long, id: Long, delta: Int) {
        val vols = ch.volumesOnce(bookId).toMutableList()
        val i = vols.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in vols.indices) return
        java.util.Collections.swap(vols, i, j)
        vols.forEachIndexed { k, v -> if (v.sort != k) ch.updateVolume(v.copy(sort = k)) }
        renumber(bookId)
    }

    suspend fun renameChapter(id: Long, title: String) = ch.rename(id, title)
    suspend fun setChapterState(id: Long, state: String) = ch.setState(id, state)
    suspend fun deleteChapter(id: Long) = lock(id).withLock {
        pending.remove(id)?.let { saveContentLocked(id, it.text, it.marks, 5 * 60_000) }
        ch.get(id)?.let { c ->
            // 删前记录左右邻居：恢复时按邻居插回原位（不依赖会被重排的旧章号，也不改 schema）。
            val visible = ch.chaptersOnce(c.bookId)
            val i = visible.indexOfFirst { it.id == id }
            if (i >= 0) RestoreHints.put(c.id, visible.getOrNull(i - 1)?.id, visible.getOrNull(i + 1)?.id)
            ch.setDeleted(id, System.currentTimeMillis()); renumber(c.bookId); books.touch(c.bookId)
        }
    }
    suspend fun restoreChapter(id: Long) = lock(id).withLock {
        val c = ch.get(id) ?: return@withLock
        if (c.deletedAt == 0L) return@withLock
        val visible = ch.chaptersOnce(c.bookId).filter { it.id != id }
        val index = resolveRestoreIndex(c, visible) ?: visible.count { it.number < c.number }
        // 先把插入点之后的章节整体后移一位，再把本章放回该位置，最后统一重排章号。
        visible.filter { it.number >= index + 1 }.forEach { ch.position(it.id, it.number + 1, it.volumeId) }
        ch.setDeleted(id, 0)
        ch.position(id, index + 1, c.volumeId)
        RestoreHints.remove(id)
        renumber(c.bookId)
        books.touch(c.bookId)
    }

    /**
     * 恢复位置（0 起的插入下标）：按删除时记录的左右邻居锚定，跳过仍被删的中间章继续找。
     * - 左邻居为空：它原本就是第一章，放最前；
     * - 右邻居为空：原本是最后一章，放最后；
     * - 否则优先插到左邻居之后，其次插到右邻居之前；
     * - 返回 null 时调用方回退到旧的章号推断（兼容升级前就已删除、没有锚点的章节）。
     */
    private suspend fun resolveRestoreIndex(c: ChapterEntity, visible: List<ChapterEntity>): Int? {
        val idx = visible.withIndex().associate { (i, x) -> x.id to i }
        val hint = RestoreHints.get(c.id) ?: return null
        if (hint.first == null) return 0
        if (hint.second == null) return visible.size
        var p: Long? = hint.first
        var guard = 0
        while (p != null && guard++ < 1000) {
            idx[p]?.let { return it + 1 }
            val h = RestoreHints.get(p) ?: break
            if (h.first == null) break
            p = h.first
        }
        var n: Long? = hint.second
        guard = 0
        while (n != null && guard++ < 1000) {
            idx[n]?.let { return it }
            n = RestoreHints.get(n)?.second
        }
        return null
    }

    /**
     * 保存正文。每次保存更新章节；距上个自动版本超过 [snapshotEveryMs] 或字数变化超过 200 时写一个自动版本，
     * 自动版本保留最近 [KEEP_AUTO] 个。
     */
    suspend fun saveContent(id: Long, content: String, aiMarks: String? = null, snapshotEveryMs: Long = 5 * 60_000, revision: Long? = null) {
        val rev = revision ?: captureRevision()
        if (!claim(id, rev)) return
        lock(id).withLock {
            if (latestRev[id]?.get() != rev) return@withLock
            val queued = pending[id]
            if (queued != null && queued.rev > rev) return@withLock
            if (latestRev[id]?.get() != rev) return@withLock
            if (queued != null) pending.remove(id, queued)
            if (latestRev[id]?.get() != rev) return@withLock
            saveContentLocked(id, content, aiMarks, snapshotEveryMs)
            saveErrors.remove(id)
        }
    }
    private suspend fun saveContentLocked(id: Long, content: String, aiMarks: String?, snapshotEveryMs: Long) = db.withTransaction {
        val c = ch.get(id) ?: return@withTransaction
        if (c.deletedAt != 0L) return@withTransaction
        if (c.content == content && (aiMarks == null || aiMarks == c.aiMarks)) return@withTransaction
        val wc = Words.count(content)
        if (c.content.isNotBlank() && (content.isBlank() || c.wordCount - wc > 200) && ch.latestVersion(id)?.content != c.content) {
            ch.insertVersion(ChapterVersionEntity(chapterId = id, content = c.content, wordCount = c.wordCount, kind = "auto", note = "大段删除前"))
        }
        ch.updateContent(id, content, wc, aiMarks ?: c.aiMarks, System.currentTimeMillis())
        db.openHelper.writableDatabase.execSQL("DELETE FROM chapter_index WHERE chapterId = ?", arrayOf(id))
        if (wc != c.wordCount) {
            val day = TimeFmt.day(System.currentTimeMillis())
            val s = db.stats()
            val cur = s.wordDay(day, c.bookId)?.words ?: 0
            s.upsertWordDay(WordDayEntity(day, c.bookId, (cur + wc - c.wordCount).coerceAtLeast(0)))
        }
        books.touch(c.bookId)
        val last = ch.latestVersion(id)
        val due = last == null || System.currentTimeMillis() - last.createdAt > snapshotEveryMs || kotlin.math.abs(last.wordCount - wc) > 200
        if (due && content.isNotBlank()) {
            ch.insertVersion(ChapterVersionEntity(chapterId = id, content = content, wordCount = wc, kind = "auto"))
            ch.pruneAuto(id, KEEP_AUTO)
        }
    }

    suspend fun snapshot(id: Long, kind: String = "manual", note: String = "") {
        val c = ch.get(id) ?: return
        ch.insertVersion(ChapterVersionEntity(chapterId = id, content = c.content, wordCount = c.wordCount, kind = kind, note = note))
    }

    /** 回滚：先把当前内容存为 rollback 快照，再写回所选版本。 */
    suspend fun rollback(chapterId: Long, versionId: Long) = lock(chapterId).withLock {
        pending.remove(chapterId)
        val v = ch.version(versionId) ?: return
        if (v.chapterId != chapterId) return@withLock
        snapshot(chapterId, "rollback", "回滚前")
        val c = ch.get(chapterId) ?: return
        saveContentLocked(c.id, v.content, "[]", 5 * 60_000)
    }

    /** 示例作品，空书架时可一键导入体验。 */
    suspend fun seedSample(): Long = db.withTransaction {
        val id = books.insert(BookEntity(title = "青云问道", genre = "仙侠", status = "serial", coverIndex = 0, dailyTarget = 4000, blurb = "断剑少年入青云，十年旧案一线牵。"))
        val v1 = ch.insertVolume(VolumeEntity(bookId = id, title = "山门初入", sort = 0))
        val v2 = ch.insertVolume(VolumeEntity(bookId = id, title = "宗门大比", sort = 1))
        val t1 = listOf("外门比试", "藏剑峰的雨", "下山之前")
        t1.forEachIndexed { i, t -> ch.insert(ChapterEntity(bookId = id, volumeId = v1, number = i + 1, title = t, state = "done", content = SAMPLE_FILLER, wordCount = Words.count(SAMPLE_FILLER))) }
        val t2 = listOf("夜探藏经阁", "内门名额只剩三个", "沈师姐的第三道符", "断剑破阵，满场寂然")
        t2.forEachIndexed { i, t -> ch.insert(ChapterEntity(bookId = id, volumeId = v2, number = 4 + i, title = t, state = "done", content = SAMPLE_FILLER, wordCount = Words.count(SAMPLE_FILLER))) }
        val text = FakeData.manuscript.joinToString("\n") { it.replace("{", "").replace("}", "").removePrefix("[ai]") }
        val aiStart = text.indexOf("她看得清楚"); val aiEnd = text.indexOf("角。") + 2
        ch.insert(ChapterEntity(bookId = id, volumeId = v2, number = 8, title = "擂台余波", content = text, wordCount = Words.count(text), aiMarks = """[{"s":$aiStart,"e":$aiEnd,"k":"gen"}]"""))
        books.touch(id)
        id
    }

    companion object {
        const val KEEP_AUTO = 50
        private const val SAMPLE_FILLER = "（示例章节正文）山风过处，松涛如海。"
    }
}
