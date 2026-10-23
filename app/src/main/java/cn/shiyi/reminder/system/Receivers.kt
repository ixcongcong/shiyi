package cn.shiyi.reminder.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.shiyi.reminder.ShiyiApp
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ShiyiApp
        app.scope.launch {
            try {
                if (intent.action == AlarmBridge.TICK) app.store.reconcile()
                else app.store.act(intent.getLongExtra("id", -1), intent.action ?: "")
            } finally { pending.finish() }
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
        app.scope.launch {
            try { app.store.reconcile(rebaseZone = true) } finally { pending.finish() }
        }
    }
}
