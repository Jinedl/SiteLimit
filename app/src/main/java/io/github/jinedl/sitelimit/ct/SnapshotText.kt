package io.github.jinedl.sitelimit.ct

/**
 * Снимок окна для отладки: одна строка на элемент. Тот же текст читается обратно
 * (parse) — присланные снимки становятся образцами для тестов распознавания.
 *
 * Приватность: тексты обрезаются; при маскировке буквы заменяются на «•», цифры на «0»,
 * кроме слов из файла правил (подписчик, участник, Клипы…) — по ним и настраиваются правила.
 */
object SnapshotText {
    const val CUT_PLAIN = 20
    const val CUT_MASKED = 40
    const val MAX_LINES = 200

    fun format(screen: Screen, header: List<String>, keywords: List<String>, mask: Boolean): String {
        val cut = if (mask) CUT_MASKED else CUT_PLAIN
        val sb = StringBuilder()
        for (h in header) sb.append("# ").append(h).append('\n')
        sb.append("# Тексты обрезаны до $cut симв.")
        sb.append(if (mask) ", буквы скрыты (•), кроме слов из правил." else ", буквы НЕ скрыты — проверьте перед отправкой.")
        sb.append(" ⚑ — слово из правил в обрезанной части.\n")
        sb.append("pkg=${screen.pkg} w=${screen.width} h=${screen.height}\n")
        for (n in screen.nodes.sortedBy { it.top }.take(MAX_LINES)) {
            sb.append("y=${n.top}..${n.bottom} x=${n.left}..${n.right} ")
            sb.append(n.cls.ifEmpty { "?" })
            if (n.id.isNotEmpty()) sb.append(" id=").append(n.id)
            field(sb, "text", n.text, cut, mask, keywords)
            field(sb, "desc", n.desc, cut, mask, keywords)
            field(sb, "hint", n.hint, cut, mask, keywords)
            if (n.selected) sb.append(" sel")
            if (n.checked) sb.append(" chk")
            if (n.clickable) sb.append(" click")
            if (n.editable) sb.append(" edit")
            val hidden = keywords.filter { k ->
                listOf(n.text, n.desc, n.hint).any { v -> v.length > cut && v.lowercase().indexOf(k, (cut - k.length + 1).coerceAtLeast(0)) >= 0 }
            }
            if (hidden.isNotEmpty()) sb.append(" ⚑").append(hidden.joinToString(","))
            sb.append('\n')
        }
        if (screen.nodes.size > MAX_LINES) sb.append("# … ещё ${screen.nodes.size - MAX_LINES} элементов\n")
        return sb.toString()
    }

    private fun field(sb: StringBuilder, name: String, v: String, cut: Int, mask: Boolean, keywords: List<String>) {
        if (v.isEmpty()) return
        val shown = if (mask) mask(v, keywords) else v
        val short = if (shown.length > cut) shown.take(cut) + "…" else shown
        sb.append(' ').append(name).append("=\"").append(escape(short)).append('"')
        if (v.length > cut) sb.append('(').append(v.length).append(')')
    }

    /** Буквы → «•», цифры → «0», кроме слов, в которых есть слово из правил («подписчиков» целиком). */
    fun mask(v: String, keywords: List<String>): String {
        val keep = BooleanArray(v.length)
        val low = v.lowercase()
        if (low.length == v.length) {
            for (k in keywords) {
                if (k.isEmpty()) continue
                var i = low.indexOf(k)
                while (i >= 0) {
                    var a = i
                    var b = i + k.length
                    while (a > 0 && v[a - 1].isLetter()) a--
                    while (b < v.length && v[b].isLetter()) b++
                    for (j in a until b) keep[j] = true
                    i = low.indexOf(k, i + 1)
                }
            }
        }
        val sb = StringBuilder(v.length)
        for (i in v.indices) {
            val c = v[i]
            sb.append(
                when {
                    keep[i] -> c
                    c.isLetter() -> '•'
                    c.isDigit() -> '0'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    // ---- обратное чтение (для тестов на присланных снимках) ----

    private val SCREEN = Regex("^pkg=(\\S*) w=(\\d+) h=(\\d+)")
    private val RANGE = Regex("^(-?\\d+)\\.\\.(-?\\d+)$")

    fun parse(text: String): Screen {
        var pkg = ""
        var w = 0
        var h = 0
        val nodes = ArrayList<UiNode>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) continue
            SCREEN.find(line)?.let {
                pkg = it.groupValues[1]
                w = it.groupValues[2].toInt()
                h = it.groupValues[3].toInt()
                return@let
            }
            if (line.startsWith("pkg=")) continue
            if (line.startsWith("y=")) nodes.add(parseNode(line))
        }
        return Screen(pkg, w, h, nodes)
    }

    private fun parseNode(line: String): UiNode {
        val tokens = tokenize(line)
        var top = 0
        var bottom = 0
        var left = 0
        var right = 0
        var cls = ""
        var id = ""
        var text = ""
        var desc = ""
        var hint = ""
        var sel = false
        var chk = false
        var click = false
        var edit = false
        for ((k, v) in tokens) {
            when (k) {
                "y" -> RANGE.find(v)?.let { top = it.groupValues[1].toInt(); bottom = it.groupValues[2].toInt() }
                "x" -> RANGE.find(v)?.let { left = it.groupValues[1].toInt(); right = it.groupValues[2].toInt() }
                "id" -> id = v
                "text" -> text = v
                "desc" -> desc = v
                "hint" -> hint = v
                "sel" -> sel = true
                "chk" -> chk = true
                "click" -> click = true
                "edit" -> edit = true
                "" -> if (cls.isEmpty() && !v.startsWith("⚑") && !v.startsWith("(")) cls = v.takeIf { it != "?" } ?: ""
            }
        }
        return UiNode(id, cls, text, desc, hint, top, bottom, left, right, sel, chk, click, edit)
    }

    /** Разбор строки элемента: key=value, key="строка с \" и \n", одиночные слова (класс, флаги). */
    private fun tokenize(line: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i] == ' ') i++
            if (i >= line.length) break
            val start = i
            while (i < line.length && line[i] != ' ' && line[i] != '=') i++
            if (i < line.length && line[i] == '=') {
                val key = line.substring(start, i)
                i++
                if (i < line.length && line[i] == '"') {
                    i++
                    val sb = StringBuilder()
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length) {
                            i++
                            sb.append(if (line[i] == 'n') '\n' else line[i])
                        } else {
                            sb.append(line[i])
                        }
                        i++
                    }
                    i++ // закрывающая кавычка
                    // длина исходного текста в скобках сразу после кавычки — пропускаем
                    if (i < line.length && line[i] == '(') {
                        while (i < line.length && line[i] != ')') i++
                        i++
                    }
                    out.add(key to sb.toString())
                } else {
                    val vs = i
                    while (i < line.length && line[i] != ' ') i++
                    out.add(key to line.substring(vs, i))
                }
            } else {
                val word = line.substring(start, i)
                when (word) {
                    "sel", "chk", "click", "edit" -> out.add(word to "")
                    else -> out.add("" to word)
                }
            }
        }
        return out
    }
}
