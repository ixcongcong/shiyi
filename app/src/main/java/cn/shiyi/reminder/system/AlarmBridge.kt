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

class AlarmBridge(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

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
        if (time == null) { alarms.cancel(alarmIntent()); return }
        val at = maxOf(time, System.currentTimeMillis() + 1000)
        try {
            if (canExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
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
            .addAction(0, "完成", action(o.id, DONE))
            .addAction(0, "延后 ${r.snoozeMinutes} 分钟", action(o.id, SNOOZE))
            .addAction(0, "跳过", action(o.id, SKIP)).build()
        return try { notifications.notify(o.id.toString(), 1, notification); true } catch (_: SecurityException) { false }
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
