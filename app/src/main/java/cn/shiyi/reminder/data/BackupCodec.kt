package cn.shiyi.reminder.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class Backup(val reminders: List<ReminderEntity>, val occurrences: List<OccurrenceEntity>)

/** Plaintext manual export only. Android automatic backup is disabled in the manifest. */
object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(reminders: List<ReminderEntity>, occurrences: List<OccurrenceEntity>): String = JSONObject().apply {
        put("format", "shiyi-backup"); put("version", 1); put("exportedAt", System.currentTimeMillis())
        put("reminders", JSONArray(reminders.map { r -> JSONObject().apply {
            put("id", r.id); put("title", r.title); put("note", r.note); put("category", r.category)
            put("schedule", JSONObject(r.scheduleJson)); put("enabled", r.enabled); put("archived", r.archived)
            put("revision", r.revision); put("privateOnLockscreen", r.privateOnLockscreen)
            put("snoozeMinutes", r.snoozeMinutes); put("repeatCount", r.repeatCount)
            put("repeatMinutes", r.repeatMinutes); put("createdAt", r.createdAt)
        } }))
        put("occurrences", JSONArray(occurrences.filter { it.state != OccurrenceState.CANCELLED }.map { o -> JSONObject().apply {
            put("key", o.occurrenceKey); put("reminderId", o.reminderId); put("revision", o.revision)
            put("date", o.anchorDate); put("slot", o.slot); put("title", o.title); put("note", o.note)
            put("plannedAt", o.plannedAt); put("ringAt", o.ringAt); put("zone", o.zone); put("state", o.state)
            put("actionAt", o.actionAt ?: JSONObject.NULL); put("notifiedAt", o.notifiedAt ?: JSONObject.NULL)
            put("attempts", o.attempts); put("detail", o.detail)
        } }))
    }.toString(2)

    fun decode(value: String): Backup {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8 MB" }
        val obj = JSONObject(value)
        require(obj.getString("format") == "shiyi-backup" && obj.getInt("version") == 1) { "不支持的备份格式或版本" }
        val rs = obj.getJSONArray("reminders")
        val os = obj.getJSONArray("occurrences")
        require(rs.length() <= 1000 && os.length() <= 50000) { "备份包含过多记录" }
        val reminders = (0 until rs.length()).map { i ->
            val r = rs.getJSONObject(i)
            ReminderEntity(
                id = r.getString("id"), title = r.getString("title"), note = r.getString("note"),
                category = r.getString("category"), scheduleJson = r.getJSONObject("schedule").toString(),
                enabled = r.getBoolean("enabled"), archived = r.getBoolean("archived"), revision = r.getInt("revision"),
                privateOnLockscreen = r.getBoolean("privateOnLockscreen"), snoozeMinutes = r.getInt("snoozeMinutes"),
                repeatCount = r.getInt("repeatCount"), repeatMinutes = r.getInt("repeatMinutes"), createdAt = r.getLong("createdAt"),
            ).also { it.validate() }
        }
        require(reminders.distinctBy { it.id }.size == reminders.size) { "备份含重复事项" }
        val occurrences = (0 until os.length()).map { i ->
            val o = os.getJSONObject(i)
            OccurrenceEntity(
                occurrenceKey = o.getString("key"), reminderId = o.getString("reminderId"), revision = o.getInt("revision"),
                anchorDate = o.getString("date"), slot = o.getInt("slot"), title = o.getString("title"), note = o.getString("note"),
                plannedAt = o.getLong("plannedAt"), ringAt = o.getLong("ringAt"), zone = o.getString("zone"), state = o.getString("state"),
                actionAt = if (o.isNull("actionAt")) null else o.getLong("actionAt"),
                notifiedAt = if (o.isNull("notifiedAt")) null else o.getLong("notifiedAt"),
                attempts = o.getInt("attempts"), detail = o.getString("detail"),
            ).also {
                UUID.fromString(it.reminderId); LocalDate.parse(it.anchorDate); ZoneId.of(it.zone)
                require(it.revision in 1..1_000_000 && it.slot in 0..11 && it.attempts in 0..6)
                require(it.state in OccurrenceState.all && it.state != OccurrenceState.CANCELLED)
                require(it.occurrenceKey == "${it.reminderId}:${it.revision}:${it.anchorDate}:${it.slot}") { "计划记录标识无效" }
                require(it.title.isNotBlank() && it.title.length <= 80 && it.note.length <= 1000 && it.detail.length <= 1000)
                require(it.plannedAt in 0..7_258_118_400_000L && it.ringAt in 0..7_258_118_400_000L)
                require(it.actionAt == null || it.actionAt in 0..7_258_118_400_000L)
                require(it.notifiedAt == null || it.notifiedAt in 0..7_258_118_400_000L)
                if (it.state in setOf(OccurrenceState.PENDING, OccurrenceState.ALERTING)) {
                    val r = reminders.firstOrNull { r -> r.id == it.reminderId }
                    require(r != null && r.enabled && !r.archived && r.revision == it.revision) { "备份中的待提醒计划缺少有效事项" }
                    if (it.state == OccurrenceState.PENDING) require(it.ringAt > 0)
                } else require(it.ringAt == 0L)
            }
        }
        require(occurrences.distinctBy { it.occurrenceKey }.size == occurrences.size) { "备份含重复计划" }
        return Backup(reminders, occurrences)
    }
}
