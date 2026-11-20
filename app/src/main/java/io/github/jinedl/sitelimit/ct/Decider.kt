package io.github.jinedl.sitelimit.ct

enum class BlockKind { DENY, CLOSED, MINUTES, OPENS, GROUP }

/**
 * Решение по текущему экрану. remainingSec / remainingOpens — остаток по самому строгому
 * из действующих лимитов (null — лимита нет).
 */
data class Verdict(
    val blocked: Boolean,
    val kind: BlockKind? = null,
    val reason: String = "",
    val type: ContentType = ContentType.UNKNOWN,
    val free: Boolean = false,
    val remainingSec: Int? = null,
    val remainingOpens: Int? = null,
)

/** Решения по типам: исключения → закрыто → минуты → открытия → общие лимиты. Неизвестное не блокируется. */
object Decider {

    /** Тип с учётом исключения «считать каналом» (большой чат → каналы). */
    fun effectiveType(det: Detection, ex: List<ExRule>): ContentType {
        if (det.type == ContentType.CHAT && ExKind.AS_CHANNEL in Exceptions.forTitle(ex, det.service, det.title)) {
            return ContentType.CHANNEL
        }
        return det.type
    }

    fun decide(
        det: Detection,
        type: ContentType,
        rules: ModeRules,
        modeTitle: String,
        ex: List<ExRule>,
        book: UsageBook,
        date: String,
        sessionBlockedByOpens: Boolean,
    ): Verdict {
        if (type == ContentType.UNKNOWN) return Verdict(false, type = type)
        val kinds = Exceptions.forTitle(ex, det.service, det.title)
        if (ExKind.DENY in kinds) {
            return Verdict(true, BlockKind.DENY, "«${det.title}» — всегда нельзя (исключение)", type)
        }
        if (ExKind.ALLOW in kinds) return Verdict(false, type = type, free = true)

        val key = Key(det.service, type)
        val lim = rules.limit(det.service, type)
        if (lim.isClosed) return Verdict(true, BlockKind.CLOSED, "закрыто в режиме «$modeTitle»", type)

        var remaining: Int? = null
        lim.minutes?.let { m ->
            val left = m * 60 - book.seconds(date, key)
            if (left <= 0) return Verdict(true, BlockKind.MINUTES, "лимит $m мин на сегодня исчерпан", type)
            remaining = left
        }
        var remainingOpens: Int? = null
        lim.opens?.let { o ->
            if (sessionBlockedByOpens) return Verdict(true, BlockKind.OPENS, "лимит открытий ($o в день) исчерпан", type)
            remainingOpens = (o - book.opens(date, key)).coerceAtLeast(0)
        }
        for (g in rules.groups) {
            if (type !in g.types) continue
            val left = g.minutes * 60 - book.groupSeconds(date, g.types)
            if (g.minutes == 0 || left <= 0) {
                return Verdict(true, BlockKind.GROUP, "общий лимит «${g.title()}» — ${g.minutes} мин — исчерпан", type)
            }
            remaining = minOf(remaining ?: left, left)
        }
        return Verdict(false, type = type, remainingSec = remaining, remainingOpens = remainingOpens)
    }
}

/**
 * Подсчёт и решение за один тик службы — без Android, чтобы проверять тестами.
 * Отвечает за секунды, открытия, полночь и 1,5 с на переход (как у сайтов).
 *
 * Открытие — вход в тип после перерыва больше gapMs (0.10-beta2: 5 с, kb/04 «Открыто»).
 * Вход в закрытый тип (закрыто, минуты исчерпаны, «всегда нельзя») открытием не считается:
 * его всё равно закроют через 1,5 с.
 */
class CtCore(val book: UsageBook, gapMs: Long = OPEN_GAP_MS, private val graceMs: Long = 1_500) {

    companion object {
        const val OPEN_GAP_MS = 5_000L
        /** Сколько держать название канала или чата, если на одном разборе его не нашлось (анимация шапки). */
        const val TITLE_HOLD_MS = 3_000L
    }

    class Config(val rules: ModeRules, val modeTitle: String, val exceptions: List<ExRule>)

    private val tracker = OpenTracker(gapMs)
    private var sessionKey: Key? = null
    private var sessionBlockedByOpens = false
    private var pendingMs = 0L
    private var overKey: Key? = null
    private var overSince = 0L
    private var titled: Detection? = null
    private var titledAt = 0L

    /** Последнее решение (для экрана и отладки). */
    var last: Verdict? = null
        private set

    /** Последнее распознавание с учётом удержанного названия. */
    var lastDetection: Detection? = null
        private set

