package io.github.jinedl.sitelimit.ct

/**
 * Упрямый режим: пока он идёт, правила можно только ужесточать.
 * Ослаблением считается: больший лимит или его снятие; удаление или ослабление общего лимита;
 * новое «всегда можно»; удаление «всегда нельзя» или «считать каналом»; удаление пакета сервиса;
 * снятие или увеличение лимита сайта; выключение беты, смена режима и правка расписания (запрещены целиком).
 */
object StrictGuard {
    private const val INF = Int.MAX_VALUE

    fun weakerLimit(old: Limit, new: Limit): Boolean =
        (new.minutes ?: INF) > (old.minutes ?: INF) || (new.opens ?: INF) > (old.opens ?: INF)

    fun weakerRules(old: ModeRules, new: ModeRules): Boolean {
        val services = old.limits.keys + new.limits.keys
        for (s in services) for (t in ContentType.LIMITED) {
            if (weakerLimit(old.limit(s, t), new.limit(s, t))) return true
        }
        for (g in old.groups) {
            val kept = new.groups.any { it.types == g.types && it.minutes <= g.minutes }
            if (!kept) return true
        }
        return false
    }

    fun weakerExceptions(old: List<ExRule>, new: List<ExRule>): Boolean {
        val oldSet = old.map { Triple(it.service, it.key, it.kind) }.toSet()
        val newSet = new.map { Triple(it.service, it.key, it.kind) }.toSet()
        val added = newSet - oldSet
        val removed = oldSet - newSet
        return added.any { it.third == ExKind.ALLOW } || removed.any { it.third != ExKind.ALLOW }
    }

    fun weakerPackages(old: Collection<String>, new: Collection<String>): Boolean = old.any { it !in new }

    /** Лимит сайта в минутах, null — лимита нет. */
    fun weakerSiteLimit(old: Int?, new: Int?): Boolean = (new ?: INF) > (old ?: INF)

    const val MESSAGE = "Идёт упрямый режим: ослабить правила нельзя до его конца"
}
