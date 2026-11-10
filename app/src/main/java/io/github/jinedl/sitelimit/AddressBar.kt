package io.github.jinedl.sitelimit

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Поиск адресной строки в дереве элементов Яндекс Браузера. */
object AddressBar {

    sealed class Result {
        /** Нашли домен в адресной строке. */
        data class Domain(val host: String, val source: String) : Result()

        /** На экране точно не сайт (поиск, список вкладок, главная) — текущий сайт сбрасывается. */
        data class NotDomain(val reason: String) : Result()

        /** Адресная строка не видна (скрыта при прокрутке) — сайт считается прежним. */
        object None : Result()
    }

    /** Предполагаемые id адресной строки (только повышают приоритет, не обязательны). */
    private val ID_HINTS = listOf("omnibar", "omnibox", "url", "address")

    /** Проверенный id адресной строки браузеров Яндекса (снимок с телефона, 11.11.2026). */
    private const val KNOWN_ID = "bro_omnibar_address_title_text"

    /** Части id экранов, где сайта нет: список вкладок, строка поиска с подсказками, главная. */
    private val NOT_SITE_HINTS = listOf("tablist_", "suggest_view", "screen_morda")

    private class Node(
        val text: String,
        val desc: String,
        val id: String,
        val cls: String,
        val top: Int,
        val bottom: Int,
    )

