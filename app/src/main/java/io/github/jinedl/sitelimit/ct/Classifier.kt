package io.github.jinedl.sitelimit.ct

/** Распознавание типа экрана по файлу правил. Чистая функция — проверяется JVM-тестами на снимках. */
object Classifier {

    fun classify(svc: ServiceDef, screen: Screen): Detection {
        for (rule in svc.screens) {
            if (matches(rule, screen)) {
                return Detection(svc.id, rule.type, title(rule, screen), rule.id)
            }
        }
        return Detection(svc.id, svc.defaultType, null, null)
    }

    fun matches(rule: ScreenRule, screen: Screen): Boolean {
        if (rule.packages.isNotEmpty() && screen.pkg !in rule.packages) return false
        for (c in rule.all) if (!c.found(screen)) return false
        if (rule.any.isNotEmpty() && rule.any.none { it.found(screen) }) return false
        for (c in rule.none) if (c.found(screen)) return false
        return true
    }

    fun title(rule: ScreenRule, screen: Screen): String? {
        for (t in rule.titles) {
            val anchor = screen.nodes.firstOrNull { t.from.test(it, screen.height) } ?: continue
            // Выше / ниже — только элементы в той же колонке (пересекаются по горизонтали):
            // иначе в Telegram название канала путается с аватаром («Посмотреть историю») слева.
            val node = when (t.pick) {
                "above" -> screen.nodes
                    .filter { it !== anchor && it.text.isNotBlank() && it.top < anchor.top && it.bottom <= anchor.bottom && sameColumn(it, anchor) }
                    .maxByOrNull { it.top }
                "below" -> screen.nodes
                    .filter { it !== anchor && it.text.isNotBlank() && it.top > anchor.top && sameColumn(it, anchor) }
                    .minByOrNull { it.top }
                else -> anchor
            } ?: continue
            val raw = when (t.field) {
                "text" -> node.text
                "desc" -> node.desc
                else -> node.text.ifBlank { node.desc }
            }
            val picked = if (t.re != null) {
                val m = t.re.find(raw) ?: continue
                (if (m.groupValues.size > 1) m.groupValues[1] else m.value)
            } else raw
            val clean = picked.lineSequence().firstOrNull()?.trim()?.take(80) ?: ""
            if (clean.isNotEmpty()) return clean
        }
        return null
    }

    /** Пересекаются по горизонтали. Элементы без координат (в тестах) считаются в одной колонке. */
    private fun sameColumn(a: UiNode, b: UiNode): Boolean {
        if (a.right <= a.left || b.right <= b.left) return true
        return a.left < b.right && b.left < a.right
    }
}
