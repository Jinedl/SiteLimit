package io.github.jinedl.sitelimit

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * Всё хранится в SharedPreferences:
 *  - "limits": домен -> минут в день;
 *  - "usage":  "дата_домен" (например "2026-11-10_youtube.com") -> секунд;
 *  - "settings": отладка, свой id адресной строки, последний снимок, текущий сайт.
 */
object Store {
    private const val KEEP_DAYS = 60L

    private fun limitsPrefs(c: Context): SharedPreferences =
        c.getSharedPreferences("limits", Context.MODE_PRIVATE)

    private fun usagePrefs(c: Context): SharedPreferences =
        c.getSharedPreferences("usage", Context.MODE_PRIVATE)

    private fun settings(c: Context): SharedPreferences =
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun today(): String = LocalDate.now().toString()

    // ---- лимиты ----

    fun limits(c: Context): Map<String, Int> =
        limitsPrefs(c).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }
            .sortedBy { it.first }
            .toMap()

    fun setLimit(c: Context, domain: String, minutes: Int) {
        limitsPrefs(c).edit().putInt(domain, minutes).apply()
    }

    fun removeLimit(c: Context, domain: String) {
        limitsPrefs(c).edit().remove(domain).apply()
    }

    // ---- расход ----

    private fun usageKey(domain: String, date: String) = "${date}_$domain"

    fun usedSeconds(c: Context, domain: String, date: String = today()): Int =
        usagePrefs(c).getInt(usageKey(domain, date), 0)

    /** Добавляет секунды к сегодняшнему расходу и возвращает новое значение. */
    fun addSeconds(c: Context, domain: String, seconds: Int): Int {
        val key = usageKey(domain, today())
        val p = usagePrefs(c)
        val value = p.getInt(key, 0) + seconds
        p.edit().putInt(key, value).apply()
        return value
    }

    /** Удаляет статистику старше KEEP_DAYS дней. */
    fun cleanupOldUsage(c: Context) {
        val border = LocalDate.now().minusDays(KEEP_DAYS).toString()
        val p = usagePrefs(c)
        val old = p.all.keys.filter { it.substringBefore('_') < border }
        if (old.isNotEmpty()) {
            val e = p.edit()
            old.forEach { e.remove(it) }
            e.apply()
        }
    }

    // ---- настройки и отладка ----

    fun isDebug(c: Context) = settings(c).getBoolean("debug", false)
    fun setDebug(c: Context, on: Boolean) = settings(c).edit().putBoolean("debug", on).apply()

    fun customId(c: Context): String = settings(c).getString("custom_id", "") ?: ""
    fun setCustomId(c: Context, id: String) = settings(c).edit().putString("custom_id", id.trim()).apply()

    fun debugDump(c: Context): String? = settings(c).getString("debug_dump", null)
    fun setDebugDump(c: Context, text: String) = settings(c).edit().putString("debug_dump", text).apply()

    /** Что служба видела при последней проверке (для экрана приложения). */
    fun diag(c: Context): String? = settings(c).getString("diag", null)
    fun setDiag(c: Context, text: String) = settings(c).edit().putString("diag", text).apply()

    fun currentSite(c: Context): String?= settings(c).getString("current", null)
    fun setCurrentSite(c: Context, host: String?) = settings(c).edit().putString("current", host).apply()

    // ---- домены ----

    private val DOMAIN = Regex(
        "^(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+" +
            "(?:\\p{L}{2,24}|xn--[a-z0-9-]{2,59})$"
    )

    /**
     * Превращает текст адресной строки или ввод пользователя в домен:
     * "https://www.YouTube.com/watch?v=1" -> "youtube.com".
     * Если текст не похож на адрес — null.
     */
    fun normalizeDomain(raw: CharSequence?): String? {
        var s = raw?.toString()?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        s = s.split(Regex("\\s+"))[0]
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringBefore(':').trimEnd('.')
        s = s.removePrefix("www.")
        return if (DOMAIN.matches(s)) s else null
    }

    /** Какой из доменов с лимитом подходит хосту (с учётом поддоменов: m.youtube.com -> youtube.com). */
    fun matchLimit(host: String, domains: Collection<String>): String? =
        domains.filter { host == it || host.endsWith(".$it") }.maxByOrNull { it.length }
}
