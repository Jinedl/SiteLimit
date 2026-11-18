package io.github.jinedl.sitelimit.ct

/**
 * Файл правил (assets/content_rules.json или свои правила из отладки): сервисы, их пакеты
 * и признаки экранов. Описание формата — в README, раздел «Файл правил».
 */
class RuleBook(
    val version: Int,
    val updated: String,
    val services: List<ServiceDef>,
    /** Слова, которые в снимке не скрываются и отмечаются (вместе со словами из условий). */
    val keywords: List<String>,
) {
    fun service(id: String): ServiceDef? = services.firstOrNull { it.id == id }

    /** Все слова из правил — для снимков (не скрывать при маскировке). */
    val allKeywords: List<String> by lazy {
        val out = LinkedHashSet<String>()
        keywords.forEach { if (it.isNotBlank()) out.add(it.lowercase()) }
        for (s in services) for (r in s.screens) {
            (r.all + r.any + r.none + r.titles.map { it.from }).forEach { c -> c.words().forEach { out.add(it) } }
        }
        out.toList().sortedByDescending { it.length }
    }

    companion object {
        fun parse(json: String): RuleBook {
            val root = MiniJson.parseObject(json)
            checkKeys(root, setOf("version", "updated", "note", "keywords", "services"), "файл")
            val services = root.list("services").mapIndexed { i, s ->
                ServiceDef.parse(s.asObj() ?: throw MiniJson.Error("services[$i]: ожидался объект"))
            }
            if (services.isEmpty()) throw MiniJson.Error("нет ни одного сервиса")
            val ids = HashSet<String>()
            for (s in services) if (!ids.add(s.id)) throw MiniJson.Error("сервис ${s.id} указан дважды")
            return RuleBook(
                version = root.int("version") ?: 0,
                updated = root.str("updated") ?: "",
                services = services,
                keywords = root.list("keywords").mapNotNull { it as? String },
            )
        }

        internal fun checkKeys(m: Map<String, Any?>, allowed: Set<String>, where: String) {
            for (k in m.keys) {
                if (k !in allowed && !k.startsWith("_")) {
                    throw MiniJson.Error("$where: неизвестное поле \"$k\" (можно: ${allowed.sorted().joinToString()})")
                }
            }
        }
    }
}

/** Пакет приложения сервиса. status: snap — по снимку, store — по странице магазина, guess — догадка. */
class PkgDef(val name: String, val status: String, val note: String) {
    val mark: String get() = if (status == "snap" || status == "store") "✅" else "🔶"
}

class ServiceDef(
    val id: String,
    val title: String,
    val packages: List<PkgDef>,
    val defaultType: ContentType,
    val screens: List<ScreenRule>,
) {
    companion object {
        fun parse(m: Map<String, Any?>): ServiceDef {
            val id = m.str("id") ?: throw MiniJson.Error("у сервиса нет id")
            RuleBook.checkKeys(m, setOf("id", "title", "packages", "default", "screens", "note"), "сервис $id")
            val pkgs = m.list("packages").map { p ->
                when (p) {
                    is String -> PkgDef(p, "guess", "")
                    else -> {
                        val o = p.asObj() ?: throw MiniJson.Error("сервис $id: пакет — строка или объект")
                        RuleBook.checkKeys(o, setOf("name", "status", "note"), "сервис $id, пакет")
                        PkgDef(o.str("name") ?: throw MiniJson.Error("сервис $id: у пакета нет name"),
                            o.str("status") ?: "guess", o.str("note") ?: "")
                    }
                }
            }
            val def = m.str("default")?.let {
                ContentType.byId(it) ?: throw MiniJson.Error("сервис $id: неизвестный тип \"$it\"")
            } ?: ContentType.UNKNOWN
            val screens = m.list("screens").mapIndexed { i, r ->
                ScreenRule.parse(r.asObj() ?: throw MiniJson.Error("сервис $id, screens[$i]: ожидался объект"), "$id[$i]")
            }
            return ServiceDef(id, m.str("title") ?: id, pkgs, def, screens)
        }
    }
}

