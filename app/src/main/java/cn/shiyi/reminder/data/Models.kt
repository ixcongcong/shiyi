package cn.shiyi.reminder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.shiyi.reminder.domain.RuleType
import cn.shiyi.reminder.domain.Schedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val note: String = "",
    val category: String = "",
    val scheduleJson: String,
    val enabled: Boolean = true,
    val archived: Boolean = false,
    val revision: Int = 1,
    val privateOnLockscreen: Boolean = true,
    val snoozeMinutes: Int = 10,
    val repeatCount: Int = 0,
    val repeatMinutes: Int = 10,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun schedule(): Schedule = ScheduleJson.decode(scheduleJson)
    fun validate() {
        UUID.fromString(id)
        require(title.isNotBlank() && title.length <= 80) { "名称需为 1–80 个字" }
        require(note.length <= 1000 && category.length <= 30) { "备注或分类过长" }
        require(snoozeMinutes in 1..1440 && repeatCount in 0..5 && repeatMinutes in 5..120) { "提示设置无效" }
        require(revision in 1..1_000_000) { "规则版本无效" }
        schedule().validate()
    }
}

object OccurrenceState {
    const val PENDING = "PENDING"
    const val ALERTING = "ALERTING"
    const val DONE = "DONE"
    const val SKIPPED = "SKIPPED"
    const val MISSED = "MISSED"
    const val CANCELLED = "CANCELLED"
    val all = setOf(PENDING, ALERTING, DONE, SKIPPED, MISSED, CANCELLED)
}

@Entity(tableName = "occurrences", indices = [Index(value = ["occurrenceKey"], unique = true), Index("reminderId"), Index("ringAt")])
data class OccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceKey: String,
    val reminderId: String,
    val revision: Int,
    val anchorDate: String,
    val slot: Int,
    val title: String,
    val note: String,
    val plannedAt: Long,
    val ringAt: Long,
    val zone: String,
    val state: String = OccurrenceState.PENDING,
    val actionAt: Long? = null,
    val notifiedAt: Long? = null,
    val attempts: Int = 0,
    val detail: String = "",
)

object ScheduleJson {
    fun encode(s: Schedule): String = JSONObject().apply {
        put("schema", 1); put("type", s.type.name)
        put("start", s.start.toString()); put("end", s.end?.toString() ?: JSONObject.NULL)
        put("times", JSONArray(s.times.sorted().map { it.toString() }))
        put("weekdays", JSONArray(s.weekdays.sorted())); put("interval", s.interval)
        put("cycle", JSONArray(s.cycle)); put("anchorName", s.anchorName)
        put("anchorTime", s.anchorTime.toString()); put("offsetMinutes", s.offsetMinutes)
    }.toString()

    fun decode(value: String): Schedule {
        require(value.length <= 10_000) { "规则数据过大" }
        val obj = JSONObject(value)
        require(obj.getInt("schema") == 1) { "不支持的规则版本" }
        val times = obj.getJSONArray("times")
        val days = obj.getJSONArray("weekdays")
        val cycle = obj.getJSONArray("cycle")
        require(times.length() in 1..12 && days.length() in 0..7 && cycle.length() in 1..12)
        return Schedule(
            type = RuleType.valueOf(obj.getString("type")),
            start = LocalDate.parse(obj.getString("start")),
            end = if (obj.isNull("end")) null else LocalDate.parse(obj.getString("end")),
            times = (0 until times.length()).map { LocalTime.parse(times.getString(it)) },
            weekdays = (0 until days.length()).map { days.getInt(it) }.toSet(),
            interval = obj.getInt("interval"),
            cycle = (0 until cycle.length()).map { cycle.getInt(it) },
            anchorName = obj.optString("anchorName", ""),
            anchorTime = LocalTime.parse(obj.optString("anchorTime", "08:00")),
            offsetMinutes = obj.optInt("offsetMinutes", -30),
        ).also { it.validate() }
    }
}
