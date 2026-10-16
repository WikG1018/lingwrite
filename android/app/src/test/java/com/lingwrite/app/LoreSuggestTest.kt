// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.FakeLlmRegistryHelper
import com.lingwrite.app.data.IssueFix
import com.lingwrite.app.data.IssueFixes
import com.lingwrite.app.data.LoreSuggest
import com.lingwrite.app.data.PayoffIdea
import com.lingwrite.app.data.PayoffIdeas
import com.lingwrite.app.data.db.ForeshadowEntity
import com.lingwrite.app.data.db.IssueEntity
import com.lingwrite.app.data.db.LoreSuggestionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** M4：A2 设定整理采纳、A6 给改法、A8 伏笔回收的数据效果（ai-features §2/§6/§8）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class LoreSuggestTest {
    private var bookId = 0L
    private var chapterId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        com.lingwrite.app.data.ai.KeyVault.override = ({ s: String -> s } to { s: String -> s })
        runBlocking {
            // AiService 需要一个可用的服务商才能调用 provider（FakeLlmRegistry 拦截实际网络）
            AppGraph.db.ai().insertProvider(com.lingwrite.app.data.db.ProviderEntity(
                presetId = "custom", name = "测", protocol = "openai", baseUrl = "http://x", auth = "bearer",
                models = "m1", keyCipher = "key",
            ))
            bookId = AppGraph.books.createBook("设定整理测试", "仙侠")
            chapterId = AppGraph.db.chapters().chaptersOnce(bookId).first().id
            AppGraph.books.saveContent(chapterId, "沈砚把断剑收进袖里。旧案的卷宗被风吹开，露出一个陌生名字：白先生。")
        }
    }

    @Test fun adoptNewCardAndState() = runBlocking {
        // new_card：采纳后设定库多 1 张卡
        val s1 = LoreSuggestionEntity(
            bookId = bookId, chapterId = chapterId, number = 1, kind = "new_card",
            payload = """{"type":"character","name":"白先生","value":"神秘引路人","why":"旧案关键人物"}""",
            quote = "白先生",
        )
        AppGraph.db.loreSuggest().insert(listOf(s1))
        val pending = AppGraph.db.loreSuggest().pending(bookId).first()
        assertEquals(1, pending.size)
        assertTrue(LoreSuggest.adopt(pending.single().id))
        val cards = AppGraph.db.lore().cardsOnce(bookId)
        assertTrue("采纳 new_card 后应多一张卡", cards.any { it.name == "白先生" })
        assertTrue("采纳后移出待处理列表", AppGraph.db.loreSuggest().pending(bookId).first().isEmpty())

        // state：采纳后人物卡的按章状态多 1 条
        val card = AppGraph.db.lore().cardsOnce(bookId).first { it.name == "白先生" }
        val s2 = LoreSuggestionEntity(
            bookId = bookId, chapterId = chapterId, number = 1, kind = "state", cardId = card.id,
            payload = """{"field":"境界","value":"金丹","why":"旧案线索"}""",
        )
        AppGraph.db.loreSuggest().insert(listOf(s2))
        val s2Id = AppGraph.db.loreSuggest().pending(bookId).first().single().id
        assertTrue(LoreSuggest.adopt(s2Id))
        val states = AppGraph.db.lore().statesForBook(bookId).filter { it.cardId == card.id }
        assertEquals(1, states.size)
        assertEquals("金丹", states.single().value)
    }

    @Test fun ignoreAndReappear() = runBlocking {
        AppGraph.db.loreSuggest().insert(listOf(LoreSuggestionEntity(
            bookId = bookId, chapterId = chapterId, number = 1, kind = "alias", cardId = 1,
            payload = """{"value":"清禾"}""", quote = "沈清禾",
        )))
        val id = AppGraph.db.loreSuggest().pending(bookId).first().single().id
        LoreSuggest.ignore(id)
        assertTrue("忽略的条目不应再次出现", AppGraph.db.loreSuggest().pending(bookId).first().isEmpty())
    }

    @Test fun issueFixGenerateAndCache() = runBlocking {
        val i = IssueEntity(bookId = bookId, chapterId = chapterId, number = 1, kind = "persona",
            quote = "沈砚把断剑收进袖里", basis = "设定：沈砚畏高", explain = "他明明畏高却主动登顶")
        val id = AppGraph.db.checks().insertIssue(i)
        val full = AppGraph.db.checks().chapterIssues(chapterId).first().first()
        // 用假 provider 生成改法并缓存到 issues.fix
        FakeLlmRegistryHelper.withReply(
            """[{"type":"text","before":"沈砚把断剑收进袖里","after":"沈砚迟疑片刻，还是把断剑收进袖里","why":"补一个迟疑，交代畏高在挣扎"}]""",
        ) {
            val fixes = IssueFixes.generate(full)
            assertEquals(1, fixes.size)
            assertEquals("text", fixes.single().type)
            val cached = AppGraph.db.checks().chapterIssues(chapterId).first().first()
            assertTrue("改法应缓存到 issues.fix", cached.fix.contains("沈砚迟疑"))
            assertEquals(1, IssueFixes.parseFixes(cached.fix).size)
        }
        // 采纳后问题变成 fixed
        IssueFixes.markFixed(AppGraph.db.checks().chapterIssues(chapterId).first().first())
        assertEquals("fixed", AppGraph.db.checks().chapterIssues(chapterId).first().first().status)
    }

    @Test fun foreshadowPayoffIdeas() = runBlocking {
        val f = ForeshadowEntity(bookId = bookId, content = "断剑的来历", chapterId = chapterId,
            plantNumber = 1, quote = "断剑收进袖里", payoff = 8)
        val fid = AppGraph.db.checks().insertForeshadow(f)
        val full = AppGraph.db.checks().foreshadow(fid)!!
        FakeLlmRegistryHelper.withReply(
            """[{"chapter":8,"how":"宗门大比上断剑认主","setup":"提前让剑鸣响两次"},{"chapter":10,"how":"沈清禾当众道破来历","setup":""},{"chapter":12,"how":"旧案卷宗现形","setup":"先出现半页残卷"}]""",
        ) {
            val ideas = PayoffIdeas.generate(full)
            assertEquals(3, ideas.size)
            assertEquals(8, ideas[0].chapter)
            val cached = AppGraph.db.checks().foreshadow(fid)!!
            assertTrue(cached.payoffIdeas.contains("宗门大比"))
            assertEquals(3, PayoffIdeas.parseIdeas(cached.payoffIdeas).size)
        }
    }
}