/**
 * Признак экрана. Срабатывает, если:
 *  - для каждого условия из all нашёлся элемент (каждое — свой элемент);
 *  - если any не пуст — нашёлся элемент хотя бы для одного условия;
 *  - ни одно условие из none не нашлось.
 * Правила сервиса проверяются по порядку, срабатывает первое.
 */
class ScreenRule(
    val id: String,
    val type: ContentType,
    val status: String,
    val note: String,
    val packages: Set<String>,
    val all: List<Cond>,
    val any: List<Cond>,
    val none: List<Cond>,
    val titles: List<TitleRule>,
) {
    companion object {
        fun parse(m: Map<String, Any?>, fallbackId: String): ScreenRule {
            val id = m.str("id") ?: fallbackId
            RuleBook.checkKeys(m, setOf("id", "type", "status", "note", "packages", "all", "any", "none", "title"), "правило $id")
            val type = ContentType.byId(m.str("type")) ?: throw MiniJson.Error("правило $id: неизвестный type \"${m.str("type")}\"")
            fun conds(k: String) = m.list(k).mapIndexed { i, c ->
                Cond.parse(c.asObj() ?: throw MiniJson.Error("правило $id, $k[$i]: ожидался объект"), "правило $id, $k[$i]")
            }
            val titleRaw = m["title"]
            val titles = when (titleRaw) {
                null -> emptyList()
                is List<*> -> titleRaw.mapIndexed { i, t ->
                    TitleRule.parse(t.asObj() ?: throw MiniJson.Error("правило $id, title[$i]: ожидался объект"), "правило $id")
                }
                else -> listOf(TitleRule.parse(titleRaw.asObj() ?: throw MiniJson.Error("правило $id: title — объект или список"), "правило $id"))
            }
            return ScreenRule(
                id = id,
                type = type,
                status = m.str("status") ?: "guess",
                note = m.str("note") ?: "",
                packages = m.list("packages").mapNotNull { it as? String }.toSet(),
                all = conds("all"),
                any = conds("any"),
                none = conds("none"),
                titles = titles,
            )
        }
    }
}

/**
 * Условие на один элемент окна: все заданные поля должны выполняться для одного и того же элемента.
 * Сравнение без учёта регистра. *Has — подстрока, *Re — регулярное выражение (поиск в тексте).
 * any* — в тексте, описании или подсказке. yMin/yMax — доля высоты экрана (0 — верх, 1 — низ) для верха элемента.
 * min — сколько элементов должно подойти (по умолчанию 1).
 */
