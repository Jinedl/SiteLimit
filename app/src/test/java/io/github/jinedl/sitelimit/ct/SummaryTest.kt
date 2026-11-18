package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTest {
    private val date = "2026-11-18"
    private val services = listOf("telegram" to "Telegram", "vk" to "VK")

    @Test
    fun noLimits() {
        assertEquals(listOf("ограничений нет"), Summary.remaining(ModeRules(), UsageBook(), date, services))
    }

    @Test
    fun studyPreset() {
        val rules = Presets.build(Modes.STUDY, services.map { it.first })
        val book = UsageBook()
        book.addSeconds(date, Key("telegram", ContentType.CHANNEL), 5 * 60 + 30)
        assertEquals(
            listOf(
                "каналы (все сервисы): осталось 10 из 15 мин",
                "Telegram: закрыто — лента, короткие ролики, длинное видео",
                "VK: закрыто — лента, короткие ролики, длинное видео",
            ),
            Summary.remaining(rules, book, date, services),
        )
    }

    @Test
    fun perTypeMinutesAndOpens() {
        val rules = ModeRules().with("vk", ContentType.FEED, Limit(minutes = 20, opens = 3))
        val book = UsageBook()
        val k = Key("vk", ContentType.FEED)
        book.addSeconds(date, k, 25 * 60)
        repeat(4) { book.addOpen(date, k) }
        assertEquals(listOf("VK · лента: 20 из 20 мин — исчерпан, открытий 3 из 3"), Summary.remaining(rules, book, date, services))
        assertEquals("сегодня 25 мин, 4 откр.", Summary.today(book, date, k))
    }
}
