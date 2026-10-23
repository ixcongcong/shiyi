package cn.shiyi.reminder.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.shiyi.reminder.ShiyiApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ShiyiApp
        val wake = WakeLease.acquire(context)
        app.diagnostics.record(if (intent.action == AlarmBridge.TICK) "闹钟回调" else "通知操作")
        app.scope.launch {
            try {
                withTimeout(8_000L) {
                    if (intent.action == AlarmBridge.TICK) app.store.reconcile()
                    else app.store.act(intent.getLongExtra("id", -1), intent.action ?: "")
                }
                app.diagnostics.succeeded()
            } catch (e: Exception) {
                app.diagnostics.record("后台处理失败", e.javaClass.simpleName)
                if (app.diagnostics.failed() <= 3) runCatching { app.store.bridge.schedule(System.currentTimeMillis() + 60_000) }
            } finally { try { wake.close() } finally { pending.finish() } }
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        val app = context.applicationContext as ShiyiApp
        val wake = WakeLease.acquire(context)
        app.diagnostics.record("恢复调度", intent.action.orEmpty())
        app.scope.launch {
            try { withTimeout(8_000L) { app.store.reconcile(rebaseZone = true) } }
            catch (e: Exception) { app.diagnostics.record("恢复失败", e.javaClass.simpleName) }
            finally { try { wake.close() } finally { pending.finish() } }
        }
    }
}
