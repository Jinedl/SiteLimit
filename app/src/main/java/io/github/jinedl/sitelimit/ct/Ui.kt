package io.github.jinedl.sitelimit.ct

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.ZonedDateTime

/** Простые элементы экранов (обычные View, без дизайна). */
object Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    private var lastClickAt = 0L

    /**
     * Повторное нажатие быстрее 0,6 с не считается: если телефон подтормаживает, второе нажатие
     * открывало второй такой же диалог, и его приходилось закрывать дважды.
     */
    fun once(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastClickAt < 600) return false
        lastClickAt = now
        return true
    }

    /** Страница с прокруткой; возвращает вертикальный контейнер. */
    fun page(a: Activity, title: String): LinearLayout {
        a.title = title
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(a, 16)
            setPadding(p, p, p, p)
        }
        a.setContentView(ScrollView(a).apply {
            fitsSystemWindows = true
            addView(box)
        })
        return box
    }

    fun text(c: Context, s: CharSequence, size: Float = 16f, bold: Boolean = false): TextView = TextView(c).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(c, 4), 0, dp(c, 4))
    }

    fun header(c: Context, s: String): TextView = text(c, s, 19f, bold = true).apply {
        setPadding(0, dp(c, 18), 0, dp(c, 4))
    }

    fun note(c: Context, s: String): TextView = text(c, s, 13f)

    fun button(c: Context, s: String, onClick: () -> Unit): Button = Button(c).apply {
        text = s
        isAllCaps = false
        setOnClickListener { if (once()) onClick() }
    }

    /** Строка списка: нажатие и долгое нажатие. */
    fun row(c: Context, s: CharSequence, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null): TextView =
        text(c, s, 16f).apply {
            setPadding(0, dp(c, 10), 0, dp(c, 10))
            if (onClick != null) setOnClickListener { if (once()) onClick() }
            if (onLong != null) setOnLongClickListener {
                if (once()) onLong()
                true
            }
        }

    fun hbox(c: Context, vararg views: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun input(c: Context, hint: String, value: String = "", number: Boolean = false): EditText = EditText(c).apply {
        this.hint = hint
        setText(value)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    fun confirm(a: Activity, message: String, yes: String = "Да", onYes: () -> Unit) {
        AlertDialog.Builder(a).setMessage(message).setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Отмена", null).show()
    }

    fun choose(a: Activity, title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton("Отмена", null).show()
    }

    /** Диалог лимита: минуты и открытия в день (пусто — без лимита, 0 — закрыто). */
    fun askLimit(a: Activity, title: String, current: Limit, onOk: (Limit) -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(a, 20)
            setPadding(p, dp(a, 8), p, 0)
        }
        val minutes = input(a, "минут в день", current.minutes?.toString() ?: "", number = true)
        val opens = input(a, "открытий в день", current.opens?.toString() ?: "", number = true)
        box.addView(note(a, "Пусто — без лимита, 0 — закрыто."))
        box.addView(text(a, "Минут в день:"))
        box.addView(minutes)
        box.addView(text(a, "Открытий в день:"))
        box.addView(opens)
        AlertDialog.Builder(a).setTitle(title).setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val m = minutes.text.toString().trim().let { if (it.isEmpty()) null else it.toIntOrNull() }
                val o = opens.text.toString().trim().let { if (it.isEmpty()) null else it.toIntOrNull() }
                when {
                    minutes.text.isNotBlank() && (m == null || m !in 0..1440) -> toast(a, "Минуты — от 0 до 1440")
                    opens.text.isNotBlank() && (o == null || o !in 0..999) -> toast(a, "Открытия — от 0 до 999")
                    else -> onOk(Limit(m, o))
                }
            }
            .setNeutralButton("Без лимита") { _, _ -> onOk(Limit()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Счётчики: у работающей службы — в памяти, иначе — сохранённые. */
    fun usage(c: Context): UsageBook = CtLive.book ?: UsageBook().apply { load(CtStore.loadUsage(c)) }

    fun today(): String = LocalDate.now().toString()
}

/** Упрямый режим в экранах: пока он идёт, ослабить правила нельзя. */
object CtGuard {
    fun active(c: Context): ActiveMode = Scheduler.resolve(ZonedDateTime.now(), CtStore.schedule(c), CtStore.manual(c))

    fun isStrict(c: Context): Boolean = CtStore.isEnabled(c) && active(c).strict

    /** true — действие запрещено (показано сообщение). weaker — действие ослабляет правила. */
    fun refuse(c: Context, weaker: Boolean): Boolean {
        if (!weaker) return false
        val a = active(c)
        if (!(CtStore.isEnabled(c) && a.strict)) return false
        Ui.toast(c, StrictGuard.MESSAGE + ": " + a.describe().removeSuffix(" 🔒 упрямый"))
        return true
    }

    fun banner(c: Context): TextView? {
        if (!isStrict(c)) return null
        val a = active(c).describe().removeSuffix(" 🔒 упрямый")
        return Ui.text(c, "🔒 Упрямый режим $a: правила можно только ужесточать.", 15f, bold = true)
    }
}
