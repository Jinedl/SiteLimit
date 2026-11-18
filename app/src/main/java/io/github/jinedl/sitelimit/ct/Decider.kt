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
 */
class CtCore(val book: UsageBook, gapMs: Long = 10_000, private val graceMs: Long = 1_500) {

    class Config(val rules: ModeRules, val modeTitle: String, val exceptions: List<ExRule>)

    private val tracker = OpenTracker(gapMs)
    private var sessionKey: Key? = null
    private var sessionBlockedByOpens = false
    private var pendingMs = 0L
    private var overKey: Key? = null
    private var overSince = 0L

    /** Последнее решение (для экрана и отладки). */
    var last: Verdict? = null
        private set

    /** Сейчас идут 1,5 с на переход: служба проверяет чаще. */
    val inGrace: Boolean get() = overKey != null

    /**
     * det == null — сервиса на экране нет (другое приложение, экран выключен).
     * Возвращает решение о блокировке, когда 1,5 с на переход прошли, иначе null.
     */
    fun tick(date: String, det: Detection?, now: Long, elapsedMs: Long, cfg: Config): Verdict? {
        if (det == null) {
            tracker.update(date, null, now)
            pendingMs = 0
            overKey = null
            last = null
            return null
        }
        val type = Decider.effectiveType(det, cfg.exceptions)
        val key = Key(det.service, type)
        val free = ExKind.ALLOW in Exceptions.forTitle(cfg.exceptions, det.service, det.title) &&
            ExKind.DENY !in Exceptions.forTitle(cfg.exceptions, det.service, det.title)

        if (tracker.update(date, key, now)) {
            val opensLimit = cfg.rules.limit(det.service, type).opens
            sessionKey = key
            sessionBlockedByOpens = !free && opensLimit != null && book.opens(date, key) >= opensLimit
            if (!free && !sessionBlockedByOpens) book.addOpen(date, key)
        }

        pendingMs += elapsedMs
        val add = (pendingMs / 1000).toInt()
        pendingMs %= 1000
        if (add > 0) book.addSeconds(date, key, add, free)

        val v = Decider.decide(det, type, cfg.rules, cfg.modeTitle, cfg.exceptions, book, date,
            sessionBlockedByOpens && sessionKey == key)
        last = v
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
}