class Cond(
    val id: String?,
    val idHas: String?,
    val cls: String?,
    val clsHas: String?,
    val text: String?,
    val textHas: String?,
    val textRe: Regex?,
    val desc: String?,
    val descHas: String?,
    val descRe: Regex?,
    val anyHas: String?,
    val anyRe: Regex?,
    val hintHas: String?,
    val selected: Boolean?,
    val checked: Boolean?,
    val editable: Boolean?,
    val clickable: Boolean?,
    val yMin: Double?,
    val yMax: Double?,
    val min: Int,
) {
    fun test(n: UiNode, screenHeight: Int): Boolean {
        if (id != null && n.lId != id) return false
        if (idHas != null && !n.lId.contains(idHas)) return false
        if (cls != null && n.lCls != cls) return false
        if (clsHas != null && !n.lCls.contains(clsHas)) return false
        if (text != null && n.lText.trim() != text) return false
        if (textHas != null && !n.lText.contains(textHas)) return false
        if (textRe != null && !textRe.containsMatchIn(n.text)) return false
        if (desc != null && n.lDesc.trim() != desc) return false
        if (descHas != null && !n.lDesc.contains(descHas)) return false
        if (descRe != null && !descRe.containsMatchIn(n.desc)) return false
        if (anyHas != null && !(n.lText.contains(anyHas) || n.lDesc.contains(anyHas) || n.lHint.contains(anyHas))) return false
        if (anyRe != null && !(anyRe.containsMatchIn(n.text) || anyRe.containsMatchIn(n.desc) || anyRe.containsMatchIn(n.hint))) return false
        if (hintHas != null && !n.lHint.contains(hintHas)) return false
        if (selected != null && n.selected != selected) return false
        if (checked != null && n.checked != checked) return false
        if (editable != null && n.editable != editable) return false
        if (clickable != null && n.clickable != clickable) return false
        if (screenHeight > 0) {
            if (yMin != null && n.top < yMin * screenHeight) return false
            if (yMax != null && n.top > yMax * screenHeight) return false
        }
        return true
    }

    /** Сколько элементов подходит (считает не дальше, чем нужно). */
    fun found(screen: Screen): Boolean {
        var c = 0
        for (n in screen.nodes) {
            if (test(n, screen.height)) {
                c++
                if (c >= min) return true
            }
        }
        return false
    }

    /** Слова из условия — для снимков. */
    fun words(): List<String> = listOfNotNull(text, textHas, desc, descHas, anyHas, hintHas).filter { it.isNotBlank() }

    companion object {
        private val FIELDS = setOf(
            "id", "idHas", "cls", "clsHas", "text", "textHas", "textRe", "desc", "descHas", "descRe",
            "anyHas", "anyRe", "hintHas", "selected", "checked", "editable", "clickable", "yMin", "yMax", "min", "note",
        )

        private fun re(m: Map<String, Any?>, k: String, where: String): Regex? = m.str(k)?.let {
            try {
                Regex(it, setOf(RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                throw MiniJson.Error("$where: ошибка в $k: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }

        private fun low(m: Map<String, Any?>, k: String): String? = m.str(k)?.lowercase()

        fun parse(m: Map<String, Any?>, where: String): Cond {
            RuleBook.checkKeys(m, FIELDS, where)
            val c = Cond(
                id = low(m, "id")?.substringAfter(":id/"),
                idHas = low(m, "idHas"),
                cls = low(m, "cls"),
                clsHas = low(m, "clsHas"),
                text = low(m, "text")?.trim(),
                textHas = low(m, "textHas"),
                textRe = re(m, "textRe", where),
                desc = low(m, "desc")?.trim(),
                descHas = low(m, "descHas"),
                descRe = re(m, "descRe", where),
                anyHas = low(m, "anyHas"),
                anyRe = re(m, "anyRe", where),
                hintHas = low(m, "hintHas"),
                selected = m.bool("selected"),
                checked = m.bool("checked"),
                editable = m.bool("editable"),
                clickable = m.bool("clickable"),
                yMin = m.num("yMin"),
                yMax = m.num("yMax"),
                min = (m.int("min") ?: 1).coerceAtLeast(1),
            )
            if (c.words().isEmpty() && c.id == null && c.idHas == null && c.cls == null && c.clsHas == null &&
                c.textRe == null && c.descRe == null && c.anyRe == null && c.selected == null && c.checked == null &&
                c.editable == null && c.clickable == null
            ) throw MiniJson.Error("$where: пустое условие")
            return c
        }
    }
}

/**
 * Откуда взять название канала или чата: первый элемент, подходящий под from;
 * pick: self — он сам, above / below — ближайший элемент с текстом выше / ниже него.
 * field: text, desc или any (текст, иначе описание). re — регулярное выражение, берётся группа 1
 * (или всё совпадение). Из результата берётся первая строка.
 */
class TitleRule(val from: Cond, val field: String, val re: Regex?, val pick: String) {
    companion object {
        fun parse(m: Map<String, Any?>, where: String): TitleRule {
            RuleBook.checkKeys(m, setOf("from", "field", "re", "pick", "note"), "$where, title")
            val from = Cond.parse(m.obj("from") ?: throw MiniJson.Error("$where: в title нет from"), "$where, title.from")
            val field = m.str("field") ?: "any"
            if (field !in setOf("text", "desc", "any")) throw MiniJson.Error("$where: title.field — text, desc или any")
            val pick = m.str("pick") ?: "self"
            if (pick !in setOf("self", "above", "below")) throw MiniJson.Error("$where: title.pick — self, above или below")
            val re = m.str("re")?.let {
                try {
                    Regex(it, setOf(RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    throw MiniJson.Error("$where: ошибка в title.re: ${e.message?.lineSequence()?.firstOrNull()}")
                }
            }
            return TitleRule(from, field, re, pick)
        }
    }
}
