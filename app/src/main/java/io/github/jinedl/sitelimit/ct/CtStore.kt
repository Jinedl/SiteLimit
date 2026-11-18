package io.github.jinedl.sitelimit.ct

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Настройки типов контента (SharedPreferences "ct") и счётчики ("ct_usage").
 * Лимиты сайтов v0.9 лежат отдельно (Store) и здесь не трогаются.
 * Любое изменение настроек увеличивает cfg_ver — служба перечитывает настройки на следующем тике.
 */
object CtStore {
    private const val TAG = "SiteLimit"
    const val RULES_ASSET = "content_rules.json"
    private const val MAX_RECENT = 15
    private const val MAX_SNAPS = 6

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("ct", Context.MODE_PRIVATE)
    private fun u(c: Context): SharedPreferences = c.getSharedPreferences("ct_usage", Context.MODE_PRIVATE)

    private fun changed(c: Context, block: SharedPreferences.Editor.() -> Unit) {
        val prefs = p(c)
        val e = prefs.edit()
        e.block()
        e.putInt("cfg_ver", prefs.getInt("cfg_ver", 0) + 1)
        e.apply()
    }

    fun configVersion(c: Context): Int = p(c).getInt("cfg_ver", 0)

    // ---- главный выключатель ----

    fun isEnabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = changed(c) { putBoolean("enabled", on) }

    // ---- файл правил ----

    fun builtinRulesText(c: Context): String = c.assets.open(RULES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun customRulesText(c: Context): String? = p(c).getString("custom_rules", null)

    /** Свои правила из отладки (null — вернуть встроенные). Перед сохранением проверить RuleBook.parse. */
    fun setCustomRules(c: Context, json: String?) = changed(c) {
        if (json.isNullOrBlank()) remove("custom_rules") else putString("custom_rules", json)
    }

    class LoadedRules(val book: RuleBook, val custom: Boolean, val error: String?)

    fun loadRules(c: Context): LoadedRules {
        val custom = customRulesText(c)
        if (custom != null) {
            try {
                return LoadedRules(RuleBook.parse(custom), true, null)
            } catch (e: Exception) {
                Log.w(TAG, "custom rules broken", e)
                return LoadedRules(RuleBook.parse(builtinRulesText(c)), false, "свои правила с ошибкой: ${e.message}")
            }
        }
        return LoadedRules(RuleBook.parse(builtinRulesText(c)), false, null)
    }

    // ---- пакеты сервисов (своё значение вместо файла правил) ----

    fun packagesOverride(c: Context, service: String): List<String>? =
        p(c).getString("pkgs_$service", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    fun setPackages(c: Context, service: String, list: List<String>?) = changed(c) {
        if (list == null) remove("pkgs_$service") else putString("pkgs_$service", list.joinToString(","))
    }

    fun packagesFor(c: Context, svc: ServiceDef): List<String> = packagesOverride(c, svc.id) ?: svc.packages.map { it.name }

    // ---- правила режимов ----

    fun modeRules(c: Context, mode: String, services: Collection<String>): ModeRules {
        val raw = p(c).getString("mode_$mode", null) ?: return Presets.forMode(mode, services)
        return try {
            ModeRules.fromJson(MiniJson.parseObject(raw))
        } catch (e: Exception) {
            Presets.forMode(mode, services)
        }
    }

    fun setModeRules(c: Context, mode: String, rules: ModeRules) = changed(c) {
        putString("mode_$mode", MiniJson.write(rules.toJson()))
    }

    // ---- исключения, расписание, ручной режим ----

    fun exceptions(c: Context): List<ExRule> = readList(c, "exceptions") { Exceptions.fromJson(it) }
    fun setExceptions(c: Context, list: List<ExRule>) = changed(c) { putString("exceptions", MiniJson.write(Exceptions.toJson(list))) }

    fun schedule(c: Context): List<ScheduleEntry> = readList(c, "schedule") { l -> l.mapNotNull { it.asObj()?.let(ScheduleEntry::fromJson) } }
    fun setSchedule(c: Context, list: List<ScheduleEntry>) = changed(c) { putString("schedule", MiniJson.write(list.map { it.toJson() })) }

    fun manual(c: Context): ManualMode? = p(c).getString("manual", null)?.let {
        try {
            ManualMode.fromJson(MiniJson.parseObject(it))
        } catch (e: Exception) {
            null
        }
    }

    fun setManual(c: Context, m: ManualMode?) = changed(c) {
        if (m == null) remove("manual") else putString("manual", MiniJson.write(m.toJson()))
    }

    private fun <T> readList(c: Context, key: String, conv: (List<Any?>) -> List<T>): List<T> {
        val raw = p(c).getString(key, null) ?: return emptyList()
        return try {
            conv(MiniJson.parseList(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- состояние для экранов и отладки (не меняет cfg_ver) ----

    fun now(c: Context): String? = p(c).getString("now", null)
    fun setNow(c: Context, text: String) = p(c).edit().putString("now", text).apply()

    fun lastBlock(c: Context): String? = p(c).getString("last_block", null)
    fun setLastBlock(c: Context, text: String) = p(c).edit().putString("last_block", text).apply()

    /** Недавние названия каналов и чатов: для быстрого добавления исключений. */
    class Recent(val service: String, val title: String, val type: ContentType)

    fun recentTitles(c: Context): List<Recent> = readList(c, "recent") { l ->
        l.mapNotNull { o ->
            val m = o.asObj() ?: return@mapNotNull null
            Recent(m.str("s") ?: return@mapNotNull null, m.str("t") ?: return@mapNotNull null,
                ContentType.byId(m.str("y")) ?: ContentType.UNKNOWN)
        }
    }

    fun addRecentTitle(c: Context, r: Recent) {
        val list = recentTitles(c).filterNot { it.service == r.service && Exceptions.normalize(it.title) == Exceptions.normalize(r.title) }
        val out = (listOf(r) + list).take(MAX_RECENT)
        p(c).edit().putString("recent", MiniJson.write(out.map { linkedMapOf("s" to it.service, "t" to it.title, "y" to it.type.id) })).apply()
    }

    fun isMask(c: Context): Boolean = p(c).getBoolean("mask", true)
    fun setMask(c: Context, on: Boolean) = p(c).edit().putBoolean("mask", on).apply()

    /** Последние снимки (новые первыми). replaceFirst — заменить последний снимок того же экрана. */
    fun snapshots(c: Context): List<String> = readList(c, "snaps") { l -> l.mapNotNull { it as? String } }

    fun pushSnapshot(c: Context, text: String, replaceFirst: Boolean) {
        val list = snapshots(c).toMutableList()
        if (replaceFirst && list.isNotEmpty()) list[0] = text else list.add(0, text)
        p(c).edit().putString("snaps", MiniJson.write(list.take(MAX_SNAPS))).apply()
    }

    fun clearSnapshots(c: Context) = p(c).edit().remove("snaps").apply()

    // ---- счётчики ----

    fun loadUsage(c: Context): Map<String, Int> =
        u(c).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    fun saveUsage(c: Context, book: UsageBook) {
        if (!book.dirty) return
        val (upd, rem) = book.drainChanges()
        val e = u(c).edit()
        for ((k, v) in upd) e.putInt(k, v)
        for (k in rem) e.remove(k)
        e.apply()
    }
}