    /** Обход дерева без содержимого страниц (WebView): там может быть любой текст. */
    private fun collect(root: AccessibilityNodeInfo, out: MutableList<Node>, limit: Int = 400) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        while (stack.isNotEmpty() && out.size < limit) {
            val n = stack.removeLast()
            val cls = n.className?.toString() ?: ""
            n.getBoundsInScreen(r)
            if (cls.contains("WebView")) {
                out.add(Node("", "", n.viewIdResourceName ?: "", "[WebView: содержимое пропущено]", r.top, r.bottom))
                continue
            }
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val id = n.viewIdResourceName ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
                out.add(Node(text, desc, id, cls.substringAfterLast('.'), r.top, r.bottom))
            }
            for (i in n.childCount - 1 downTo 0) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }

    private fun idMatches(id: String, wanted: String): Boolean =
        wanted.isNotEmpty() && (id == wanted || id.endsWith("/$wanted"))

    /** Точные id экранов без сайта (для быстрого поиска): строка поиска, список вкладок, главная. */
    private val NOT_SITE_IDS = listOf("suggest_view", "tablist_layout", "screen_morda")

    /** Приложения, где проверенный id адресной строки уже находился (за время работы службы). */
    private val knownIdApps = HashSet<String>()

    /**
     * Быстрый путь: поиск элементов по id — несколько запросов к системе вместо обхода
     * сотен элементов. Полный обход — только в приложениях, где известный id не встречался.
     */
    fun find(root: AccessibilityNodeInfo, screenHeight: Int, customId: String): Result {
        val pkg = root.packageName?.toString() ?: ""
        fun byId(id: String): List<AccessibilityNodeInfo> =
            root.findAccessibilityNodeInfosByViewId(if (id.contains(":id/")) id else "$pkg:id/$id")
                ?: emptyList()

        if (customId.isNotEmpty()) fastIdResult(byId(customId), "свой id")?.let { return it }
        for (id in NOT_SITE_IDS) {
            if (byId(id).isNotEmpty()) return Result.NotDomain("экран без сайта: $id")
        }
        fastIdResult(byId(KNOWN_ID), "id адресной строки")?.let {
            knownIdApps.add(pkg)
            return it
        }
        // Строка в этом приложении находилась раньше, а сейчас её нет — она скрыта при прокрутке.
        if (pkg in knownIdApps) return Result.None

        val nodes = ArrayList<Node>()
        collect(root, nodes)
        return decide(nodes, screenHeight, customId)
    }

    private fun fastIdResult(found: List<AccessibilityNodeInfo>, name: String): Result? {
        var hadText = false
        for (n in found) {
            val host = Store.normalizeDomain(n.text) ?: Store.normalizeDomain(n.contentDescription)
            if (host != null) return Result.Domain(host, name)
            if (!n.text.isNullOrBlank()) hadText = true
        }
        return if (hadText) Result.NotDomain("в $name не адрес") else null
    }

    /** Домен из элемента с нужным id; NotDomain, если там текст, но не адрес; null, если элемента нет. */
    private fun idResult(nodes: List<Node>, wanted: String, name: String): Result? {
        var hadText = false
        for (n in nodes) {
            if (!idMatches(n.id, wanted)) continue
            val host = Store.normalizeDomain(n.text) ?: Store.normalizeDomain(n.desc)
            if (host != null) return Result.Domain(host, name)
            if (n.text.isNotBlank()) hadText = true
        }
        return if (hadText) Result.NotDomain("в $name не адрес") else null
    }

    private fun decide(nodes: List<Node>, screenHeight: Int, customId: String): Result {
        // 1. Свой id из отладки (если задан) — главнее всего.
        idResult(nodes, customId, "свой id")?.let { return it }

        // 2. Список вкладок, поиск, главная — сайта на экране нет (даже если строка с прошлым адресом видна).
        nodes.firstOrNull { n -> NOT_SITE_HINTS.any { n.id.contains(it) } }?.let {
            return Result.NotDomain("экран без сайта: ${it.id.substringAfter(":id/")}")
        }

        // 2б. Проверенный id адресной строки.
        idResult(nodes, KNOWN_ID, "id адресной строки")?.let { return it }

        // 3. Эвристика: текст, похожий на домен; выше приоритет у id с omnibar/url
        //    и у элементов в верхней или нижней четверти экрана.
        var best: Result.Domain? = null
        var bestScore = -1
        val h = if (screenHeight > 0) screenHeight else 2000
        for (n in nodes) {
            val fromText = Store.normalizeDomain(n.text)
            val host = fromText ?: Store.normalizeDomain(n.desc) ?: continue
            var score = 0
            val shortId = n.id.substringAfter(":id/").lowercase()
            if (ID_HINTS.any { shortId.contains(it) }) score += 50
            if (n.top < h / 4) score += 20 else if (n.bottom > h * 3 / 4) score += 10
            if (fromText != null) score += 1
            if (score > bestScore) {
                bestScore = score
                best = Result.Domain(host, "эвристика, id=${shortId.ifEmpty { "—" }}")
            }
        }
        return best ?: Result.None
    }

    /** Текст для отладочного режима: все элементы окна браузера (кроме страниц) с id и текстом. */
    fun dump(root: AccessibilityNodeInfo, screenHeight: Int, customId: String): String {
        val nodes = ArrayList<Node>()
        collect(root, nodes)
        val result = find(root, screenHeight, customId)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("Снимок $time, окно ${root.packageName}, высота экрана $screenHeight\n")
        sb.append(
            "Определено: " + when (result) {
                is Result.Domain -> "${result.host} (${result.source})"
                is Result.NotDomain -> "не сайт (${result.reason})"
                Result.None -> "адрес не найден"
            } + "\n"
        )
        sb.append("Свой id: ${customId.ifEmpty { "не задан" }}\n\n")
        sb.append("y — верх элемента, id — что можно вписать в «свой id»\n\n")
        for (n in nodes.sortedBy { it.top }.take(150)) {
            sb.append("y=${n.top} ${n.cls}")
            if (n.id.isNotEmpty()) sb.append(" id=${n.id.substringAfter(":id/")}")
            if (n.text.isNotEmpty()) sb.append(" text=\"${n.text.take(80)}\"")
            if (n.desc.isNotEmpty()) sb.append(" desc=\"${n.desc.take(80)}\"")
            sb.append('\n')
        }
        return sb.toString()
    }
}
