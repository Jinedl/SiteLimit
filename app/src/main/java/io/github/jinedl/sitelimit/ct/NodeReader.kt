package io.github.jinedl.sitelimit.ct

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Чтение окна приложения в список UiNode: только видимые элементы, без содержимого WebView.
 *
 * Каждый запрос элемента выполняется в главном потоке читаемого приложения (Telegram, VK…),
 * поэтому читается как можно меньше (0.10-beta2, жалоба на подтормаживания):
 *  - у прокручиваемых списков — только первые LIST_ITEMS видимых элементов (шапка и нижняя
 *    панель экрана читаются целиком, а сотни постов и диалогов правилам не нужны);
 *  - не больше LIMIT элементов в результате и VISIT_LIMIT просмотренных.
 */
object NodeReader {
    const val LIMIT = 300
    const val VISIT_LIMIT = 900
    const val LIST_ITEMS = 4

    fun read(root: AccessibilityNodeInfo, limit: Int = LIMIT): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var visited = 0
        while (stack.isNotEmpty() && out.size < limit && visited < VISIT_LIMIT) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue
            val cls = n.className?.toString() ?: ""
            n.getBoundsInScreen(r)
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            if (cls.contains("WebView")) {
                out.add(UiNode(id = id, cls = "WebView", top = r.top, bottom = r.bottom, left = r.left, right = r.right))
                continue
            }
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val hint = n.hintText?.toString() ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || id.isNotEmpty() || n.isSelected || n.isEditable) {
                out.add(
                    UiNode(
                        id = id,
                        cls = cls.substringAfterLast('.'),
                        text = text,
                        desc = desc,
                        hint = hint,
                        top = r.top,
                        bottom = r.bottom,
                        left = r.left,
                        right = r.right,
                        selected = n.isSelected,
                        checked = n.isChecked,
                        clickable = n.isClickable,
                        editable = n.isEditable,
                    )
                )
            }
            val count = n.childCount
            if (n.isScrollable && count > LIST_ITEMS) {
                // Список: первые видимые элементы, в обычном порядке обхода.
                val kids = ArrayList<AccessibilityNodeInfo>(LIST_ITEMS)
                var i = 0
                while (i < count && kids.size < LIST_ITEMS) {
                    n.getChild(i)?.let { if (it.isVisibleToUser) kids.add(it) }
                    i++
                }
                for (k in kids.indices.reversed()) stack.addLast(kids[k])
            } else {
                for (i in count - 1 downTo 0) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
            }
        }
        return out
    }
}
