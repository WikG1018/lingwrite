// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.i18n

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 界面语言（per-app language）。
 * - Android 13+：交给系统 [LocaleManager]，系统设置「应用语言」里也能改，二者同步。
 * - Android 12 / 12L：没有系统接口，存在本地 SharedPreferences，[MainActivity] 在 attachBaseContext 时套用。
 * 可选值见 res/xml/locales_config.xml：zh-CN（默认）、en、zh-TW、ja；空串 = 跟随系统。
 */
object AppLocale {
    val supported = listOf("zh-CN", "en", "zh-TW", "ja")
    private const val PREFS = "app_locale"

    /** 当前手动选择的语言标签；"" 表示跟随系统。 */
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        else ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tag", "").orEmpty()

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tag", tag).commit()
            activity.recreate()
        }
    }

    /** Android 12 / 12L：给 Activity 套上手动选择的语言。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tag", "").orEmpty()
        if (tag.isBlank()) return base
        val cfg = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(cfg)
    }

    /** 界面实际使用的语言（解析后）。 */
    fun uiLocale(): Locale = L10n.context().resources.configuration.locales[0] ?: Locale.SIMPLIFIED_CHINESE
}

object L10n {
    /** 最近一个带界面语言配置的 Context（Activity 优先，其次 Application）。 */
    @Volatile var ctx: Context? = null

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun context(): Context = ctx ?: runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
    }.getOrNull() ?: error("L10n: no context")
}

/** 取界面字符串。非 Composable 场景也能用；切换语言后 Activity 重建，界面会重新取值。 */
fun tr(@StringRes id: Int, vararg args: Any?): String {
    val r = L10n.context().resources
    return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
}

/** 数据层兜底：纯 JVM（无 Android Context）取不到资源时返回给定文案，保证单测与降级路径可用。 */
fun trOr(@StringRes id: Int, fallback: String, vararg args: Any?): String =
    runCatching { tr(id, *args) }.getOrElse { fallback }

/** AI 提示词片段：始终用简体中文（默认资源），不随界面语言变化。 */
fun trZh(@StringRes id: Int, vararg args: Any?): String {
    val r = zhResources()
    return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
}

@Volatile private var zhRes: android.content.res.Resources? = null
private fun zhResources(): android.content.res.Resources = zhRes ?: L10n.context().let { c ->
    val cfg = Configuration(c.resources.configuration).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
    c.createConfigurationContext(cfg).resources.also { zhRes = it }
}
