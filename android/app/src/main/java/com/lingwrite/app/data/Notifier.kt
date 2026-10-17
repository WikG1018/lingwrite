// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lingwrite.app.AppGraph
import com.lingwrite.app.MainActivity
import com.lingwrite.app.R
import com.lingwrite.app.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

object TaskHooks {
    /** 后台检查完成：章节 id、发现的问题数。 */
    var done: suspend (Long, Int) -> Unit = { _, _ -> }
}

enum class NotifyType(val channel: String, val titleRes: Int, val descRes: Int) {
    Tasks("tasks", R.string.notify_001, R.string.notify_002),
    Foreshadow("foreshadow", R.string.notify_003, R.string.notify_004),
    Update("update", R.string.notify_005, R.string.notify_006),
}

/** 本地通知：渠道、权限判断、深链。 */
object Notifier {
    const val EXTRA_ROUTE = "lingwrite.route"
    /** 点通知进来后待跳转的路由。 */
    val pending = MutableStateFlow<String?>(null)
    /** 测试可替换：实际发出通知。 */
    var poster: (Context, NotifyType, String, String, String) -> Unit = ::realPost

    fun consume(i: Intent?) { i?.getStringExtra(EXTRA_ROUTE)?.let { pending.value = it } }

    fun permissionGranted(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun enabled(p: AppPrefs, t: NotifyType) = p.notifyAll && when (t) { NotifyType.Tasks -> p.notifyTasks; NotifyType.Foreshadow -> p.notifyForeshadow; NotifyType.Update -> p.notifyUpdate }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        NotifyType.entries.forEach { t ->
            nm.createNotificationChannel(NotificationChannel(t.channel, tr(t.titleRes), if (t == NotifyType.Update) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr(t.descRes) })
        }
    }

    suspend fun post(ctx: Context, t: NotifyType, title: String, text: String, route: String): Boolean {
        if (!enabled(AppGraph.prefs.flow.first(), t) || !permissionGranted(ctx)) return false
        poster(ctx, t, title, text, route); return true
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun realPost(ctx: Context, t: NotifyType, title: String, text: String, route: String) {
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, route.hashCode(), Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, t.channel).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(route.hashCode(), n) }
    }

    /** 临期/超期伏笔汇总，每天最多一次。返回推送的条数。 */
    suspend fun foreshadowDigest(ctx: Context, now: Long = System.currentTimeMillis()): Int {
        val p = AppGraph.prefs.flow.first()
        if (now - p.lastFsNotify < 86_400_000L) return 0
        var sent = 0
        AppGraph.db.checks().allOpen().groupBy { it.bookId }.forEach { (bookId, fs) ->
            val book = AppGraph.db.books().get(bookId) ?: return@forEach
            val cur = AppGraph.db.chapters().maxNumberWithWords(bookId) ?: 1
            val over = fs.count { fsState(it, cur) == FsState.Overdue }; val due = fs.count { fsState(it, cur) == FsState.Due }
            if (over + due == 0) return@forEach
            val text = listOfNotNull(
                over.takeIf { it > 0 }?.let { tr(R.string.notify_009, it) },
                due.takeIf { it > 0 }?.let { tr(R.string.notify_010, it) },
            ).joinToString(tr(R.string.data_043))
            if (post(ctx, NotifyType.Foreshadow, tr(R.string.notify_007, book.title), tr(R.string.notify_008, text), "work/$bookId")) sent++
        }
        if (sent > 0) AppGraph.prefs.update { it.copy(lastFsNotify = now) }
        return sent
    }
}
