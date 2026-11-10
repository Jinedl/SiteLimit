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

        /** Свой id найден, но в нём не адрес (например, поисковый запрос) — сайт сбрасывается. */
        object NotDomain : Result()

        /** Адресная строка не видна (скрыта при прокрутке) — сайт считается прежним. */
        object None : Result()
    }

    /** Предполагаемые id адресной строки (только повышают приоритет, не обязательны). */
    private val ID_HINTS = listOf("omnibar", "omnibox", "url", "address")

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

    fun find(root: AccessibilityNodeInfo, screenHeight: Int, customId: String): Result {
        val nodes = ArrayList<Node>()
        collect(root, nodes)
        return decide(nodes, screenHeight, customId)
    }

    private fun decide(nodes: List<Node>, screenHeight: Int, customId: String): Result {
        // 1. Свой id из отладки — главный источник.
        var customHadText = false
        for (n in nodes) {
            if (!idMatches(n.id, customId)) continue
            val host = Store.normalizeDomain(n.text) ?: Store.normalizeDomain(n.desc)
            if (host != null) return Result.Domain(host, "свой id")
            if (n.text.isNotBlank()) customHadText = true
        }
        if (customHadText) return Result.NotDomain

        // 2. Эвристика: текст, похожий на домен; выше приоритет у id с omnibar/url
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
        val result = decide(nodes, screenHeight, customId)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("Снимок $time, окно ${root.packageName}, высота экрана $screenHeight\n")
        sb.append(
            "Определено: " + when (result) {
                is Result.Domain -> "${result.host} (${result.source})"
                Result.NotDomain -> "свой id найден, но в нём не адрес"
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
