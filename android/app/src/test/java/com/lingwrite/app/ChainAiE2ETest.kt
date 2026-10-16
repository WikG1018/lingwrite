// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.ChainPrompts
import com.lingwrite.app.data.ChainRunner
import com.lingwrite.app.data.ChainStore
import com.lingwrite.app.data.SynopsisFormat
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.data.ai.ChatRequest
import com.lingwrite.app.data.ai.FakeLlmRegistry
import com.lingwrite.app.data.ai.LlmEvent
import com.lingwrite.app.data.ai.LlmProvider
import com.lingwrite.app.data.db.BookPlanEntity
import com.lingwrite.app.data.encodeBeats
import com.lingwrite.app.data.parseBeats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * M3 §7 端到端（outline-chain.md §7）：用假 provider 依次返回 ①–⑥ 的结果，
 * 走 ChainRunner（UI 调用的同一套函数：提示词 → 真重试解析 → 采纳写库），断言全链路数据。
 * 说明：这里覆盖的是「AI 返回 → 解析 → 采纳落库」；Compose 界面本身由截图测试覆盖。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChainAiE2ETest {
    private var bookId = 0L

    /** 按提示词关键字返回对应步骤的 JSON（模拟模型）。 */
    private class StepProvider : LlmProvider {
        val seen = mutableListOf<String>()
        override fun stream(req: ChatRequest) = flow {
            val user = req.messages.last().content
            seen += user
            val reply = when {
                // 用每步提示词里独有的措辞匹配；顺序也重要：③ 的上游里会带「卖点」，⑤ 的上游会带「章纲」字样
                user.contains("故事梗概") -> """[{"title":"问剑","logline":"断剑少年潜入宗门查旧案","hook":"旧案一线牵"},{"title":"青云","logline":"另一版","hook":"爽点"},{"title":"C","logline":"d","hook":"e"}]"""
                user.contains("分三段输出") -> """{"spine":"断剑少年查旧案","stages":[{"name":"外门","goal":"站稳脚跟","conflict":"同门倾轧","end":"卷入内门之争"}],"ending":"旧案大白，宗门重建"}"""
                user.contains("条场景节拍") -> """["300 字内抛出钩子：断剑共鸣","比试中藏拙，留一手","沈清禾旁观不语"]"""
                user.contains("开篇写法示意") -> """[{"text":"从断剑共鸣写起","effect":"开门见山"},{"text":"从执事唱名写起","effect":"悬念前置"},{"text":"从沈清禾的视线写起","effect":"侧写主角"}]"""
                user.contains("卷的卷纲") -> """[{"name":"第一卷 山门","goal":"站稳脚跟","climax":"外门夺魁","outline":"入外门，初露锋芒","chapters":60},{"name":"第2卷：大比","goal":"查旧案","climax":"断剑真相","outline":"宗门大比","chapters":55},{"name":"第三卷","goal":"北上","climax":"师徒对决","outline":"北地之乱","chapters":50}]"""
                user.contains("章的章纲") -> """[{"title":"外门比试","event":"外门比试","hook":"断剑共鸣","cliff":"有人认出剑"},{"title":"第 2 章 藏剑峰的雨","event":"夜探藏剑峰","hook":"雨夜来客","cliff":"剑鸣示警"},{"title":"第 3 章 下山之前","event":"领受任务","hook":"旧案卷宗","cliff":"名字出现"}]"""
                user.contains("条卖点") -> """["断剑设定有悬念","宗门权力斗争","复仇节奏快"]"""
                else -> "{}"
            }
            emit(LlmEvent.Delta(reply))
            emit(LlmEvent.Done(10, 20))
        }
    }

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        runBlocking {
            AppGraph.db.ai().insertProvider(com.lingwrite.app.data.db.ProviderEntity(
                presetId = "custom", name = "测", protocol = "openai", baseUrl = "http://x", auth = "bearer",
                models = "m1", keyCipher = "key",
            ))
        }
        com.lingwrite.app.data.ai.KeyVault.override = ({ s: String -> s } to { s: String -> s })
        FakeLlmRegistry.provider = StepProvider()
    }

    @Test fun fullChainFromIdeaToFirstChapter() = runBlocking {
        bookId = AppGraph.books.createBook("链路测试", "仙侠")
        val dao = AppGraph.db.plan()

        // ① 创意 → 梗概（采纳第 2 个）
        val pitches = ChainRunner.pitches(bookId, "断剑少年入青云查旧案", "仙侠", "起点")
        assertEquals(3, pitches.size)
        val pick = pitches[1]
        dao.upsertBook((dao.bookOnce(bookId) ?: BookPlanEntity(bookId)).copy(
            idea = "断剑少年入青云查旧案", logline = pick.second, chainStep = 1,
        ))
        ChainStore.markAdopted(bookId, 1)

        // ② 卖点
        val points = ChainRunner.sellingPoints(bookId)
        assertTrue(points.isNotEmpty())
        dao.upsertBook(dao.bookOnce(bookId)!!.copy(sellingPoints = com.lingwrite.app.data.encodeList(points), chainStep = 2))
        ChainStore.markAdopted(bookId, 2)

        // ③ 总纲
        val syn = ChainRunner.synopsis(bookId)
        if (syn == null) {
            val logs = AppGraph.db.ai().logs(null, 5).first().joinToString(" | ") { "${it.task}/${it.outcome}/${it.error}" }
            throw AssertionError("③ 总纲没解析出来，最近调用日志：$logs")
        }
        val synopsis = SynopsisFormat.build(syn.first, syn.second, syn.third)
        dao.upsertBook(dao.bookOnce(bookId)!!.copy(synopsis = synopsis, chainStep = 3))
        ChainStore.markAdopted(bookId, 3)
        assertTrue(synopsis.contains("主线：断剑少年查旧案"))

        // ④ 拆卷：4 个候选里采纳 3 卷（卷名不带编号）
        val vols = ChainRunner.volumes(bookId, 4)
        assertEquals(3, vols.size)
        assertEquals("山门", vols[0].name)
        assertEquals("大比", vols[1].name)
        assertEquals(3, ChainRunner.adoptVolumes(bookId, vols.take(3), overwrite = false))
        dao.upsertBook(dao.bookOnce(bookId)!!.copy(chainStep = 4))
        ChainStore.markAdopted(bookId, 4, dao.volumes(bookId).first())
        val realVols = AppGraph.db.chapters().volumesOnce(bookId).sortedBy { it.sort }
        // createBook 会先建一个空卷，所以采纳 3 卷后一共 4 卷
        assertEquals(4, realVols.size)
        realVols.forEach { assertFalse("卷名不该带编号: ${it.title}", it.title.startsWith("第")) }
        assertEquals("采纳的 3 卷都该有卷纲目标", 3, realVols.count { dao.volumeOnce(it.id)?.goal?.isNotBlank() == true })
        val targetVol = realVols.first { dao.volumeOnce(it.id)?.goal?.isNotBlank() == true }

        // ⑤ 章纲：第一卷前 3 章全部采纳（章名不带编号）
        val drafts = ChainRunner.chapters(bookId, targetVol.id, 3)
        assertEquals(3, drafts.size)
        assertEquals("外门比试", drafts[0].title)
        // 模型可能带「第 2 章」前缀，采纳时由 Naming.stripChapter 剥掉（下面断言库里没有编号）
        assertTrue("候选章名应含正文名", drafts[1].title.contains("藏剑峰的雨"))
        assertEquals(3, ChainRunner.adoptChapters(bookId, targetVol.id, drafts))
        dao.upsertBook(dao.bookOnce(bookId)!!.copy(chainStep = 5))
        ChainStore.markAdopted(bookId, 5, dao.volumes(bookId).first())
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        assertTrue(chs.size >= 4)
        chs.forEach { assertFalse("章名不该带编号: ${it.title}", it.title.startsWith("第")) }
        assertEquals("应正好 3 章有章纲", 3, chs.count { dao.chapterOnce(it.id)?.event?.isNotBlank() == true })

        // ⑥ 节拍（辅助模式：只进便签，正文不动）
        val first = chs.first()
        val beats = ChainRunner.beats(bookId, first.id)
        assertEquals(3, beats.size)
        val openings = ChainRunner.openings(bookId, beats)
        assertEquals(3, openings.size)
        ChainRunner.adoptBeats(bookId, first.id, beats, openings, assistMode = true)
        dao.upsertBook(dao.bookOnce(bookId)!!.copy(chainStep = 6))
        ChainStore.markAdopted(bookId, 6, dao.volumes(bookId).first())

        // 断言：链路数据齐、正文仍为空（辅助模式红线）、便签有节拍和开篇示意
        val bp = dao.bookOnce(bookId)!!
        assertEquals(6, bp.chainStep)
        assertTrue(bp.logline.isNotBlank())
        assertTrue(bp.synopsis.isNotBlank())
        assertTrue(bp.sellingPoints.contains("断剑"))
        assertEquals(3, parseBeats(dao.chapterOnce(first.id)!!.beats).size)
        assertEquals("", AppGraph.db.chapters().get(first.id)!!.content)
        val notes = AppGraph.db.notes().notes(first.id).first()
        assertTrue("辅助模式应有节拍 + 开篇示意便签", notes.size >= 4)
        assertTrue(notes.any { it.kind == "beat" })
        assertTrue(notes.count { it.kind == "direction" } >= 3)

        // call_logs 依次出现 wizard / outline 任务
        val logs = AppGraph.db.ai().logs(null, 100).first()
        assertTrue(logs.any { it.task == "wizard" })
        assertTrue(logs.any { it.task == "outline" })
    }

    /** 「上游已改」可达：采纳后改总纲，第 ④ 步的指纹就对不上。 */
    @Test fun upstreamChangedBecomesReachable() = runBlocking {
        bookId = AppGraph.books.createBook("上游测试")
        val dao = AppGraph.db.plan()
        dao.upsertBook(BookPlanEntity(bookId = bookId, idea = "旧创意", logline = "旧梗概", synopsis = "旧总纲", chainStep = 3))
        ChainStore.markAdopted(bookId, 3)
        ChainStore.markAdopted(bookId, 4)
        var bp = dao.bookOnce(bookId)!!
        assertFalse("没改上游时不该提示", ChainStore.changed(bp, emptyList(), 4))
        dao.upsertBook(bp.copy(synopsis = "改过的总纲"))
        bp = dao.bookOnce(bookId)!!
        assertTrue("改了总纲后第 ④ 步应显示上游已改", ChainStore.changed(bp, emptyList(), 4))
    }

    /** 候选缓存落库：生成后重新读出来还能继续用（未采纳的候选不丢）。 */
    @Test fun candidatesSurviveReload() = runBlocking {
        bookId = AppGraph.books.createBook("候选缓存")
        val list = ChainRunner.pitches(bookId, "一个捕快能看见寿命", "悬疑", "番茄")
        assertEquals(3, list.size)
        val raw = ChainStore.cands(bookId, 1)
        assertTrue("候选应写进 book_plans.chain", raw != null && raw.contains("问剑"))
        assertEquals(3, ChainPrompts.parsePitches(raw!!).size)
    }
}
