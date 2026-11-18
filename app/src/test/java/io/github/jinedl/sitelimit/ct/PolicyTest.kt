package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Упрямый режим, исключения, решения и пресеты. */
class PolicyTest {
    private val services = listOf("telegram", "max", "vk", "youtube")
    private val date = "2026-11-18"

    // ---- упрямый режим ----

    @Test
    fun weakerLimit() {
        assertTrue(StrictGuard.weakerLimit(Limit(15), Limit(20)))
        assertTrue(StrictGuard.weakerLimit(Limit(15), Limit()))           // снять лимит
        assertTrue(StrictGuard.weakerLimit(Limit(0), Limit(5)))           // открыть закрытое
        assertTrue(StrictGuard.weakerLimit(Limit(opens = 3), Limit(opens = 5)))
        assertFalse(StrictGuard.weakerLimit(Limit(15), Limit(10)))
        assertFalse(StrictGuard.weakerLimit(Limit(), Limit(30)))          // поставить лимит — можно
        assertFalse(StrictGuard.weakerLimit(Limit(15, 3), Limit(15, 3)))
    }

    @Test
    fun weakerRulesIncludingGroups() {
        val study = Presets.build(Modes.STUDY, services)
        assertFalse(StrictGuard.weakerRules(study, study))
        assertTrue(StrictGuard.weakerRules(study, study.with("vk", ContentType.FEED, Limit(10))))
        assertFalse(StrictGuard.weakerRules(study, study.with("vk", ContentType.CHAT, Limit(30))))
        assertTrue(StrictGuard.weakerRules(study, study.copy(groups = emptyList())))
        assertTrue(StrictGuard.weakerRules(study, study.copy(groups = listOf(Group(setOf(ContentType.CHANNEL), 30)))))
        assertFalse(StrictGuard.weakerRules(study, study.copy(groups = listOf(Group(setOf(ContentType.CHANNEL), 5)))))
        assertTrue(StrictGuard.weakerRules(study, Presets.build(Modes.REST, services)))
        assertFalse(StrictGuard.weakerRules(Presets.build(Modes.REST, services),
            Presets.build(Modes.REST, services).with("youtube", ContentType.SHORTS, Limit(0))))
    }

    @Test
    fun weakerExceptions() {
        val deny = ExRule("telegram", "Мемы", ExKind.DENY)
        val allow = ExRule("telegram", "Учёба", ExKind.ALLOW)
        assertTrue(StrictGuard.weakerExceptions(emptyList(), listOf(allow)))
        assertFalse(StrictGuard.weakerExceptions(emptyList(), listOf(deny)))
        assertTrue(StrictGuard.weakerExceptions(listOf(deny), emptyList()))
        assertFalse(StrictGuard.weakerExceptions(listOf(allow), emptyList()))
        assertTrue(StrictGuard.weakerExceptions(listOf(ExRule("telegram", "Чат курса", ExKind.AS_CHANNEL)), emptyList()))
    }

    @Test
    fun weakerSitesAndPackages() {
        assertTrue(StrictGuard.weakerSiteLimit(30, null))
        assertTrue(StrictGuard.weakerSiteLimit(30, 45))
        assertFalse(StrictGuard.weakerSiteLimit(30, 10))
        assertFalse(StrictGuard.weakerSiteLimit(null, 10))
        assertTrue(StrictGuard.weakerPackages(listOf("a", "b"), listOf("a")))
        assertFalse(StrictGuard.weakerPackages(listOf("a"), listOf("a", "b")))
    }

    // ---- исключения ----

    @Test
    fun titleMatchingIgnoresCaseSpacesAndInvisibleChars() {
        val ex = listOf(ExRule("telegram", "  Новости   Москвы ", ExKind.DENY), ExRule(Exceptions.ANY, "Общий", ExKind.ALLOW))
        assertEquals(setOf(ExKind.DENY), Exceptions.forTitle(ex, "telegram", "новости​ москвы"))
        assertEquals(emptySet<ExKind>(), Exceptions.forTitle(ex, "max", "Новости Москвы"))
        assertEquals(setOf(ExKind.ALLOW), Exceptions.forTitle(ex, "max", "общий"))
        assertEquals(emptySet<ExKind>(), Exceptions.forTitle(ex, "telegram", null))
    }

    @Test
    fun siteExceptionsWithSubdomains() {
        val ex = listOf(
            ExRule(Exceptions.SITE, "youtube.com", ExKind.DENY),
            ExRule(Exceptions.SITE, "music.youtube.com", ExKind.ALLOW),
        )
        assertEquals(ExKind.DENY, Exceptions.forSite(ex, "m.youtube.com")?.kind)
        assertEquals(ExKind.ALLOW, Exceptions.forSite(ex, "music.youtube.com")?.kind)
        assertNull(Exceptions.forSite(ex, "notyoutube.com"))
        val tie = listOf(ExRule(Exceptions.SITE, "vk.com", ExKind.ALLOW), ExRule(Exceptions.SITE, "vk.com", ExKind.DENY))
        assertEquals(ExKind.DENY, Exceptions.forSite(tie, "vk.com")?.kind)
    }

