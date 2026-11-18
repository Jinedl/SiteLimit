package io.github.jinedl.sitelimit.ct

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Запись расписания: режим, дни недели (бит 0 — понедельник … бит 6 — воскресенье),
 * начало и конец в минутах от полуночи. Конец раньше начала — через полночь (23:00–07:00:
 * начинается в отмеченный день). Конец = начало — весь день. strict — упрямый режим.
 */
data class ScheduleEntry(val mode: String, val days: Int, val start: Int, val end: Int, val strict: Boolean) {
    fun hasDay(d: DayOfWeek): Boolean = days and (1 shl (d.value - 1)) != 0

    fun describe(): String = "${daysText(days)} ${hhmm(start)}–${hhmm(end)} · ${Modes.title(mode)}" +
        if (strict) " · упрямый" else ""

    fun toJson(): Map<String, Any?> = linkedMapOf("mode" to mode, "days" to days, "start" to start, "end" to end, "strict" to strict)

    companion object {
        fun fromJson(m: Map<String, Any?>): ScheduleEntry? {
            val mode = m.str("mode") ?: return null
            val days = m.int("days") ?: return null
            val start = m.int("start")?.takeIf { it in 0 until 1440 } ?: return null
            val end = m.int("end")?.takeIf { it in 0 until 1440 } ?: return null
            return ScheduleEntry(mode, days and 0x7F, start, end, m.bool("strict") ?: false)
        }

        private val SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

        fun daysText(days: Int): String = when (days and 0x7F) {
            0x7F -> "каждый день"
            0x1F -> "Пн–Пт"
            0x60 -> "Сб–Вс"
            else -> SHORT.indices.filter { days and (1 shl it) != 0 }.joinToString(",") { SHORT[it] }
        }

        fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
    }
}

/** Ручной режим на срок: until — время окончания (мс с 1970). */
data class ManualMode(val mode: String, val until: Long, val strict: Boolean) {
    fun toJson(): Map<String, Any?> = linkedMapOf("mode" to mode, "until" to until, "strict" to strict)

    companion object {
        fun fromJson(m: Map<String, Any?>?): ManualMode? {
            if (m == null) return null
            return ManualMode(m.str("mode") ?: return null, m.long("until") ?: return null, m.bool("strict") ?: false)
        }
    }
}

enum class ModeSource { DEFAULT, SCHEDULE, MANUAL }

/** Действующий режим: откуда он, до какого времени (мс) и упрямый ли. */
data class ActiveMode(val mode: String, val source: ModeSource, val until: Long?, val strict: Boolean) {
    fun describe(zone: ZoneId = ZoneId.systemDefault()): String {
        val base = "«${Modes.title(mode)}»"
        val src = when (source) {
            ModeSource.DEFAULT -> ""
            ModeSource.SCHEDULE -> " по расписанию"
            ModeSource.MANUAL -> " вручную"
        }
        val till = until?.let { " до " + timeText(it, zone) } ?: ""
        return base + src + till + if (strict) " 🔒 упрямый" else ""
    }

    companion object {
        fun timeText(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
            val now = ZonedDateTime.now(zone)
            val hm = "%02d:%02d".format(t.hour, t.minute)
            return if (t.toLocalDate() == now.toLocalDate()) hm else "%02d.%02d %s".format(t.dayOfMonth, t.monthValue, hm)
        }
    }
}

object Scheduler {

    fun covers(e: ScheduleEntry, t: LocalDateTime): Boolean {
        val m = t.hour * 60 + t.minute
        val today = t.dayOfWeek
        return when {
            e.start < e.end -> e.hasDay(today) && m >= e.start && m < e.end
            e.start > e.end -> (e.hasDay(today) && m >= e.start) || (e.hasDay(today.minus(1)) && m < e.end)
            else -> e.hasDay(today)
        }
    }

    /** Когда закончится запись, которая сейчас действует. */
    fun endOf(e: ScheduleEntry, t: LocalDateTime): LocalDateTime {
        val m = t.hour * 60 + t.minute
        val day = t.toLocalDate()
        return when {
            e.start < e.end -> day.atTime(e.end / 60, e.end % 60)
            e.start > e.end -> if (m >= e.start) day.plusDays(1).atTime(e.end / 60, e.end % 60) else day.atTime(e.end / 60, e.end % 60)
            else -> day.plusDays(1).atStartOfDay()
        }
    }

    /**
     * Порядок: упрямый ручной → упрямое расписание → ручной → расписание → «Обычный».
     * Так обычный ручной режим не отменяет упрямое расписание.
     */
    fun resolve(now: ZonedDateTime, entries: List<ScheduleEntry>, manual: ManualMode?): ActiveMode {
        val nowMs = now.toInstant().toEpochMilli()
        val local = now.toLocalDateTime()
        val man = manual?.takeIf { nowMs < it.until }
        val sched = entries.filter { covers(it, local) }
        fun fromEntry(e: ScheduleEntry) = ActiveMode(
            e.mode, ModeSource.SCHEDULE, endOf(e, local).atZone(now.zone).toInstant().toEpochMilli(), e.strict,
        )
        if (man != null && man.strict) return ActiveMode(man.mode, ModeSource.MANUAL, man.until, true)
        sched.firstOrNull { it.strict }?.let { return fromEntry(it) }
        if (man != null) return ActiveMode(man.mode, ModeSource.MANUAL, man.until, false)
        sched.firstOrNull()?.let { return fromEntry(it) }
        return ActiveMode(Modes.NORMAL, ModeSource.DEFAULT, null, false)
    }
}
