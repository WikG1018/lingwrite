// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.ForeshadowEntity
import com.lingwrite.app.ui.routes.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp

class AboutShotTest : RouteShotBase() {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val sent = mutableListOf<Pair<NotifyType, String>>()

    override suspend fun extraSeed(bookId: Long) {
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId = bookId, content = "铜钱", plantNumber = 1, payoff = 0))
        Notifier.poster = { _, t, _, _, r -> sent += t to r }
    }

    @Test fun notifyRules() = runBlocking {
        assertFalse(Notifier.post(ctx, NotifyType.Tasks, "t", "x", "a"))  // 未授权
        shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(Notifier.post(ctx, NotifyType.Tasks, "t", "x", "a"))
        AppGraph.prefs.update { it.copy(notifyTasks = false) }
        assertFalse(Notifier.post(ctx, NotifyType.Tasks, "t", "x", "a"))
        assertTrue(Notifier.post(ctx, NotifyType.Update, "t", "x", "update"))
        AppGraph.prefs.update { it.copy(notifyAll = false) }
        assertFalse(Notifier.post(ctx, NotifyType.Update, "t", "x", "update"))
        AppGraph.prefs.update { it.copy(notifyAll = true) }
        sent.clear()
        assertEquals(1, Notifier.foreshadowDigest(ctx))
        assertEquals(NotifyType.Foreshadow to "work/$bookId", sent.single())
        assertEquals(0, Notifier.foreshadowDigest(ctx))  // 每天一次
        Notifier.consume(Intent().putExtra(Notifier.EXTRA_ROUTE, "issues/$bookId"))
        assertEquals("issues/$bookId", Notifier.pending.value)
        AppGraph.prefs.update { it.copy(notifyTasks = true) }
        val ch = AppGraph.db.chapters().chaptersOnce(bookId).first()
        TaskHooks.done(ch.id, 2)
        assertEquals(NotifyType.Tasks to "issues/$bookId", sent.last())
    }

    @Test fun aboutShot() { Updater.available.value = Release("0.2.0", "2026-10-10", "", "", 0, null); shoot("26-about") { AboutRoute(LocalNav.current) } }
    @Test fun notifyShot() = shoot("27-notifications") { NotificationsRoute(LocalNav.current) }
    @Test fun deniedShot() {
        runBlocking { AppGraph.prefs.update { it.copy(notifyAsked = true) } }
        shoot("28-notify-denied") { NotificationsRoute(LocalNav.current) }
    }
    @Test fun privacyShot() = shoot("29-privacy") { Page("隐私说明") { androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp)); PrivacyCards(); LicenseList() } }
}
