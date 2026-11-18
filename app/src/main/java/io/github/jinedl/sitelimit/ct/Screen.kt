package io.github.jinedl.sitelimit.ct

/**
 * Элемент окна в виде, удобном для правил и тестов (без классов Android).
 * id — короткий, без «пакет:id/»; cls — короткое имя класса (TextView).
 */
class UiNode(
    val id: String = "",
    val cls: String = "",
    val text: String = "",
    val desc: String = "",
    val hint: String = "",
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val clickable: Boolean = false,
    val editable: Boolean = false,
) {
    val lText: String = text.lowercase()
    val lDesc: String = desc.lowercase()
    val lHint: String = hint.lowercase()
    val lId: String = id.lowercase()
    val lCls: String = cls.lowercase()

    override fun toString(): String = "UiNode(id=$id cls=$cls text=$text desc=$desc y=$top..$bottom)"
}

/** Снимок окна: пакет, размеры экрана и элементы (в порядке обхода дерева). */
class Screen(val pkg: String, val width: Int, val height: Int, val nodes: List<UiNode>)

/** Результат распознавания: сервис, тип, название канала или чата (если нашлось) и сработавшее правило. */
data class Detection(
    val service: String,
    val type: ContentType,
    val title: String? = null,
    val rule: String? = null,
)
