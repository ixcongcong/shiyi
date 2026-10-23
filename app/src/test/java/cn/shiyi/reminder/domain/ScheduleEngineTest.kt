package cn.shiyi.reminder.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ScheduleEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDate.of(2026, 1, 1)
    private fun utc(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()
    private fun dates(s: Schedule, from: Instant = utc("2026-01-01T00:00"), n: Int = 5) = ScheduleEngine.next(s, from, zone, n).map { it.anchorDate }

    @Test fun dailyMultipleTimesAreSortedAndIndependent() {
        val s = Schedule(start = start, times = listOf(LocalTime.of(20, 0), LocalTime.of(8, 0)))
        val actual = ScheduleEngine.next(s, utc("2026-01-01T09:00"), zone, 3)
        assertEquals(listOf(utc("2026-01-01T20:00"), utc("2026-01-02T08:00"), utc("2026-01-02T20:00")), actual.map { it.instant })
        assertEquals(listOf(1, 0, 1), actual.map { it.slot })
    }
    @Test fun everyNDaysKeepsStartAnchorAcrossMonthsAndLeapDay() {
        val s = Schedule(type = RuleType.EVERY_N_DAYS, start = LocalDate.of(2028, 2, 27), interval = 2)
        assertEquals(listOf("2028-02-27", "2028-02-29", "2028-03-02", "2028-03-04"), dates(s, utc("2028-02-27T00:00"), 4).map { it.toString() })
    }
    @Test fun cycleAlternatesAndDoesNotRestartOnQuery() {
        val s = Schedule(type = RuleType.CYCLE, start = start, cycle = listOf(2, 3))
        assertEquals(listOf(1, 3, 6, 8, 11), dates(s).map { it.dayOfMonth })
        assertEquals(listOf(6, 8, 11), dates(s, utc("2026-01-04T00:00"), 3).map { it.dayOfMonth })
    }
    @Test fun weeklyOnlyIncludesChosenWeekdays() {
        val s = Schedule(type = RuleType.WEEKLY, start = start, weekdays = setOf(1, 7))
        assertEquals(listOf("2026-01-04", "2026-01-05", "2026-01-11"), dates(s, n = 3).map { it.toString() })
    }
    @Test fun onceInPastProducesNoFuturePlan() {
        assertTrue(dates(Schedule(type = RuleType.ONCE, start = start), utc("2026-01-02T00:00")).isEmpty())
    }
    @Test fun endDateIsInclusive() {
        assertEquals(2, dates(Schedule(start = start, end = start.plusDays(1))).size)
    }
    @Test fun relativeTimeCanFallOnPreviousDay() {
        val s = Schedule(type = RuleType.ONCE, start = start.plusDays(1), anchorName = "自定日程", anchorTime = LocalTime.of(0, 15), offsetMinutes = -30)
        val p = ScheduleEngine.next(s, utc("2026-01-01T00:00"), zone).single()
        assertEquals(utc("2026-01-01T23:45"), p.instant)
        assertEquals(start.plusDays(1), p.anchorDate)
    }
    @Test fun relativeTimeCanFallOnFollowingDay() {
        val s = Schedule(type = RuleType.ONCE, start = start, anchorName = "自定日程", anchorTime = LocalTime.of(23, 45), offsetMinutes = 30)
        assertEquals(utc("2026-01-02T00:15"), ScheduleEngine.next(s, utc("2026-01-02T00:00"), zone).single().instant)
    }
    @Test fun daylightSavingGapUsesFirstValidTime() {
        val s = Schedule(type = RuleType.ONCE, start = LocalDate.of(2026, 3, 8), times = listOf(LocalTime.of(2, 30)))
        val result = ScheduleEngine.next(s, Instant.parse("2026-03-08T00:00:00Z"), ZoneId.of("America/New_York")).single()
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), result.instant)
    }
    @Test fun daylightSavingOverlapUsesEarlierOffsetOnce() {
        val s = Schedule(type = RuleType.ONCE, start = LocalDate.of(2026, 11, 1), times = listOf(LocalTime.of(1, 30)))
        val result = ScheduleEngine.next(s, Instant.parse("2026-11-01T00:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(1, result.size)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), result.single().instant)
    }
    @Test fun intervalDoesNotAdvanceByFixed24HoursAcrossDst() {
        val s = Schedule(start = LocalDate.of(2026, 3, 7), times = listOf(LocalTime.of(8, 0)))
        val actual = ScheduleEngine.next(s, Instant.parse("2026-03-07T00:00:00Z"), ZoneId.of("America/New_York"), 3)
        assertEquals(23 * 3600L, actual[1].instant.epochSecond - actual[0].instant.epochSecond)
        assertTrue(actual.all { it.instant.atZone(ZoneId.of("America/New_York")).hour == 8 })
    }
    @Test fun planCountIsBoundedForLongIntervals() {
        val s = Schedule(type = RuleType.EVERY_N_DAYS, start = start, interval = 3650)
        assertEquals(3, dates(s, n = 3).size)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidEndDateRejected() { Schedule(start = start, end = start.minusDays(1)).validate() }
    @Test(expected = IllegalArgumentException::class) fun duplicateTimeRejected() { Schedule(times = listOf(LocalTime.NOON, LocalTime.NOON)).validate() }
    @Test(expected = IllegalArgumentException::class) fun emptyWeeklySelectionRejected() { Schedule(type = RuleType.WEEKLY, weekdays = emptySet()).validate() }
    @Test(expected = IllegalArgumentException::class) fun zeroCycleRejected() { Schedule(cycle = listOf(0, 2)).validate() }
}
