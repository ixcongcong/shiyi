package cn.shiyi.reminder.data

import androidx.room.withTransaction
import cn.shiyi.reminder.domain.ScheduleEngine
import cn.shiyi.reminder.system.AlarmBridge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Serializes database mutations and the single next-alarm reconciliation. */
class ReminderStore(val database: ShiyiDatabase, val bridge: AlarmBridge) {
    val dao = database.reminders()
    private val mutex = Mutex()

    suspend fun save(value: ReminderEntity) = mutex.withLock {
        value.validate()
        val old = dao.reminder(value.id)
        val next = value.copy(revision = (old?.revision ?: 0) + 1)
        database.withTransaction {
            cancelLocked(value.id)
            dao.save(next)
        }
        refreshLocked()
    }

    suspend fun toggle(id: String) = mutex.withLock {
        val r = dao.reminder(id) ?: return@withLock
        database.withTransaction {
            cancelLocked(id)
            dao.save(r.copy(enabled = !r.enabled, revision = r.revision + 1))
        }
        refreshLocked()
    }

    suspend fun archive(id: String) = mutex.withLock {
        val r = dao.reminder(id) ?: return@withLock
        database.withTransaction {
            cancelLocked(id)
            dao.save(r.copy(archived = !r.archived, enabled = false, revision = r.revision + 1))
        }
        refreshLocked()
    }

    suspend fun delete(id: String) = mutex.withLock {
        database.withTransaction { cancelLocked(id); dao.deleteReminder(id) }
        refreshLocked()
    }

    private suspend fun cancelLocked(id: String) {
        dao.activeFor(id).forEach {
            bridge.cancel(it.id)
            dao.update(it.copy(state = OccurrenceState.CANCELLED, ringAt = 0))
        }
        dao.pruneCancelled()
    }

    suspend fun act(id: Long, action: String, delayMinutes: Int? = null) = mutex.withLock {
        val o = dao.occurrence(id) ?: return@withLock
        if (o.state == OccurrenceState.CANCELLED) return@withLock
        val now = System.currentTimeMillis()
        if (action == AlarmBridge.SNOOZE) {
            if (o.state == OccurrenceState.DONE || o.state == OccurrenceState.SKIPPED) return@withLock
            val r = dao.reminder(o.reminderId) ?: return@withLock
            if (!r.enabled || r.archived || r.revision != o.revision) return@withLock
            val delay = delayMinutes ?: r.snoozeMinutes
            require(delay in 1..1440) { "延后时间需为 1–1440 分钟" }
            dao.update(o.copy(state = OccurrenceState.PENDING, ringAt = now + delay * 60_000L, actionAt = now, attempts = 0, detail = "延后 $delay 分钟"))
        } else {
            val state = when (action) {
                AlarmBridge.DONE -> OccurrenceState.DONE
                AlarmBridge.SKIP -> OccurrenceState.SKIPPED
                else -> return@withLock
            }
            // Final states are idempotent: stale notification taps cannot change a completed item.
            if (o.state == OccurrenceState.DONE || o.state == OccurrenceState.SKIPPED) return@withLock
            dao.update(o.copy(state = state, actionAt = now, ringAt = 0, detail = if (o.state == OccurrenceState.MISSED) "手动补记" else o.detail))
        }
        bridge.cancel(id)
        refreshLocked()
    }

    suspend fun reconcile(rebaseZone: Boolean = false) = mutex.withLock { refreshLocked(rebaseZone) }

    suspend fun rescheduleOnce(id: Long, time: Instant) = mutex.withLock {
        val o = dao.occurrence(id) ?: return@withLock
        require(o.state in setOf(OccurrenceState.PENDING, OccurrenceState.ALERTING, OccurrenceState.MISSED)) { "这次事项已处理" }
        val r = dao.reminder(o.reminderId) ?: error("原提醒已删除")
        require(r.enabled && !r.archived && r.revision == o.revision) { "原提醒已暂停或修改" }
        require(time.isAfter(Instant.now())) { "请选择未来的时间" }
        bridge.cancel(id)
        dao.update(o.copy(state = OccurrenceState.PENDING, ringAt = time.toEpochMilli(), actionAt = System.currentTimeMillis(), attempts = 0, detail = "单独调整本次时间"))
        refreshLocked()
    }

    private suspend fun refreshLocked(rebaseZone: Boolean = false) {
        val now = Instant.now()
        val nowMs = now.toEpochMilli()
        val zone = ZoneId.systemDefault()
        val reminders = dao.reminders().associateBy { it.id }
        database.withTransaction {
            if (rebaseZone) {
                dao.scheduled().forEach { o ->
                    val r = reminders[o.reminderId]
                    if (r != null && o.state == OccurrenceState.PENDING && o.attempts == 0 && o.actionAt == null) {
                        val time = ScheduleEngine.onDate(r.schedule(), LocalDate.parse(o.anchorDate), zone)
                            .firstOrNull { it.slot == o.slot }?.instant?.toEpochMilli()
                        if (time != null) dao.update(o.copy(plannedAt = time, ringAt = time, zone = zone.id))
                    }
                }
            }
            // Bound the future plan; no separate OS alarm per reminder or time slot.
            reminders.values.filter { it.enabled && !it.archived }.forEach { r ->
                ScheduleEngine.next(r.schedule(), now, zone, 32).forEach { p ->
                    val ms = p.instant.toEpochMilli()
                    dao.insert(OccurrenceEntity(
                        occurrenceKey = "${r.id}:${r.revision}:${p.anchorDate}:${p.slot}", reminderId = r.id,
                        revision = r.revision, anchorDate = p.anchorDate.toString(), slot = p.slot,
                        title = r.title, note = r.note, plannedAt = ms, ringAt = ms, zone = zone.id,
                    ))
                }
            }
            dao.scheduled().filter { it.ringAt <= nowMs }.forEach { o ->
                val r = reminders[o.reminderId]
                if (r == null || !r.enabled || r.archived || o.revision != r.revision) {
                    bridge.cancel(o.id)
                    dao.update(o.copy(state = OccurrenceState.CANCELLED, ringAt = 0))
                } else if (nowMs - o.ringAt > 10 * 60_000L) {
                    dao.update(o.copy(state = OccurrenceState.MISSED, ringAt = 0, detail = "超过提醒时间，请查看并处理"))
                } else if (bridge.post(o, r)) {
                    val attempts = o.attempts + 1
                    val retry = if (attempts <= r.repeatCount) nowMs + r.repeatMinutes * 60_000L else 0L
                    dao.update(o.copy(state = OccurrenceState.ALERTING, notifiedAt = nowMs, attempts = attempts, ringAt = retry))
                } else {
                    dao.update(o.copy(state = OccurrenceState.MISSED, ringAt = 0, detail = "通知未获允许，请检查权限或通知渠道"))
                }
            }
            dao.pruneCancelled()
        }
        bridge.schedule(dao.scheduled().minOfOrNull { it.ringAt })
    }

    suspend fun exportBackup(): String = mutex.withLock { BackupCodec.encode(dao.reminders(), dao.occurrences()) }

    suspend fun restoreBackup(value: String) = mutex.withLock {
        val backup = BackupCodec.decode(value)
        bridge.schedule(null)
        bridge.cancelAll()
        database.withTransaction {
            dao.clearOccurrences(); dao.clearReminders()
            backup.reminders.forEach { dao.save(it) }
            backup.occurrences.forEach { dao.insert(it) }
        }
        refreshLocked(rebaseZone = true)
    }
}
