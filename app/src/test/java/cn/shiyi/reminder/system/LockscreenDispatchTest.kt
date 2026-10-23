package cn.shiyi.reminder.system

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockscreenDispatchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun enhancedModeRegistersUserVisibleSystemAlarm() {
        val manager = context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).setCanScheduleExactAlarms(true)
        val bridge = AlarmBridge(context)
        val at = System.currentTimeMillis() + 120_000
        bridge.schedule(at)
        assertTrue(bridge.useAlarmClock)
        assertEquals(at, manager.nextAlarmClock.triggerTime)
        assertNotNull(manager.nextAlarmClock.showIntent)
    }

    @Test fun replacingAndCancellingLeavesOnlyTheCurrentAlarm() {
        val manager = context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).setCanScheduleExactAlarms(true)
        val bridge = AlarmBridge(context)
        val at = System.currentTimeMillis() + 120_000
        bridge.schedule(at)
        bridge.schedule(at + 60_000)
        assertEquals(at + 60_000, manager.nextAlarmClock.triggerTime)
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        bridge.schedule(null)
        assertNull(manager.nextAlarmClock)
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }

    @Test fun revokedExactPermissionUsesVisibleDegradedMode() {
        val manager = context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).setCanScheduleExactAlarms(false)
        val bridge = AlarmBridge(context)
        bridge.schedule(System.currentTimeMillis() + 120_000)
        assertNull(manager.nextAlarmClock)
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        assertTrue(bridge.diagnostics.report().contains("可能延迟"))
    }

    @Test fun disablingEnhancedModeKeepsAnExactWakeupAlarm() {
        val manager = context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).setCanScheduleExactAlarms(true)
        val bridge = AlarmBridge(context)
        bridge.useAlarmClock = false
        bridge.schedule(System.currentTimeMillis() + 120_000)
        assertNull(manager.nextAlarmClock)
        assertEquals(AlarmManager.RTC_WAKEUP, shadowOf(manager).scheduledAlarms.single().type)
    }

    @Test fun cpuLeaseIsHeldDuringWorkAndReleasedAfterSuccess() {
        WakeLease.acquire(context).use {
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        }
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun cpuLeaseIsReleasedWhenWorkFails() {
        runCatching {
            WakeLease.acquire(context).use {
                assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
                error("simulated dispatch failure")
            }
        }
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun diagnosticsKeepOnlyTheLatestFortyEvents() {
        val diagnostics = ReminderDiagnostics(context)
        repeat(45) { diagnostics.record("测试", "event-$it;") }
        val report = diagnostics.report()
        assertFalse(report.contains("event-0;"))
        assertTrue(report.contains("event-44;"))
        assertEquals(40, Regex("event-[0-9]+;").findAll(report).count())
    }
}
