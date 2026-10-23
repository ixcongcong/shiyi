package cn.shiyi.reminder.system

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.shiyi.reminder.MainActivity
import cn.shiyi.reminder.R
import cn.shiyi.reminder.data.OccurrenceEntity
import cn.shiyi.reminder.data.ReminderEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AlarmBridge(private val context: Context, val diagnostics: ReminderDiagnostics = ReminderDiagnostics(context)) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("reminder_settings", Context.MODE_PRIVATE)
    var useAlarmClock: Boolean
        get() = preferences.getBoolean("alarm_clock", true)
        set(value) { preferences.edit().putBoolean("alarm_clock", value).commit() }

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "事项提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "时宜的提醒通知，声音与振动可在系统中调整"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun canExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun alarmIntent() = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(time: Long?) {
        if (time == null) { alarms.cancel(alarmIntent()); diagnostics.record("闹钟取消", "无待发安排"); return }
        val at = maxOf(time, System.currentTimeMillis() + 1000)
        try {
            val mode = when {
                canExact() && useAlarmClock -> {
                    val show = PendingIntent.getActivity(context, 1,
                        Intent(context, MainActivity::class.java).setAction("cn.shiyi.SHOW_ALARM"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), alarmIntent())
                    "加强锁屏（系统闹钟）"
                }
                canExact() -> { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent()); "精确提醒" }
                else -> { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent()); "非精确提醒，可能延迟" }
            }
            diagnostics.record("闹钟预约", "${ReminderDiagnostics.formatTime(at)} · $mode")
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
            diagnostics.record("闹钟降级", "${ReminderDiagnostics.formatTime(at)} · 精确权限不可用，可能延迟")
        }
    }

    private fun action(id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(action)
            .setData(Uri.parse("shiyi://occurrence/$id/$action")).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("MissingPermission") // canNotify checks both runtime permission and channel state.
    fun post(o: OccurrenceEntity, r: ReminderEntity): Boolean {
        if (!canNotify()) return false
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val clock = Instant.ofEpochMilli(o.plannedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("时宜提醒").setContentText("有一项提醒待处理").setContentIntent(open).build()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(o.title).setContentText("$clock${if (o.note.isBlank()) " · 记得给自己一点时间" else " · ${o.note}"}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(o.note.ifBlank { "到时间了，可以完成、延后或跳过本次。" }))
            .setContentIntent(open).setAutoCancel(false)
            .setVisibility(if (r.privateOnLockscreen) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(public).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "完成", action(o.id, DONE))
            .addAction(0, "延后 ${r.snoozeMinutes} 分钟", action(o.id, SNOOZE))
            .addAction(0, "跳过", action(o.id, SKIP)).build()
        return try {
            notifications.notify(o.id.toString(), 1, notification)
            diagnostics.record("通知提交", "计划 ${ReminderDiagnostics.formatTime(o.ringAt)} · 延迟 ${maxOf(0, System.currentTimeMillis() - o.ringAt) / 1000} 秒")
            true
        } catch (_: SecurityException) { diagnostics.record("通知失败", "通知权限被撤销"); false }
    }

    fun cancel(id: Long) = notifications.cancel(id.toString(), 1)
    fun cancelAll() = notifications.cancelAll()
    @SuppressLint("MissingPermission")
    fun test(): Boolean {
        if (!canNotify()) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("时宜 · 测试通知").setContentText("如果看到了这条消息，通知展示正常。锁屏和省电表现仍需实际测试。")
            .setContentIntent(open).setAutoCancel(true).build()
        return try { notifications.notify("test", 2, n); true } catch (_: SecurityException) { false }
    }

    companion object {
        const val CHANNEL = "shiyi_reminders_v1"
        const val TICK = "cn.shiyi.TICK"
        const val DONE = "cn.shiyi.DONE"
        const val SKIP = "cn.shiyi.SKIP"
        const val SNOOZE = "cn.shiyi.SNOOZE"
    }
}
