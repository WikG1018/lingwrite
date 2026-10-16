// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 一个可用的新版本。 */
data class Release(
    val version: String, val date: String, val body: String,
    val apkUrl: String, val size: Long, val sha256: String?,
)

data class Mirror(val id: String, val name: String, val prefix: String)

/** 应用内更新：GitHub Releases，失败时回退镜像；安装前校验签名与 SHA-256。 */
object Updater {
    const val REPO = "WikG1018/lingwrite"
    var githubApi = "https://api.github.com"
    var mirrors = listOf(Mirror("ghfast", "ghfast.top", "https://ghfast.top/"), Mirror("ghproxy", "gh-proxy.com", "https://gh-proxy.com/"))
    /** 测试可替换：返回 APK 签名证书的 SHA-256 集合；null 表示读不出。 */
    var signerOf: (Context, File) -> Set<String>? = ::apkSigners
    var selfSigners: (Context) -> Set<String>? = ::installedSigners
    val available = MutableStateFlow<Release?>(null)

    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** 按更新源生成依次尝试的地址包装器（GitHub 原地址 → 镜像前缀）。 */
    fun sources(mirror: String, custom: String): List<Pair<String, (String) -> String>> {
        val gh: Pair<String, (String) -> String> = "GitHub" to { u: String -> u }
        fun pre(name: String, p: String): Pair<String, (String) -> String> = name to { u: String -> p.trimEnd('/') + "/" + u }
        val ms = mirrors.map { pre(it.name, it.prefix) }
        return when (mirror) {
            "github" -> listOf(gh)
            "custom" -> if (custom.isBlank()) listOf(gh) else listOf(pre("自定义镜像", custom.trim()))
            "auto" -> listOf(gh) + ms
            else -> ms.filterIndexed { i, _ -> mirrors[i].id == mirror }.ifEmpty { ms } + gh
        }
    }

    fun newer(remote: String, local: String): Boolean {
        fun nums(v: String) = v.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = nums(remote); val b = nums(local)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    private val shaRe = Regex("""SHA-?256\s*[:：]?\s*`?([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)

    fun parse(json: String): Release? {
        val o = LingJson.parseToJsonElement(json).jsonObject
        val assets = o["assets"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val apk = assets.firstOrNull { it.str("name").endsWith(".apk") } ?: return null
        val body = o.str("body")
        return Release(
            version = o.str("tag_name").trimStart('v', 'V'), date = o.str("published_at").take(10), body = body,
            apkUrl = apk.str("browser_download_url"), size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0,
            sha256 = shaRe.find(body)?.groupValues?.get(1)?.lowercase(),
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute().use {
        if (!it.isSuccessful) error("HTTP ${it.code}"); it.body!!.string()
    }

    /** 取最新正式版；依次尝试各源。返回 (release, 使用的源名)。 */
    suspend fun latest(mirror: String, custom: String): Pair<Release?, String> = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for ((name, wrap) in sources(mirror, custom)) {
            val r = runCatching { parse(get(wrap("$githubApi/repos/$REPO/releases/latest"))) }
            r.onSuccess { rel ->
                val sha = rel?.sha256 ?: rel?.let { runCatching { get(wrap(it.apkUrl + ".sha256")).trim().take(64).lowercase().takeIf { s -> s.length == 64 } }.getOrNull() }
                return@withContext (rel?.copy(apkUrl = wrap(rel.apkUrl), sha256 = sha)) to name
            }
            last = r.exceptionOrNull()
        }
        throw last ?: IllegalStateException("没有可用的更新源")
    }

    /** 下载到 cache/updates，progress 0..1；协程取消即中止并删除半成品。 */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "lingwrite-${r.version}.apk")
        try {
            http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body!!; val total = body.contentLength().takeIf { it > 0 } ?: r.size
                body.byteStream().use { input -> f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var n = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val k = input.read(buf); if (k < 0) break
                        out.write(buf, 0, k); n += k
                        if (total > 0) progress((n.toFloat() / total).coerceIn(0f, 1f))
                    }
                } }
            }
            f
        } catch (t: Throwable) { f.delete(); throw t }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val k = i.read(b); if (k < 0) break; md.update(b, 0, k) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    sealed interface Verdict { data object Ok : Verdict; data class Blocked(val reason: String) : Verdict }

    /** 校验 SHA-256 与签名证书；不通过时删除安装包。 */
    fun verify(ctx: Context, f: File, r: Release): Verdict {
        val v = when {
            r.sha256 == null -> Verdict.Blocked("发布说明里没有 SHA-256，无法确认安装包完整，已阻止安装")
            sha256(f) != r.sha256 -> Verdict.Blocked("安装包 SHA-256 校验失败，可能下载不完整或被篡改，已阻止安装")
            else -> {
                val a = signerOf(ctx, f); val b = selfSigners(ctx)
                if (a.isNullOrEmpty() || b.isNullOrEmpty() || a != b) Verdict.Blocked("安装包签名与当前版本不一致，已阻止安装") else Verdict.Ok
            }
        }
        if (v is Verdict.Blocked) f.delete()
        return v
    }

    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun apkSigners(ctx: Context, f: File): Set<String>? =
        ctx.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.toSet()

    private fun installedSigners(ctx: Context): Set<String>? = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.toSet()
    }.getOrNull()

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, f: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun sizeText(b: Long) = if (b <= 0) "—" else if (b >= 1 shl 20) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"

    /** 启动检查：开启且距上次超过一天。返回新版本（未被跳过）。 */
    suspend fun autoCheck(now: Long = System.currentTimeMillis(), current: String): Release? {
        val p = com.lingwrite.app.AppGraph.prefs.flow.first()
        if (!p.autoUpdateCheck || now - p.lastUpdateCheck < 86_400_000L) return null
        val r = runCatching { latest(p.mirror, p.customMirror).first }.getOrNull()
        com.lingwrite.app.AppGraph.prefs.update { it.copy(lastUpdateCheck = now) }
        val n = r?.takeIf { newer(it.version, current) } ?: return null
        available.value = n
        return n.takeIf { it.version != p.skippedVersion }
    }
}
