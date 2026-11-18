package io.github.jinedl.sitelimit.ct

/**
 * Тип контента (решение kb/04 от 18.11.2026). Смысл контента не оценивается — только тип экрана.
 * id — для файла правил и настроек, title — для экранов.
 */
enum class ContentType(val id: String, val title: String, val short: String) {
    CHAT("chat", "Переписка", "переписка"),
    CHANNEL("channel", "Каналы и паблики", "каналы"),
    FEED("feed", "Лента и рекомендации", "лента"),
    SHORTS("shorts", "Короткие ролики", "короткие ролики"),
    VIDEO("video", "Длинное видео", "длинное видео"),
    SERVICE("service", "Поиск и служебное", "поиск и служебное"),
    UNKNOWN("unknown", "Неизвестно", "неизвестно");

    companion object {
        /** Типы, на которые ставятся лимиты. «Неизвестно» не ограничивается никогда. */
        val LIMITED: List<ContentType> = listOf(CHAT, CHANNEL, FEED, SHORTS, VIDEO, SERVICE)

        fun byId(id: String?): ContentType? = entries.firstOrNull { it.id == id }
    }
}

/** Сервис и тип — ключ счётчиков и лимитов. */
data class Key(val service: String, val type: ContentType)
