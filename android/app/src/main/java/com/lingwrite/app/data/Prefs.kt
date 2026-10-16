// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.store by preferencesDataStore("lingwrite_prefs")

/** 全部偏好序列化为一个 JSON，字段增删无需迁移。 */
@Serializable
data class AppPrefs(
    val onboarded: Boolean = false,
    /** assist / generate */
    val writeMode: String = "assist",
    val autoCheck: Boolean = true,
    val semanticSearch: Boolean = false,
    val contextBudget: Int = 128_000,
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** auto / github / 镜像 id / custom */
    val mirror: String = "auto",
    val customMirror: String = "",
    /** 用户选择跳过的版本号 */
    val skippedVersion: String = "",
    /** 通知总开关 */
    val notifyAll: Boolean = true,
    /** 上次推送伏笔提醒的时间 */
    val lastFsNotify: Long = 0,
    val notifyTasks: Boolean = true,
    val notifyForeshadow: Boolean = true,
    val notifyUpdate: Boolean = true,
    val notifyAsked: Boolean = false,
    /** 空 = 使用默认后台文风提示词 */
    val stylePrompt: String = "",
    val styleEnabled: Boolean = true,
    val bannedWords: String = DEFAULT_BANNED,
    /** sentence / paragraph / long */
    val continueLength: String = "paragraph",
    /** system / light / dark */
    val theme: String = "system",
    /** 减弱动画：system（跟随系统）/ on / off（MO3） */
    val reduceMotion: String = "system",
    /** 编辑器模式提示条是否已显示（modes.md §3.5） */
    val modeTipShown: Boolean = false,
    /** 本章便签首次提示 */
    val notesTipShown: Boolean = false,
    /** 旧用户默认关闭；新用户完成首次设置后开启。进度在跳过/旋转后保留。 */
    val authorGuide: Boolean = false,
    val authorGuideStep: Int = 0,
    val authorGuideInviteRevision: Int = 0,
    /** 光标停留 3 分钟没有新内容时提示一次卡文急救（A5） */
    val stuckHint: Boolean = true,
    /** "providerId|model" */
    val writingModel: String = "",
    val autoModel: String = "",
    val candidates: Int = 3,
    /** 语义检索向量模型 "providerId|model" */
    val embedModel: String = "",
) {
    companion object {
        const val DEFAULT_BANNED = "不禁,宛如,仿佛,一抹,嘴角勾起一抹,眼中闪过一丝,心中一凛,倒吸一口凉气,不由得,深邃,璀璨,与此同时,值得一提的是,总而言之,命运的齿轮,空气仿佛凝固了"
    }
}

class Prefs(private val context: Context) {
    private val key = stringPreferencesKey("prefs_json")
    private fun decode(s: String?) = s?.let { runCatching { LingJson.decodeFromString(AppPrefs.serializer(), it) }.getOrNull() } ?: AppPrefs()
    @Volatile var snapshot: AppPrefs? = null
        private set
    val flow: Flow<AppPrefs> = context.store.data.map { decode(it[key]).also { value -> snapshot = value } }
    suspend fun update(f: (AppPrefs) -> AppPrefs) {
        val saved = context.store.edit { p -> p[key] = LingJson.encodeToString(AppPrefs.serializer(), f(decode(p[key]))) }
        snapshot = decode(saved[key])
    }
}