    /** Сейчас идут 1,5 с на переход: служба проверяет чаще. */
    val inGrace: Boolean get() = overKey != null

    /**
     * Название канала, когда на экране его нет:
     *  - экран, у правила которого названия нет вовсе (комментарии под постом), сразу после канала
     *    с названием — это тот же канал: название держится, пока не откроется экран другого типа;
     *  - название пропало на одном разборе (шапка в анимации) — прежнее держится TITLE_HOLD_MS.
     * Иначе исключение «всегда можно» не действует в комментариях и мигает при анимации.
     */
    fun stickTitle(d: Detection, now: Long): Detection {
        if (d.title != null) {
            titled = d
            titledAt = now
            return d
        }
        val t = titled ?: return d
        if (t.service != d.service) return d
        if (t.type != d.type) {
            if (d.type != ContentType.UNKNOWN) titled = null // ушли в чаты и т.п. — связь с каналом потеряна
            return d
        }
        if (!d.hasTitleRule) {
            titledAt = now
            return d.copy(title = t.title)
        }
        if (now - titledAt <= TITLE_HOLD_MS) return d.copy(title = t.title)
        return d
    }

    /** Журнал для отладки: переходы между экранами и засчитано ли открытие. */
    var onEvent: ((String) -> Unit)? = null
    private var logKey: Key? = null

    /**
     * det == null — сервиса на экране нет (другое приложение, экран выключен).
     * Возвращает решение о блокировке, когда 1,5 с на переход прошли, иначе null.
     */
    fun tick(date: String, detIn: Detection?, now: Long, elapsedMs: Long, cfg: Config): Verdict? {
        if (detIn == null) {
            if (logKey != null) onEvent?.invoke("вне сервиса (другое приложение, SiteLimit или экран выключен)")
            logKey = null
            tracker.update(date, null, now)
            pendingMs = 0
            overKey = null
            last = null
            lastDetection = null
            return null
        }
        val det = stickTitle(detIn, now)
        lastDetection = det
        val type = Decider.effectiveType(det, cfg.exceptions)
        val key = Key(det.service, type)
        val kinds = Exceptions.forTitle(cfg.exceptions, det.service, det.title)
        val free = ExKind.ALLOW in kinds && ExKind.DENY !in kinds

        var newOpen = false
        val trackerNew = tracker.update(date, key, now)
        if (trackerNew) {
            val opensLimit = cfg.rules.limit(det.service, type).opens
            sessionKey = key
            sessionBlockedByOpens = !free && opensLimit != null && book.opens(date, key) >= opensLimit
            newOpen = !free && !sessionBlockedByOpens
        }

        pendingMs += elapsedMs
        val add = (pendingMs / 1000).toInt()
        pendingMs %= 1000
        if (add > 0) book.addSeconds(date, key, add, free)

        var v = Decider.decide(det, type, cfg.rules, cfg.modeTitle, cfg.exceptions, book, date,
            sessionBlockedByOpens && sessionKey == key)
        if (newOpen && !v.blocked) {
            book.addOpen(date, key)
            v = v.copy(remainingOpens = v.remainingOpens?.let { (it - 1).coerceAtLeast(0) })
        }
        last = v
        if (onEvent != null && (key != logKey || trackerNew)) {
            onEvent?.invoke(describeEntry(det, type, v, free, trackerNew, newOpen, date, key, cfg))
        }
        logKey = key
        if (!v.blocked) {
            overKey = null
            return null
        }
        if (overKey != key) {
            overKey = key
            overSince = now
        }
        if (now - overSince >= graceMs) {
            overKey = null
            return v
        }
        return null
    }

    private fun describeEntry(
        det: Detection, type: ContentType, v: Verdict, free: Boolean, trackerNew: Boolean, newOpen: Boolean,
        date: String, key: Key, cfg: Config,
    ): String {
        val head = "${det.service} — ${type.short}" + (det.title?.let { " «$it»" } ?: "") + " (${det.rule ?: "нет правила"})"
        val limit = cfg.rules.limit(det.service, type).opens
        val tail = when {
            type == ContentType.UNKNOWN -> "открытие не прерывает"
            free -> "исключение «всегда можно», открытия не считаются"
            !trackerNew -> "то же открытие (вернулся быстрее ${OPEN_GAP_MS / 1000} с)"
            newOpen && !v.blocked -> "+1 открытие, сегодня ${book.opens(date, key)}" + (limit?.let { " из $it" } ?: "")
            v.kind == BlockKind.OPENS -> "открытие не засчитано: ${v.reason}"
            v.blocked -> "открытие не засчитано (закрыт): ${v.reason}"
            else -> "новое открытие"
        }
        return "$head: $tail"
    }
}