    @Test
    fun bigChatAsChannel() {
        val ex = listOf(ExRule("telegram", "Поток 2026", ExKind.AS_CHANNEL))
        val det = Detection("telegram", ContentType.CHAT, "Поток 2026", "tg_group")
        assertEquals(ContentType.CHANNEL, Decider.effectiveType(det, ex))
        assertEquals(ContentType.CHAT, Decider.effectiveType(det.copy(title = "Другой чат"), ex))
        // «считать каналом» не меняет другие типы
        assertEquals(ContentType.SHORTS, Decider.effectiveType(det.copy(type = ContentType.SHORTS), ex))
    }

    // ---- решения ----

    private fun decide(det: Detection, rules: ModeRules, ex: List<ExRule> = emptyList(), book: UsageBook = UsageBook()) =
        Decider.decide(det, Decider.effectiveType(det, ex), rules, "Тест", ex, book, date, false)

    @Test
    fun unknownIsNeverBlocked() {
        val closedAll = ModeRules(mapOf("telegram" to ContentType.entries.associateWith { Limit(0) }))
        assertFalse(decide(Detection("telegram", ContentType.UNKNOWN), closedAll).blocked)
    }

    @Test
    fun denyAndAllowExceptions() {
        val rules = ModeRules().with("telegram", ContentType.CHANNEL, Limit(0))
        val ex = listOf(ExRule("telegram", "Учёба", ExKind.ALLOW), ExRule("telegram", "Мемы", ExKind.DENY))
        assertFalse(decide(Detection("telegram", ContentType.CHANNEL, "Учёба"), rules, ex).blocked)
        val v = decide(Detection("telegram", ContentType.CHAT, "Мемы"), ModeRules(), ex)
        assertTrue(v.blocked)
        assertEquals(BlockKind.DENY, v.kind)
        assertTrue(decide(Detection("telegram", ContentType.CHANNEL, "Другое"), rules, ex).blocked)
    }

    @Test
    fun groupLimitAcrossServices() {
        val rest = Presets.build(Modes.REST, services)
        val book = UsageBook()
        book.addSeconds(date, Key("vk", ContentType.FEED), 40 * 60)
        val left = decide(Detection("youtube", ContentType.SHORTS), rest, book = book)
        assertFalse(left.blocked)
        assertEquals(20 * 60, left.remainingSec)
        book.addSeconds(date, Key("youtube", ContentType.VIDEO), 20 * 60)
        val v = decide(Detection("vk", ContentType.FEED), rest, book = book)
        assertTrue(v.blocked)
        assertEquals(BlockKind.GROUP, v.kind)
        // переписка в общий лимит не входит
        assertFalse(decide(Detection("vk", ContentType.CHAT), rest, book = book).blocked)
    }

    @Test
    fun remainingIsMinOfLimits() {
        val rules = ModeRules(
            mapOf("telegram" to mapOf(ContentType.CHANNEL to Limit(minutes = 30, opens = 5))),
            listOf(Group(setOf(ContentType.CHANNEL), 15)),
        )
        val book = UsageBook()
        book.addSeconds(date, Key("telegram", ContentType.CHANNEL), 5 * 60)
        book.addOpen(date, Key("telegram", ContentType.CHANNEL))
        val v = decide(Detection("telegram", ContentType.CHANNEL), rules, book = book)
        assertEquals(10 * 60, v.remainingSec)
        assertEquals(4, v.remainingOpens)
    }

    // ---- пресеты ----

    @Test
    fun presetsMatchKb01() {
        val talk = Presets.build(Modes.TALK, services)
        for (s in services) {
            assertTrue(talk.limit(s, ContentType.CHAT).isEmpty)
            assertTrue(talk.limit(s, ContentType.SERVICE).isEmpty)
            for (t in listOf(ContentType.CHANNEL, ContentType.FEED, ContentType.SHORTS, ContentType.VIDEO)) {
                assertTrue("$s $t", talk.limit(s, t).isClosed)
            }
        }
        val study = Presets.build(Modes.STUDY, services)
        assertTrue(study.limit("telegram", ContentType.CHAT).isEmpty)
        assertTrue(study.limit("telegram", ContentType.CHANNEL).isEmpty)
        assertEquals(listOf(Group(setOf(ContentType.CHANNEL), 15)), study.groups)
        assertTrue(study.limit("youtube", ContentType.SHORTS).isClosed)
        val rest = Presets.build(Modes.REST, services)
        assertTrue(rest.limits.isEmpty())
        assertEquals(60, rest.groups.single().minutes)
        assertEquals(ModeRules(), Presets.forMode(Modes.NORMAL, services))
    }

    @Test
    fun modeRulesJsonRoundTrip() {
        val r = Presets.build(Modes.STUDY, services).with("vk", ContentType.CHAT, Limit(minutes = 90, opens = 20))
        assertEquals(r, ModeRules.fromJson(MiniJson.parseObject(MiniJson.write(r.toJson()))))
        val ex = listOf(ExRule("vk", "Паблик", ExKind.DENY), ExRule(Exceptions.SITE, "vk.com", ExKind.ALLOW))
        assertEquals(ex, Exceptions.fromJson(MiniJson.parseList(MiniJson.write(Exceptions.toJson(ex)))))
    }
}
