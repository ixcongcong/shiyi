package cn.shiyi.reminder.system

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local-only bounded timing log. Never stores reminder titles, notes or backup contents. */
class ReminderDiagnostics(private val context: Context) {
    private val preferences = context.getSharedPreferences("reminder_diagnostics", Context.MODE_PRIVATE)
    private val lock = Any()

    fun record(event: String, detail: String = "") = synchronized(lock) {
        val history = runCatching { JSONArray(preferences.getString("events", "[]")) }.getOrElse { JSONArray() }
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val entry = JSONObject().put("at", System.currentTimeMillis()).put("event", event)
            .put("detail", detail.take(240)).put("locked", keyguard.isKeyguardLocked)
            .put("interactive", power.isInteractive).put("idle", power.isDeviceIdleMode)
        val next = JSONArray()
        for (i in maxOf(0, history.length() - 39) until history.length()) next.put(history.getJSONObject(i))
        next.put(entry)
        // Persist before the receiver returns, rather than relying on an asynchronous disk flush.
        preferences.edit().putString("events", next.toString()).commit()
        Unit
    }

    fun failed(): Int = synchronized(lock) {
        val count = preferences.getInt("failures", 0) + 1
        preferences.edit().putInt("failures", count).commit()
        count
    }

    fun succeeded() { preferences.edit().putInt("failures", 0).apply() }

    fun report(): String = synchronized(lock) {
        val history = runCatching { JSONArray(preferences.getString("events", "[]")) }.getOrElse { JSONArray() }
        val power = context.getSystemService(PowerManager::class.java)
        buildString {
            appendLine("时宜锁屏诊断 · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("系统电池优化豁免：${power.isIgnoringBatteryOptimizations(context.packageName)}")
            appendLine("仅记录时间和系统状态，不含事项名称或备注。")
            appendLine("“闹钟回调”晚于预约时间：系统或后台调度延迟；回调与通知提交准时但锁屏未显示：检查系统展示、省电和勿扰。")
            if (history.length() == 0) appendLine("暂无记录，请先预约锁屏测试。")
            for (i in 0 until history.length()) {
                val e = history.getJSONObject(i)
                appendLine("${formatTime(e.getLong("at"))} ${e.getString("event")} ${e.getString("detail")} [锁屏=${e.getBoolean("locked")},息屏=${!e.getBoolean("interactive")},休眠=${e.getBoolean("idle")}]")
            }
            append("通知提交记录不证明实际响铃或展示。")
        }
    }

    companion object {
        fun formatTime(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    }
}
