package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Счётчики, открытия и полночь. */
class UsageTest {
    private val d1 = "2026-11-18"
    private val d2 = "2026-11-19"
    private val tgChannel = Key("telegram", ContentType.CHANNEL)
    private val tgChat = Key("telegram", ContentType.CHAT)

    private fun det(type: ContentType, title: String? = null, svc: String = "telegram") = Detection(svc, type, title, "r")

    private val noLimits = CtCore.Config(ModeRules(), "Обычный", emptyList())

    /** Прогоняет n тиков по 1 с. */
    private fun run(core: CtCore, date: String, d: Detection?, from: Long, n: Int, cfg: CtCore.Config = noLimits): Long {
        var t = from
        repeat(n) {
            t += 1000
            core.tick(date, d, t, 1000, cfg)
        }
        return t
    }

    @Test
    fun secondsAreCountedPerServiceAndType() {
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHANNEL), 0, 30)
        t = run(core, d1, det(ContentType.CHAT), t, 10)
        run(core, d1, null, t, 100) // другое приложение — не считается
        assertEquals(30, core.book.seconds(d1, tgChannel))
        assertEquals(10, core.book.seconds(d1, tgChat))
    }

    @Test
    fun partialSecondsAccumulate() {
        val core = CtCore(UsageBook())
        var t = 0L
        repeat(8) {
            t += 250
            core.tick(d1, det(ContentType.SHORTS, svc = "youtube"), t, 250, noLimits)
        }
        assertEquals(2, core.book.seconds(d1, Key("youtube", ContentType.SHORTS)))
    }

    @Test
    fun openIsCountedOnEnterNotEachTick() {
        val core = CtCore(UsageBook())
        run(core, d1, det(ContentType.CHANNEL), 0, 60)
        assertEquals(1, core.book.opens(d1, tgChannel))
    }

    @Test
    fun shortInterruptionIsSameOpen() {
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHANNEL), 0, 5)
        t = run(core, d1, null, t, 2)                              // шторка или другое приложение 2 с
        t = run(core, d1, det(ContentType.UNKNOWN), t, 2)          // мелькнул неизвестный экран
        run(core, d1, det(ContentType.CHANNEL), t, 5)
        assertEquals(1, core.book.opens(d1, tgChannel))
    }

    @Test
    fun openGapIsFiveSeconds() {
        assertEquals(5_000L, CtCore.OPEN_GAP_MS)
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHANNEL), 0, 3)
        t = run(core, d1, null, t, 4)                              // 4 с в другом приложении — то же открытие
        t = run(core, d1, det(ContentType.CHANNEL), t, 3)
        assertEquals(1, core.book.opens(d1, tgChannel))
        t = run(core, d1, null, t, 7)                              // 7 с — новое открытие
        run(core, d1, det(ContentType.CHANNEL), t, 3)
        assertEquals(2, core.book.opens(d1, tgChannel))
    }

    @Test
    fun enteringClosedTypeIsNotAnOpen() {
        val closed = CtCore.Config(ModeRules().with("telegram", ContentType.CHANNEL, Limit(minutes = 0)), "Только общение", emptyList())
        val core = CtCore(UsageBook())
        var t = 0L
        repeat(3) {
            t = run(core, d1, det(ContentType.CHANNEL), t, 2, closed)
            t = run(core, d1, det(ContentType.CHAT), t, 10, closed)
        }
        assertEquals("закрытые попытки — не открытия", 0, core.book.opens(d1, tgChannel))
        run(core, d1, det(ContentType.CHANNEL), t, 2)                // режим без ограничений
        assertEquals(1, core.book.opens(d1, tgChannel))
    }

    @Test
    fun titleIsHeldWhenHeaderBlinks() {
        val ex = listOf(ExRule("telegram", "Учебный канал", ExKind.ALLOW))
        val cfg = CtCore.Config(ModeRules().with("telegram", ContentType.CHANNEL, Limit(minutes = 0)), "Только общение", ex)
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHANNEL, "Учебный канал"), 0, 3, cfg)
        // шапка в анимации 2 с: название не найдено — исключение продолжает действовать
        repeat(8) {
            t += 250
            assertNull(core.tick(d1, det(ContentType.CHANNEL), t, 250, cfg))
            assertTrue(core.last!!.free)
            assertEquals("Учебный канал", core.lastDetection!!.title)
        }
        // названия нет дольше 3 с — это уже не тот канал, закрывается
        var blocked: Verdict? = null
        repeat(12) {
            t += 250
            blocked = core.tick(d1, det(ContentType.CHANNEL), t, 250, cfg) ?: blocked
        }
        assertNotNull(blocked)
    }

    @Test
    fun returnAfterGapIsNewOpen() {
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHANNEL), 0, 5)
        t = run(core, d1, null, t, 30)
        run(core, d1, det(ContentType.CHANNEL), t, 5)
        assertEquals(2, core.book.opens(d1, tgChannel))
    }

    @Test
    fun switchingTypesCountsOpensForEach() {
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHAT), 0, 3)
        t = run(core, d1, det(ContentType.CHANNEL), t, 3)
        t = run(core, d1, det(ContentType.CHAT), t, 3)
        run(core, d1, det(ContentType.CHANNEL), t, 3)
        assertEquals(2, core.book.opens(d1, tgChannel))
        assertEquals(2, core.book.opens(d1, tgChat))
    }

    @Test
    fun unknownInSameServiceKeepsOpenAlive() {
        val core = CtCore(UsageBook())
        var t = run(core, d1, det(ContentType.CHANNEL), 0, 3)
        t = run(core, d1, det(ContentType.UNKNOWN), t, 40) // профиль канала, не распознан
        run(core, d1, det(ContentType.CHANNEL), t, 3)
        assertEquals(1, core.book.opens(d1, tgChannel))
        assertEquals(40, core.book.seconds(d1, Key("telegram", ContentType.UNKNOWN)))
    }

    @Test
    fun midnightStartsNewDayAndCountsOngoingOpen() {
        val core = CtCore(UsageBook())
        val t = run(core, d1, det(ContentType.SHORTS, svc = "vk"), 0, 20)
        run(core, d2, det(ContentType.SHORTS, svc = "vk"), t, 15)
        val k = Key("vk", ContentType.SHORTS)
        assertEquals(20, core.book.seconds(d1, k))
        assertEquals(15, core.book.seconds(d2, k))
        assertEquals(1, core.book.opens(d1, k))
        assertEquals(1, core.book.opens(d2, k))
    }

    @Test
    fun minutesLimitResetsAtMidnight() {
        val rules = ModeRules().with("vk", ContentType.FEED, Limit(minutes = 1))
        val cfg = CtCore.Config(rules, "Обычный", emptyList())
        val core = CtCore(UsageBook())
        var t = 0L
        var blocked: Verdict? = null
        repeat(70) {
            t += 1000
            blocked = core.tick(d1, det(ContentType.FEED, svc = "vk"), t, 1000, cfg) ?: blocked
        }
        assertNotNull("за 70 с при лимите 1 мин должна быть блокировка", blocked)
        assertEquals(BlockKind.MINUTES, blocked!!.kind)
        // новые сутки — снова можно
        t += 1000
        assertNull(core.tick(d2, det(ContentType.FEED, svc = "vk"), t, 1000, cfg))
        assertFalse(core.last!!.blocked)
    }

    @Test
    fun graceGivesTimeToLeave() {
        val rules = ModeRules().with("telegram", ContentType.CHANNEL, Limit(minutes = 0))
        val cfg = CtCore.Config(rules, "Учёба/Работа", emptyList())
        val core = CtCore(UsageBook())
        assertNull(core.tick(d1, det(ContentType.CHANNEL), 1000, 1000, cfg))
        assertTrue(core.inGrace)
        assertNull(core.tick(d1, det(ContentType.CHANNEL), 2000, 1000, cfg))   // 1 с — ещё рано
        // ушёл в чат за 1,5 с — блокировки нет
        assertNull(core.tick(d1, det(ContentType.CHAT), 2400, 400, cfg))
        assertFalse(core.inGrace)
        // вернулся и остался — блокировка через 1,5 с
        assertNull(core.tick(d1, det(ContentType.CHANNEL), 3000, 600, cfg))
        val v = core.tick(d1, det(ContentType.CHANNEL), 4500, 1500, cfg)
        assertNotNull(v)
        assertEquals(BlockKind.CLOSED, v!!.kind)
    }

    @Test
    fun opensLimitBlocksWithoutCountingBlockedAttempt() {
        val rules = ModeRules().with("telegram", ContentType.CHANNEL, Limit(opens = 2))
        val cfg = CtCore.Config(rules, "Обычный", emptyList())
        val core = CtCore(UsageBook())
        var t = 0L
        fun visit(): Verdict? {
            var res: Verdict? = null
            repeat(3) {
                t += 1000
                res = core.tick(d1, det(ContentType.CHANNEL), t, 1000, cfg) ?: res
            }
            repeat(15) {
                t += 1000
                core.tick(d1, det(ContentType.CHAT), t, 1000, cfg)
            }
            return res
        }
        assertNull(visit())
        assertNull(visit())
        val third = visit()
        assertNotNull(third)
        assertEquals(BlockKind.OPENS, third!!.kind)
        assertEquals(2, core.book.opens(d1, tgChannel))
    }

    @Test
    fun allowedExceptionTimeIsFree() {
        val ex = listOf(ExRule("telegram", "Учебный канал", ExKind.ALLOW))
        val rules = ModeRules().with("telegram", ContentType.CHANNEL, Limit(minutes = 1))
        val cfg = CtCore.Config(rules, "Обычный", ex)
        val core = CtCore(UsageBook())
        var t = 0L
        repeat(120) {
            t += 1000
            assertNull(core.tick(d1, det(ContentType.CHANNEL, "Учебный  канал"), t, 1000, cfg))
        }
        assertEquals(0, core.book.seconds(d1, tgChannel))
        assertEquals(120, core.book.freeSeconds(d1, tgChannel))
        // другой канал — лимит ещё целый
        t += 1000
        core.tick(d1, det(ContentType.CHANNEL, "Новости"), t, 1000, cfg)
        assertFalse(core.last!!.blocked)
    }

    @Test
    fun cleanupKeeps60Days() {
        val b = UsageBook()
        b.addSeconds("2026-09-18", tgChat, 5)
        b.addSeconds("2026-09-19", tgChat, 7)
        b.addOpen("2026-11-18", tgChat)
        val border = java.time.LocalDate.parse("2026-11-18").minusDays(UsageBook.KEEP_DAYS).toString()
        assertEquals("2026-09-19", border)
        assertEquals(1, b.cleanup(border))
        assertEquals(0, b.seconds("2026-09-18", tgChat))
        assertEquals(7, b.seconds("2026-09-19", tgChat))
        assertEquals(1, b.opens("2026-11-18", tgChat))
    }

    @Test
    fun groupSecondsSumAcrossServices() {
        val b = UsageBook()
        b.addSeconds(d1, Key("vk", ContentType.FEED), 100)
        b.addSeconds(d1, Key("youtube", ContentType.SHORTS), 50)
        b.addSeconds(d1, Key("youtube", ContentType.SHORTS), 10, free = true)
        b.addSeconds(d1, Key("telegram", ContentType.CHAT), 999)
        b.addSeconds(d2, Key("vk", ContentType.FEED), 1)
        assertEquals(150, b.groupSeconds(d1, setOf(ContentType.FEED, ContentType.SHORTS)))
    }

    @Test
    fun drainChangesGivesOnlyNewValuesAndRemovedKeys() {
        val b = UsageBook()
        b.load(mapOf(UsageBook.key("2026-01-01", tgChat, 's') to 9, UsageBook.key(d1, tgChat, 's') to 1))
        b.addSeconds(d1, tgChat, 4)
        b.addOpen(d1, tgChannel)
        b.cleanup("2026-09-19")
        val (upd, rem) = b.drainChanges()
        assertEquals(mapOf(UsageBook.key(d1, tgChat, 's') to 5, UsageBook.key(d1, tgChannel, 'o') to 1), upd)
        assertEquals(setOf(UsageBook.key("2026-01-01", tgChat, 's')), rem)
        assertFalse(b.dirty)
        assertEquals(Pair(emptyMap<String, Int>(), emptySet<String>()), b.drainChanges())
    }

    @Test
    fun exportAndLoad() {
        val b = UsageBook()
        b.addSeconds(d1, tgChat, 42)
        assertTrue(b.dirty)
        val c = UsageBook()
        c.load(b.export())
        assertFalse(c.dirty)
        assertEquals(42, c.seconds(d1, tgChat))
    }
}
