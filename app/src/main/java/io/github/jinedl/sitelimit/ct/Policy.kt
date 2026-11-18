package io.github.jinedl.sitelimit.ct

/**
 * Лимит на тип: минуты в день и открытия в день. null — без лимита, 0 — закрыто.
 */
data class Limit(val minutes: Int? = null, val opens: Int? = null) {
    val isClosed: Boolean get() = minutes == 0 || opens == 0
    val isEmpty: Boolean get() = minutes == null && opens == null

    fun describe(): String = when {
        isClosed -> "закрыто"
        isEmpty -> "без лимита"
        else -> listOfNotNull(minutes?.let { "$it мин" }, opens?.let { "$it откр." }).joinToString(" · ")
    }

    fun toJson(): Map<String, Any?> = linkedMapOf("m" to minutes, "o" to opens)

    companion object {
        fun fromJson(m: Map<String, Any?>?): Limit = Limit(m?.int("m"), m?.int("o"))
    }
}

/** Общий лимит минут на несколько типов во всех сервисах вместе (например, ленты и ролики — 60 мин). */
data class Group(val types: Set<ContentType>, val minutes: Int) {
    fun title(): String = ContentType.LIMITED.filter { it in types }.joinToString(", ") { it.short }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "types" to ContentType.LIMITED.filter { it in types }.map { it.id },
        "m" to minutes,
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): Group? {
            val types = m.list("types").mapNotNull { ContentType.byId(it as? String) }.toSet()
            val minutes = m.int("m") ?: return null
            return if (types.isEmpty()) null else Group(types, minutes)
        }
    }
}

/** Правила одного режима: лимиты по сервису и типу и общие лимиты. */
data class ModeRules(
    val limits: Map<String, Map<ContentType, Limit>> = emptyMap(),
    val groups: List<Group> = emptyList(),
) {
    fun limit(service: String, type: ContentType): Limit = limits[service]?.get(type) ?: Limit()

    fun with(service: String, type: ContentType, l: Limit): ModeRules {
        val forSvc = (limits[service] ?: emptyMap()).toMutableMap()
        if (l.isEmpty) forSvc.remove(type) else forSvc[type] = l
        val all = limits.toMutableMap()
        if (forSvc.isEmpty()) all.remove(service) else all[service] = forSvc
        return copy(limits = all)
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "limits" to limits.mapValues { (_, m) ->
            LinkedHashMap<String, Any?>().apply { for ((t, l) in m) put(t.id, l.toJson()) }
        },
        "groups" to groups.map { it.toJson() },
    )

    companion object {
        fun fromJson(m: Map<String, Any?>?): ModeRules {
            if (m == null) return ModeRules()
            val limits = LinkedHashMap<String, Map<ContentType, Limit>>()
            m.obj("limits")?.forEach { (svc, v) ->
                val per = LinkedHashMap<ContentType, Limit>()
                v.asObj()?.forEach { (t, lv) ->
                    val type = ContentType.byId(t)
                    if (type != null && type != ContentType.UNKNOWN) {
                        val l = Limit.fromJson(lv.asObj())
                        if (!l.isEmpty) per[type] = l
                    }
                }
                if (per.isNotEmpty()) limits[svc] = per
            }
            val groups = m.list("groups").mapNotNull { it.asObj()?.let(Group::fromJson) }
            return ModeRules(limits, groups)
        }
    }
}

/** Режимы: у каждого свои правила. «Обычный» действует, когда нет ни расписания, ни ручного режима. */
object Modes {
    const val NORMAL = "normal"
    const val TALK = "talk"
    const val STUDY = "study"
    const val REST = "rest"
    val ALL = listOf(NORMAL, TALK, STUDY, REST)

    fun title(id: String): String = when (id) {
        NORMAL -> "Обычный"
        TALK -> "Только общение"
        STUDY -> "Учёба/Работа"
        REST -> "Отдых"
        else -> id
    }
}

/**
 * Пресеты (kb/01, цифры условные): применяются к режиму и затем редактируются.
 * В пресетах «ролики» — короткие ролики и длинное видео; поиск и служебное не закрываются.
 */
object Presets {
    const val NONE = "none"
    val ALL = listOf(NONE, Modes.TALK, Modes.STUDY, Modes.REST)

