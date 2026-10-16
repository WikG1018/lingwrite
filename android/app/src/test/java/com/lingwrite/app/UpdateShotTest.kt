// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lingwrite.app.data.Mirror
import com.lingwrite.app.data.Release
import com.lingwrite.app.data.Updater
import com.lingwrite.app.ui.routes.LocalNav
import com.lingwrite.app.ui.routes.UpdateContent
import com.lingwrite.app.ui.routes.UpdateRoute
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class UpdateShotTest : RouteShotBase() {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun releaseJson(url: String, withSha: Boolean = true) = """{"tag_name":"v0.2.0","published_at":"2026-10-10T08:00:00Z",
        "body":"## 新增\n- 应用内更新\n${if (withSha) "SHA-256: $sha" else ""}","assets":[{"name":"lingwrite-0.2.0.apk","size":${apk.size},"browser_download_url":"$url"}]}"""

    @Test fun versions() {
        assertTrue(Updater.newer("v0.2.0", "0.1.9")); assertTrue(Updater.newer("1.0", "0.9.9"))
        assertFalse(Updater.newer("0.1.0", "0.1.0")); assertFalse(Updater.newer("0.1.0", "0.2"))
        val r = Updater.parse(releaseJson("https://x/a.apk"))!!
        assertEquals("0.2.0", r.version); assertEquals(sha, r.sha256); assertEquals("2026-10-10", r.date)
        assertEquals(listOf("GitHub"), Updater.sources("github", "").map { it.first })
        assertEquals("https://m.example/https://a", Updater.sources("custom", "https://m.example/").single().second("https://a"))
    }

    @Test fun fallbackDownloadVerify() = runBlocking {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/mirror/") && p.contains("releases/latest") -> MockResponse().setBody(releaseJson(s.url("/dl/a.apk").toString()))
                    p.startsWith("/mirror/") && p.endsWith("a.apk") -> MockResponse().setBody(Buffer().write(apk))
                    else -> MockResponse().setResponseCode(503)
                }
            }
        }
        s.start()
        val oldApi = Updater.githubApi; val oldM = Updater.mirrors
        try {
            Updater.githubApi = s.url("/gh").toString().trimEnd('/')
            Updater.mirrors = listOf(Mirror("m", "测试镜像", s.url("/mirror/").toString()))
            val (r, src) = Updater.latest("auto", "")
            assertEquals("测试镜像", src)
            assertTrue(r!!.apkUrl.contains("/mirror/"))
            var last = 0f
            val f = Updater.download(ctx, r) { last = it }
            assertEquals(1f, last); assertEquals(apk.size.toLong(), f.length())
            Updater.signerOf = { _, _ -> setOf("aa") }; Updater.selfSigners = { setOf("aa") }
            assertEquals(Updater.Verdict.Ok, Updater.verify(ctx, f, r))
            Updater.signerOf = { _, _ -> setOf("bb") }
            val v = Updater.verify(ctx, f, r) as Updater.Verdict.Blocked
            assertEquals("安装包签名与当前版本不一致，已阻止安装", v.reason); assertFalse(f.exists())
            val f2 = Updater.download(ctx, r) {}
            assertTrue(Updater.verify(ctx, f2, r.copy(sha256 = "0".repeat(64))) is Updater.Verdict.Blocked); assertFalse(f2.exists())
        } finally { Updater.githubApi = oldApi; Updater.mirrors = oldM; s.shutdown() }
    }

    @Test fun routeShot() {
        Updater.available.value = null
        shoot("24-update") { UpdateRoute(LocalNav.current) }
    }

    @Test fun sheetShot() {
        val r = Release("0.2.0", "2026-10-10", "## 新增\n- **应用内更新**：GitHub 不通时自动走镜像\n- 关于页与通知开关\n## 修复\n- 导出 Markdown 时分卷标题重复", "", 18_600_000, sha)
        shoot("25-update-sheet") {
            Page("更新") {
                Spacer(Modifier.height(16.dp))
                UpdateContent(r, null, "安装包签名与当前版本不一致，已阻止安装", {}, {}, {}, {})
            }
        }
    }
}
