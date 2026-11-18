package io.github.jinedl.sitelimit.ct

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Чтение окна приложения в список UiNode: только видимые элементы, без содержимого WebView,
 * не больше LIMIT элементов (чтобы не грузить телефон на длинных экранах).
 */
object NodeReader {
    const val LIMIT = 400

    fun read(root: AccessibilityNodeInfo, limit: Int = LIMIT): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var visited = 0
        while (stack.isNotEmpty() && out.size < limit && visited < limit * 3) {
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
            for (i in n.childCount - 1 downTo 0) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }
}
