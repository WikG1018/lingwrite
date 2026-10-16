// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.Mirror
import com.lingwrite.app.data.Updater
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/** 更新源：指向公开仓库 WikG1018/lingwrite 的 Releases API；解析、镜像回退、.sha256 兜底。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class UpdaterSourceTest {
    private val sha = "c9e38b8f8b6e7eac4ab7f30c0073b17d0c5aa8a4b4d4417f3f0ac3afba1a8196"

    private fun json(base: String, body: String) = """{"tag_name":"v0.3.0","published_at":"2026-11-01T00:00:00Z","body":${kotlinx.serialization.json.JsonPrimitive(body)},
        "assets":[{"name":"lingwrite-v0.3.0.apk.sha256","size":88,"browser_download_url":"$base/x.apk.sha256"},{"name":"lingwrite-v0.3.0.apk","size":123,"browser_download_url":"$base/x.apk"}]}"""

    @Test fun pointsAtPublicRepo() {
        // v0.2.12 及以前内置的也是这个仓库：公开并发版后旧版本可直接检查到更新
        assertEquals("WikG1018/lingwrite", Updater.REPO)
        assertEquals("https://api.github.com", Updater.githubApi)
    }

    @Test fun parsesReleaseNotesFormat() {
        val r = Updater.parse(json("https://github.com/WikG1018/lingwrite/releases/download/v0.3.0", "## 灵写 v0.3.0\n\nSHA-256: `$sha`\n"))!!
        assertEquals("0.3.0", r.version); assertEquals(sha, r.sha256); assertEquals(123L, r.size)
        assertTrue("选 .apk 而不是 .apk.sha256", r.apkUrl.endsWith("/x.apk"))
        // 英文冒号/全角冒号/无反引号都能取到
        assertEquals(sha, Updater.parse(json("https://h", "SHA256：$sha"))!!.sha256)
        assertNull(Updater.parse("""{"tag_name":"v1","body":"","assets":[]}"""))
    }

    @Test fun ghFailsThenMirrorAndShaFile() = runBlocking {
        val s = MockWebServer(); val seen = mutableListOf<String>()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!; seen += p
                return when {
                    p.startsWith("/gh/") -> MockResponse().setResponseCode(403)
                    p.startsWith("/m2/") && p.endsWith("/repos/WikG1018/lingwrite/releases/latest") -> MockResponse().setBody(json(s.url("/dl").toString().trimEnd('/'), "无校验和"))
                    p.startsWith("/m2/") && p.endsWith("x.apk.sha256") -> MockResponse().setBody("$sha  lingwrite-v0.3.0.apk\n")
                    else -> MockResponse().setResponseCode(502)
                }
            }
        }
        s.start()
        val oldApi = Updater.githubApi; val oldM = Updater.mirrors
        try {
            Updater.githubApi = s.url("/gh").toString().trimEnd('/')
            Updater.mirrors = listOf(Mirror("m1", "镜像一", s.url("/m1/").toString()), Mirror("m2", "镜像二", s.url("/m2/").toString()))
            val (r, src) = Updater.latest("auto", "")
            assertEquals("镜像二", src)
            assertEquals(sha, r!!.sha256)
            assertTrue(r.apkUrl.contains("/m2/"))
            assertTrue(seen.first().startsWith("/gh/repos/WikG1018/lingwrite/releases/latest"))
            // 指定镜像时先走镜像、最后才回 GitHub
            assertEquals(listOf("镜像一", "GitHub"), Updater.sources("m1", "").map { it.first })
        } finally { Updater.githubApi = oldApi; Updater.mirrors = oldM; s.shutdown() }
    }
}
