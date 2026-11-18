package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val weekdays = 0x1F
    private val study = ScheduleEntry(Modes.STUDY, weekdays, 9 * 60, 18 * 60, strict = false)

    // 16.11.2026 — понедельник
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)
    private fun z(day: Int, h: Int, m: Int = 0): ZonedDateTime = at(day, h, m).atZone(zone)

    @Test
    fun weekdaysNineToSix() {
        assertTrue(Scheduler.covers(study, at(16, 9, 0)))
        assertTrue(Scheduler.covers(study, at(20, 17, 59)))   // пятница
        assertFalse(Scheduler.covers(study, at(16, 18, 0)))   // конец не включается
        assertFalse(Scheduler.covers(study, at(16, 8, 59)))
        assertFalse(Scheduler.covers(study, at(21, 12, 0)))   // суббота
    }

    @Test
    fun overnightRangeStartsOnMarkedDay() {
        val night = ScheduleEntry(Modes.TALK, 1 shl 4, 23 * 60, 7 * 60, strict = false) // только пятница
        assertTrue(Scheduler.covers(night, at(20, 23, 30)))   // пятница вечер
        assertTrue(Scheduler.covers(night, at(21, 6, 59)))    // утро субботы — продолжение
        assertFalse(Scheduler.covers(night, at(20, 6, 0)))    // утро пятницы — нет (четверг не отмечен)
        assertEquals(at(21, 7, 0), Scheduler.endOf(night, at(20, 23, 30)))
        assertEquals(at(21, 7, 0), Scheduler.endOf(night, at(21, 1, 0)))
    }

    @Test
    fun wholeDayWhenStartEqualsEnd() {
        val all = ScheduleEntry(Modes.REST, 0x60, 0, 0, strict = false)
        assertTrue(Scheduler.covers(all, at(22, 15, 0)))   // воскресенье
        assertFalse(Scheduler.covers(all, at(23, 15, 0)))  // понедельник
    }

    @Test
    fun resolveDefaultScheduleManual() {
        assertEquals(Modes.NORMAL, Scheduler.resolve(z(16, 8), listOf(study), null).mode)
        val a = Scheduler.resolve(z(16, 10), listOf(study), null)
        assertEquals(Modes.STUDY, a.mode)
        assertEquals(ModeSource.SCHEDULE, a.source)
        assertEquals(z(16, 18).toInstant().toEpochMilli(), a.until)

        val manual = ManualMode(Modes.REST, z(16, 11).toInstant().toEpochMilli(), strict = false)
        assertEquals(Modes.REST, Scheduler.resolve(z(16, 10), listOf(study), manual).mode)
        // ручной режим закончился — снова расписание
        assertEquals(Modes.STUDY, Scheduler.resolve(z(16, 11, 1), listOf(study), manual).mode)
    }

    @Test
    fun plainManualDoesNotOverrideStrictSchedule() {
        val strictStudy = study.copy(strict = true)
        val manual = ManualMode(Modes.REST, z(16, 20).toInstant().toEpochMilli(), strict = false)
        val a = Scheduler.resolve(z(16, 10), listOf(strictStudy), manual)
        assertEquals(Modes.STUDY, a.mode)
        assertTrue(a.strict)
        // после конца упрямого расписания действует ручной
        assertEquals(Modes.REST, Scheduler.resolve(z(16, 18, 30), listOf(strictStudy), manual).mode)
    }

    @Test
    fun strictManualWins() {
        val manual = ManualMode(Modes.TALK, z(16, 12).toInstant().toEpochMilli(), strict = true)
        val a = Scheduler.resolve(z(16, 10), listOf(study.copy(strict = true)), manual)
        assertEquals(Modes.TALK, a.mode)
        assertEquals(ModeSource.MANUAL, a.source)
        assertTrue(a.strict)
    }

    @Test
    fun describeAndJson() {
        assertEquals("Пн–Пт 09:00–18:00 · Учёба/Работа", study.describe())
        assertEquals(study, ScheduleEntry.fromJson(MiniJson.parseObject(MiniJson.write(study.toJson()))))
        val m = ManualMode(Modes.TALK, 123456789L, true)
        assertEquals(m, ManualMode.fromJson(MiniJson.parseObject(MiniJson.write(m.toJson()))))
        assertEquals("Пн,Ср", ScheduleEntry.daysText(0b101))
    }
}
