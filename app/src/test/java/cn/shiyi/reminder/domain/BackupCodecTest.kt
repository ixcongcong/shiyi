package cn.shiyi.reminder.domain

import cn.shiyi.reminder.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BackupCodecTest {
    private val date = LocalDate.of(2026, 1, 1)
    private fun reminder() = ReminderEntity(title = "自建事项", note = "中文备注", scheduleJson = ScheduleJson.encode(Schedule(start = date)))
    @Test fun scheduleAndBackupRoundTripPreserveUserFields() {
        val reminder = reminder()
        val o = OccurrenceEntity(reminderId = reminder.id, revision = 1, occurrenceKey = "${reminder.id}:1:$date:0", anchorDate = date.toString(), slot = 0,
            title = reminder.title, note = reminder.note, plannedAt = 1_767_225_600_000L, ringAt = 0, zone = "Asia/Shanghai", state = OccurrenceState.DONE, actionAt = 1_767_225_600_000L)
        val result = BackupCodec.decode(BackupCodec.encode(listOf(reminder), listOf(o)))
        assertEquals(reminder, result.reminders.single())
        assertEquals(o, result.occurrences.single())
        assertEquals(Schedule(start = date), reminder.schedule())
    }
    @Test fun emptyBackupHasNoSeedReminders() {
        val result = BackupCodec.decode(BackupCodec.encode(emptyList(), emptyList()))
        assertTrue(result.reminders.isEmpty()); assertTrue(result.occurrences.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedBackupVersionRejected() {
        val obj = JSONObject(BackupCodec.encode(emptyList(), emptyList())).put("version", 99)
        BackupCodec.decode(obj.toString())
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateReminderRejectedBeforeRestore() {
        val r = reminder()
        BackupCodec.decode(BackupCodec.encode(listOf(r, r), emptyList()))
    }
    @Test(expected = IllegalArgumentException::class) fun activeOccurrenceWithoutReminderRejected() {
        val r = reminder()
        val o = OccurrenceEntity(reminderId = r.id, revision = 1, occurrenceKey = "${r.id}:1:$date:0", anchorDate = date.toString(), slot = 0,
            title = r.title, note = "", plannedAt = 1, ringAt = 1, zone = "Asia/Shanghai")
        BackupCodec.decode(BackupCodec.encode(emptyList(), listOf(o)))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRuleSchemaRejected() {
        val json = JSONObject(ScheduleJson.encode(Schedule(start = date))).put("schema", 4)
        ScheduleJson.decode(json.toString())
    }
}
