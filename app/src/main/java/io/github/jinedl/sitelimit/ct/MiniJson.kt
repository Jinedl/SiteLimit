package io.github.jinedl.sitelimit.ct

/**
 * Маленький разбор и запись JSON без зависимостей: одинаково работает на телефоне и в JVM-тестах
 * (org.json в тестах — заглушка). Объекты — LinkedHashMap (порядок полей сохраняется),
 * числа — Long или Double.
 */
object MiniJson {

    class Error(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i < text.length) throw p.err("лишний текст после конца JSON")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw Error("ожидался объект { … }")

    @Suppress("UNCHECKED_CAST")
    fun parseList(text: String): List<Any?> =
        parse(text) as? List<Any?> ?: throw Error("ожидался список [ … ]")

    private class Parser(val s: String) {
        var i = 0

        fun err(m: String): Error {
            val line = s.substring(0, minOf(i, s.length)).count { it == '\n' } + 1
            return Error("$m (строка $line)")
        }

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun value(): Any? {
            if (i >= s.length) throw err("неожиданный конец")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw err("неожиданный символ «$c»")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw err("ожидалось $w")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (peek() == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (peek() != '"') throw err("ожидалось имя поля в кавычках")
                val k = str()
                ws()
                if (peek() != ':') throw err("ожидалось «:» после \"$k\"")
                i++
                ws()
                m[k] = value()
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> throw err("ожидалось «,» или «}»")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (peek() == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l.add(value())
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> throw err("ожидалось «,» или «]»")
                }
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw err("строка без закрывающей кавычки")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw err("обрыв после \\")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw err("короткий \\u")
                                val h = s.substring(i, i + 4).toIntOrNull(16) ?: throw err("плохой \\u")
                                sb.append(h.toChar())
                                i += 4
                            }
                            else -> throw err("неизвестная последовательность \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
            }
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw err("плохое число «$t»")
        }
    }

    /** Запись в JSON. pretty — с отступами (для показа и копирования). */
    fun write(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, v, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, level + 1)
                    quote(sb, k.toString())
                    sb.append(if (level >= 0) ": " else ":")
                    write(sb, value, if (level >= 0) level + 1 else -1)
                }
                newline(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                // Короткие списки строк и чисел — в одну строку, чтобы файл правил читался легче.
                val inline = level >= 0 && items.all { it == null || it is String || it is Number || it is Boolean } &&
                    items.sumOf { (it?.toString()?.length ?: 4) + 4 } < 90
                sb.append('[')
                var first = true
                for (item in items) {
                    if (!first) sb.append(if (inline) ", " else ",")
                    first = false
                    if (!inline) newline(sb, level + 1)
                    write(sb, item, if (level >= 0) level + 1 else -1)
                }
                if (!inline) newline(sb, level)
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), level)
            else -> quote(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

// ---- удобное чтение полей ----

internal fun Map<String, Any?>.str(k: String): String? = this[k] as? String

internal fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Number)?.toInt()

internal fun Map<String, Any?>.long(k: String): Long? = (this[k] as? Number)?.toLong()

internal fun Map<String, Any?>.num(k: String): Double? = (this[k] as? Number)?.toDouble()

internal fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(k: String): List<Any?> = this[k] as? List<Any?> ?: emptyList()

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>