    fun title(id: String): String = if (id == NONE) "Без ограничений" else Modes.title(id)

    fun describe(id: String): String = when (id) {
        NONE -> "ничего не ограничено"
        Modes.TALK -> "переписка без лимита; каналы, ленты, короткие ролики и видео закрыты"
        Modes.STUDY -> "переписка без лимита; каналы — 15 мин в день во всех сервисах вместе; ленты, короткие ролики и видео закрыты"
        Modes.REST -> "всё открыто; ленты, короткие ролики и видео — 60 мин в день во всех сервисах вместе"
        else -> ""
    }

    /** Пресет для режима с тем же id (у «Обычного» — без ограничений). */
    fun forMode(mode: String, services: Collection<String>): ModeRules =
        build(if (mode in ALL) mode else NONE, services)

    fun build(id: String, services: Collection<String>): ModeRules {
        fun closed(vararg types: ContentType): ModeRules {
            val limits = LinkedHashMap<String, Map<ContentType, Limit>>()
            for (s in services) limits[s] = types.associateWith { Limit(minutes = 0) }
            return ModeRules(limits)
        }
        return when (id) {
            Modes.TALK -> closed(ContentType.CHANNEL, ContentType.FEED, ContentType.SHORTS, ContentType.VIDEO)
            Modes.STUDY -> closed(ContentType.FEED, ContentType.SHORTS, ContentType.VIDEO)
                .copy(groups = listOf(Group(setOf(ContentType.CHANNEL), 15)))
            Modes.REST -> ModeRules(groups = listOf(Group(setOf(ContentType.FEED, ContentType.SHORTS, ContentType.VIDEO), 60)))
            else -> ModeRules()
        }
    }
}

enum class ExKind(val id: String, val title: String) {
    ALLOW("allow", "всегда можно"),
    DENY("deny", "всегда нельзя"),
    AS_CHANNEL("as_channel", "считать каналом");

    companion object {
        fun byId(id: String?): ExKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Исключение для канала или чата (по названию) или сайта (по домену, с поддоменами).
 * service: id сервиса, SITE или ANY (любой сервис).
 */
data class ExRule(val service: String, val name: String, val kind: ExKind) {
    val key: String = Exceptions.normalize(name)

    fun toJson(): Map<String, Any?> = linkedMapOf("service" to service, "name" to name, "kind" to kind.id)

    companion object {
        fun fromJson(m: Map<String, Any?>): ExRule? {
            val kind = ExKind.byId(m.str("kind")) ?: return null
            val name = m.str("name")?.takeIf { it.isNotBlank() } ?: return null
            return ExRule(m.str("service") ?: Exceptions.ANY, name, kind)
        }
    }
}

object Exceptions {
    const val SITE = "site"
    const val ANY = "*"

    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")
    private val SPACES = Regex("\\s+")

    /** Сравнение названий: без регистра, лишних пробелов и невидимых символов. */
    fun normalize(s: String): String = s.replace(INVISIBLE, "").replace(SPACES, " ").trim().lowercase()

    /** Какие исключения подходят каналу или чату с этим названием. */
    fun forTitle(list: List<ExRule>, service: String, title: String?): Set<ExKind> {
        if (title.isNullOrBlank()) return emptySet()
        val key = normalize(title)
        return list.filter { it.service != SITE && (it.service == service || it.service == ANY) && it.key == key }
            .map { it.kind }.toSet()
    }

    /** Исключение для сайта: самый длинный подходящий домен; при равенстве «нельзя» сильнее «можно». */
    fun forSite(list: List<ExRule>, host: String): ExRule? {
        val h = host.lowercase()
        return list.filter { it.service == SITE && it.kind != ExKind.AS_CHANNEL && (h == it.key || h.endsWith("." + it.key)) }
            .sortedWith(compareByDescending<ExRule> { it.key.length }.thenBy { if (it.kind == ExKind.DENY) 0 else 1 })
            .firstOrNull()
    }

    fun toJson(list: List<ExRule>): List<Any?> = list.map { it.toJson() }

    fun fromJson(l: List<Any?>): List<ExRule> = l.mapNotNull { it.asObj()?.let(ExRule::fromJson) }
}
