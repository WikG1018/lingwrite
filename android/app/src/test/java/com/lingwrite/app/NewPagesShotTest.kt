// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.SeriesStore
import com.lingwrite.app.data.db.CallLogEntity
import com.lingwrite.app.data.db.ForeshadowEntity
import com.lingwrite.app.data.db.ReadMetricEntity
import com.lingwrite.app.i18n.L10n
import com.lingwrite.app.ui.routes.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v0.4–v0.6 新页面截图（审查 X1）：AI 体检、导入、发布前检查、追读、系列、事件轴，
 * 以及速查 / 朗读 / 写作流程三个面板。中文出浅色 + 深色，英文 / 日文各出一套检查文案。
 */
abstract class NewPagesShotBase(private val lang: String) : RouteShotBase() {
    private var chapterId = 0L
    private var seriesId = 0L

    @Before fun resetLocale() { L10n.ctx = null }

    override suspend fun extraSeed(bookId: Long) {
        val db = AppGraph.db
        val chapters = db.chapters().headersOnce(bookId)
        chapterId = chapters.first().id
        listOf(1 to 92, 2 to 81, 3 to 77, 4 to 58, 5 to 66, 6 to 63).forEach { (n, pct) ->
            db.readStats().insert(ReadMetricEntity(bookId = bookId, chapterNumber = n, reads = 12000 - n * 1300, completion = pct, favorites = 400 - n * 30))
        }
        chapters.take(3).forEachIndexed { i, ch ->
            repeat(3 - i) { db.ai().insertLog(CallLogEntity(bookId = bookId, chapterId = ch.id, provider = "p", model = "m", task = "continue", outcome = "accepted")) }
            db.ai().insertLog(CallLogEntity(bookId = bookId, chapterId = ch.id, provider = "p", model = "m", task = "polish", outcome = "rejected"))
        }
        db.checks().insertForeshadow(ForeshadowEntity(bookId = bookId, content = "师父留下的半块玉佩", chapterId = chapters[0].id, plantNumber = 1, quote = "玉佩缺了一角", payoff = 5))
        db.checks().insertForeshadow(ForeshadowEntity(bookId = bookId, content = "藏经阁第三层为何上锁", chapterId = chapters.getOrNull(1)?.id, plantNumber = 2, payoff = 8))
        seriesId = SeriesStore.create("长夜宇宙")
        SeriesStore.addBook(seriesId, bookId)
    }

    private fun shot(name: String, screen: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) =
        shoot(if (lang == "zh") name else "$name-$lang", screen = screen, content = content)

    @Test fun aiUsage() = shot("70-ai-usage") { AiUsageRoute(bookId, LocalNav.current) }
    @Test fun importPage() = shot("71-import") { ImportRoute(LocalNav.current) }
    @Test fun publishCheck() = shot("72-publish-check") { PublishCheckRoute(bookId, 0L, LocalNav.current) }
    @Test fun readStats() = shot("73-read-stats") { ReadStatsRoute(bookId, LocalNav.current) }
    @Test fun seriesList() = shot("74-series") { SeriesListRoute(LocalNav.current) }
    @Test fun seriesDetail() = shot("75-series-detail") { SeriesDetailRoute(seriesId, LocalNav.current) }
    @Test fun timeline() = shot("76-timeline") { TimelineRoute(bookId, LocalNav.current) }
    @Test fun quickRef() = shot("77-quick-ref", screen = true) {
        Box(Modifier.fillMaxSize().background(Ling.colors.background)) { EditorQuickRef(bookId, chapterId, 3, "", {}, {}) }
    }
    @Test fun tts() = shot("78-tts", screen = true) {
        Box(Modifier.fillMaxSize().background(Ling.colors.background)) { TtsSheet("夜雨落在山门前。", 0, 1f, bookId) {} }
    }
    @Test fun writeFlow() = shot("79-write-flow", screen = true) {
        Box(Modifier.fillMaxSize().background(Ling.colors.background)) { WriteFlowSheet(bookId, chapterId, 1, {}, {}, {}) }
    }
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class NewPagesShotTest : NewPagesShotBase("zh")

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class NewPagesShotEnTest : NewPagesShotBase("en")

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w412dp-h915dp-xhdpi", application = android.app.Application::class)
class NewPagesShotJaTest : NewPagesShotBase("ja")
