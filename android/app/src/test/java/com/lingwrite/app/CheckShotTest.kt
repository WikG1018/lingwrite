// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.KeyVault
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.routes.ForeshadowList
import com.lingwrite.app.ui.routes.IssueCard
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

fun sse(text: String) = MockResponse().setBody("data: " + buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", text) } } } } + "\n\ndata: [DONE]\n\n")

/** 用 MockWebServer 充当自动任务模型。 */
suspend fun withMockModel(vararg replies: String, block: suspend (MockWebServer) -> Unit) {
    KeyVault.override = ({ s: String -> "enc:$s" } to { s: String -> s.removePrefix("enc:") })
    val s = MockWebServer(); replies.forEach { s.enqueue(sse(it)) }; s.start()
    val id = AppGraph.db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "测", protocol = "openai", baseUrl = s.url("/v1").toString(), auth = "bearer", models = "m1", keyCipher = KeyVault.encrypt("k")))
    AppGraph.prefs.update { it.copy(writingModel = "$id|m1", autoModel = "$id|m1") }
    try { block(s) } finally { s.shutdown(); KeyVault.override = null }
}

class CheckShotTest : RouteShotBase() {
    @Test fun logic() {
        val f = ForeshadowEntity(bookId = 1, content = "铜钱", plantNumber = 3, payoff = 10)
        assertEquals(FsState.Due, fsState(f, 8)); assertEquals(FsState.Overdue, fsState(f, 11)); assertEquals(FsState.Open, fsState(f, 2))
        assertEquals(FsState.Paid, fsState(f.copy(status = "paid"), 30))
        assertEquals(3, locate("他说：沈砚已经筑基了。", "沈砚已经筑基")); assertEquals(3, locate("他说：沈砚已经筑基了。", "“沈砚已经筑基了啊”"))
        val intended = listOf(IssueEntity(bookId = 1, chapterId = 1, number = 1, kind = "persona", quote = "沈砚笑着说谎", basis = "", explain = "", status = "intended"))
        assertTrue(suppressed("persona", "沈砚笑着说谎", intended)); assertFalse(suppressed("time", "沈砚笑着说谎", intended))
    }

    @Test fun checkPipeline() = runBlocking {
        val ch = AppGraph.db.chapters().chaptersOnce(bookId).first { it.content.isNotBlank() }
        val q = ch.content.trim().take(8)
        withMockModel(
            """[{"who":"主角","claim":"已筑基","quote":"$q"}]""",
            """好的：[{"kind":"power","quote":"$q","basis":"设定：第 ${ch.number} 章时境界为炼气","explain":"境界提前了"}]""",
        ) { s ->
            assertEquals(1, Consistency.check(ch.id))
            val i = AppGraph.db.checks().intended(bookId)
            assertTrue(i.isEmpty())
            assertTrue(s.takeRequest().body.readUtf8().contains("抽取"))
            assertTrue(s.takeRequest().body.readUtf8().contains("设定与前情"))
        }
    }

    @Test fun ledgerShot() {
        val fs = listOf(
            ForeshadowEntity(1, 1, "铜钱背面的“砚”字——师父早知道沈砚会接手此案", plantNumber = 3, quote = "背面刻着一个极小的“砚”字", payoff = 40, cardId = 1),
            ForeshadowEntity(2, 1, "林晚的药方少了一味药，她父亲的病另有隐情", plantNumber = 11, quote = "这方子缺了当归", payoff = 46),
            ForeshadowEntity(3, 1, "顾长风左手从不离开袖子", plantNumber = 27, payoff = 80),
            ForeshadowEntity(4, 1, "城门守卫换班时间被改", plantNumber = 41, quote = "今儿个换班提前了半个时辰", pending = true))
        shoot("16-foreshadow") {
            Column(Modifier.fillMaxSize().background(Ling.colors.background)) {
                LingTopBar("长夜问刀", "伏笔", onBack = {})
                Column(Modifier.verticalScroll(rememberScrollState())) { ForeshadowList(fs, 44, mapOf(1L to "沈砚"), 0, {}, {}, {}, {}) }
            }
        }
    }

    @Test fun issueShot() = shoot("17-issues") {
        Column(Modifier.fillMaxSize().background(Ling.colors.background)) {
            LingTopBar("一致性检查", "2 条待处理", onBack = {})
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IssueCard(IssueEntity(1, 1, 1, 42, "power", "沈砚一刀劈开了金丹修士的护体罡气", 0, "设定：沈砚第 42 章时境界=筑基初期（第 30 章起）", "筑基初期正面破开金丹护体，与等级体系不符"), onJump = {}, onIntended = {}, onIgnore = {})
                IssueCard(IssueEntity(2, 1, 1, 42, "time", "三年前他就认识林晚", 0, "第 11 章摘要：沈砚与林晚初次见面", "第 11 章才初识，时间线矛盾"), onJump = {}, onIntended = {}, onIgnore = {})
                IssueCard(IssueEntity(3, 1, 1, 40, "persona", "沈砚笑嘻嘻地讨好上司", 0, "性格：沉默、执拗", "与性格不符", status = "intended", note = "他在演戏，为了拿到卷宗"), onJump = null, onIntended = {}, onIgnore = {})
            }
        }
    }
}
