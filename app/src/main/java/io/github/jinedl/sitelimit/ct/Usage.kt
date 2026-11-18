package io.github.jinedl.sitelimit.ct

/**
 * Счётчики по дням, сервисам и типам: секунды (s), секунды по исключению «всегда можно» (f,
 * в лимиты не входят) и открытия (o). Ключ — "дата|сервис|тип|вид", дата ISO (2026-11-18),
 * поэтому сравнение строк = сравнение дат. Хранится в памяти, на диск — пачкой (CtStore).
 */
class UsageBook {
    private val map = HashMap<String, Int>()

    /** Были изменения с последнего сохранения. */
    var dirty = false
        private set

    fun seconds(date: String, k: Key): Int = map[key(date, k, 's')] ?: 0
    fun freeSeconds(date: String, k: Key): Int = map[key(date, k, 'f')] ?: 0
    fun opens(date: String, k: Key): Int = map[key(date, k, 'o')] ?: 0

    fun addSeconds(date: String, k: Key, s: Int, free: Boolean = false) {
        if (s <= 0) return
        val kk = key(date, k, if (free) 'f' else 's')
        map[kk] = (map[kk] ?: 0) + s
        dirty = true
    }

    fun addOpen(date: String, k: Key) {
        val kk = key(date, k, 'o')
        map[kk] = (map[kk] ?: 0) + 1
        dirty = true
    }

    /** Секунды в лимит по набору типов во всех сервисах (для общих лимитов). */
    fun groupSeconds(date: String, types: Set<ContentType>): Int {
        val prefix = "$date|"
        var sum = 0
        for ((k, v) in map) {
            if (!k.startsWith(prefix) || !k.endsWith("|s")) continue
            val parts = k.split('|')
            if (parts.size == 4 && ContentType.byId(parts[2]) in types) sum += v
        }
        return sum
    }

    /** Удаляет записи раньше даты border (как у сайтов — хранение 60 дней). Возвращает число удалённых. */
    fun cleanup(border: String): Int {
        val old = map.keys.filter { it.substringBefore('|') < border }
        old.forEach { map.remove(it) }
        if (old.isNotEmpty()) dirty = true
        return old.size
    }

    fun load(values: Map<String, Int>) {
        map.clear()
        map.putAll(values)
        dirty = false
    }

    fun export(): Map<String, Int> = HashMap(map)

    fun markSaved() {
        dirty = false
    }

    companion object {
        const val KEEP_DAYS = 60L

        fun key(date: String, k: Key, kind: Char) = "$date|${k.service}|${k.type.id}|$kind"
    }
}

/**
 * Что считается открытием: переход в тип (сервис + тип) из другого типа, другого приложения
 * или с выключенного экрана — если с прошлого раза, когда этот тип был на экране, прошло больше gapMs.
 * Возврат в тот же тип быстрее gapMs (шторка уведомлений, мелькнувший неизвестный экран,
 * короткий переход в другое приложение) — то же открытие. Неизвестный экран в том же сервисе
 * продлевает открытие. В полночь идущее открытие засчитывается и новым суткам.
 */
class OpenTracker(private val gapMs: Long = 10_000) {
    var current: Key? = null
        private set
    private var lastSeen = Long.MIN_VALUE / 4
    private var date: String? = null

    /** true — началось новое открытие. key == null — не в сервисе (другое приложение, экран выключен). */
    fun update(date: String, key: Key?, now: Long): Boolean {
        if (key == null) return false
        if (key.type == ContentType.UNKNOWN) {
            if (current?.service == key.service && now - lastSeen <= gapMs) lastSeen = now
            return false
        }
        val isNew = key != current || now - lastSeen > gapMs || date != this.date
        current = key
        lastSeen = now
        this.date = date
        return isNew
    }
}
