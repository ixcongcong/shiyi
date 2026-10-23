package cn.shiyi.reminder.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class RuleType(val label: String) {
    ONCE("仅一次"), DAILY("每天"), WEEKLY("每周"), EVERY_N_DAYS("每 N 天"), CYCLE("自定义间隔周期")
}

data class Schedule(
    val type: RuleType = RuleType.DAILY,
    val start: LocalDate = LocalDate.now(),
    val end: LocalDate? = null,
    val times: List<LocalTime> = listOf(LocalTime.of(9, 0)),
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val interval: Int = 2,
    val cycle: List<Int> = listOf(2, 3),
    val anchorName: String = "",
    val anchorTime: LocalTime = LocalTime.of(8, 0),
    val offsetMinutes: Int = -30,
) {
    fun validate() {
        require(end == null || !end.isBefore(start)) { "结束日期不能早于开始日期" }
        require(times.size in 1..12 && times.distinct().size == times.size) { "请选择 1–12 个不同时间" }
        require(times.all { it.second == 0 && it.nano == 0 }) { "时间精确到分钟即可" }
        require(weekdays.all { it in 1..7 } && (type != RuleType.WEEKLY || weekdays.isNotEmpty())) { "请至少选择一个星期" }
        require(interval in 1..3650) { "间隔天数需在 1–3650 之间" }
        require(cycle.size in 1..12 && cycle.all { it in 1..3650 }) { "请设置有效的周期天数" }
        require(anchorName.length <= 40 && offsetMinutes in -1440..1440) { "参照日程设置无效" }
        require(anchorTime.second == 0 && anchorTime.nano == 0) { "参照时间精确到分钟即可" }
        require(start.year in 2000..2199 && (end == null || end.year in 2000..2199)) { "日期需在 2000–2199 年之间" }
    }

    fun summary(): String {
        val frequency = when (type) {
            RuleType.ONCE -> "仅 ${start}"
            RuleType.DAILY -> "每天"
            RuleType.WEEKLY -> "每周" + weekdays.sorted().joinToString("、") { "一二三四五六日"[it - 1].toString() }
            RuleType.EVERY_N_DAYS -> "每 $interval 天"
            RuleType.CYCLE -> "间隔 ${cycle.joinToString(" / ")} 天循环"
        }
        val clock = if (anchorName.isBlank()) times.sorted().joinToString("、") else {
            "$anchorName $anchorTime ${if (offsetMinutes < 0) "提前" else "延后"}${kotlin.math.abs(offsetMinutes)} 分钟"
        }
        return "$frequency · $clock"
    }
}

data class PlannedTime(val anchorDate: LocalDate, val slot: Int, val instant: Instant)

object ScheduleEngine {
    fun next(schedule: Schedule, from: Instant, zone: ZoneId, count: Int = 12): List<PlannedTime> {
        schedule.validate()
        require(count in 1..128)
        val results = mutableListOf<PlannedTime>()
        // Relative times can cross midnight in either direction.
        var candidate = maxOf(schedule.start, from.atZone(zone).toLocalDate().minusDays(1))
        var visited = 0
        while (results.size < count + 12 && visited++ < count + 16) {
            val date = nextDate(schedule, candidate) ?: break
            if (schedule.end != null && date.isAfter(schedule.end)) break
            if (date.year > 2199) break
            onDate(schedule, date, zone).filterTo(results) { !it.instant.isBefore(from) }
            candidate = date.plusDays(1)
        }
        return results.sortedBy { it.instant }.distinctBy { it.instant }.take(count)
    }

    fun onDate(schedule: Schedule, date: LocalDate, zone: ZoneId): List<PlannedTime> {
        val locals = if (schedule.anchorName.isBlank()) schedule.times.sorted().map { date.atTime(it) }
        else listOf(date.atTime(schedule.anchorTime).plusMinutes(schedule.offsetMinutes.toLong()))
        return locals.mapIndexed { slot, local -> PlannedTime(date, slot, resolve(local, zone).toInstant()) }
    }

    private fun nextDate(s: Schedule, candidate: LocalDate): LocalDate? {
        val date = maxOf(candidate, s.start)
        val delta = ChronoUnit.DAYS.between(s.start, date)
        return when (s.type) {
            RuleType.ONCE -> s.start.takeIf { !date.isAfter(it) }
            RuleType.DAILY -> date
            RuleType.WEEKLY -> (0L..6L).map { date.plusDays(it) }.first { it.dayOfWeek.value in s.weekdays }
            RuleType.EVERY_N_DAYS -> s.start.plusDays(((delta + s.interval - 1) / s.interval) * s.interval)
            RuleType.CYCLE -> {
                val period = s.cycle.sum().toLong()
                val offsets = s.cycle.runningFold(0L) { acc, days -> acc + days }.dropLast(1)
                val block = delta / period
                val remainder = delta % period
                val offset = offsets.firstOrNull { it >= remainder }
                s.start.plusDays(if (offset == null) (block + 1) * period else block * period + offset)
            }
        }
    }

    private fun resolve(local: LocalDateTime, zone: ZoneId): ZonedDateTime {
        val offsets = zone.rules.getValidOffsets(local)
        return if (offsets.isEmpty()) zone.rules.getTransition(local)!!.dateTimeAfter.atZone(zone)
        else ZonedDateTime.ofLocal(local, zone, offsets.first())
    }
}
