package io.github.jinedl.sitelimit

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.LinearLayout
import io.github.jinedl.sitelimit.ct.ContentType
import io.github.jinedl.sitelimit.ct.CtGuard
import io.github.jinedl.sitelimit.ct.CtStore
import io.github.jinedl.sitelimit.ct.Group
import io.github.jinedl.sitelimit.ct.Key
import io.github.jinedl.sitelimit.ct.Limit
import io.github.jinedl.sitelimit.ct.ModeRules
import io.github.jinedl.sitelimit.ct.Modes
import io.github.jinedl.sitelimit.ct.ServiceDef
import io.github.jinedl.sitelimit.ct.StrictGuard
import io.github.jinedl.sitelimit.ct.Summary
import io.github.jinedl.sitelimit.ct.Ui

/** Сервисы → типы → лимиты для выбранного режима, общие лимиты и пакеты сервисов. */
class RulesActivity : Activity() {

    private lateinit var box: LinearLayout
    private var mode: String = Modes.NORMAL
    private var services: List<ServiceDef> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = Ui.page(this, "Лимиты по типам")
        mode = CtGuard.active(this).mode
        services = CtStore.loadRules(this).book.services
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun rules(): ModeRules = CtStore.modeRules(this, mode, services.map { it.id })

    /** Режим, который сейчас действует, в упрямом режиме можно только ужесточать. */
    private fun save(new: ModeRules) {
        val isActive = CtGuard.active(this).mode == mode
        if (isActive && CtGuard.refuse(this, StrictGuard.weakerRules(rules(), new))) return
        CtStore.setModeRules(this, mode, new)
        build()
    }

    private fun build() {
        box.removeAllViews()
        CtGuard.banner(this)?.let { box.addView(it) }
        val active = CtGuard.active(this)
        box.addView(Ui.note(this, "Сейчас действует режим ${active.describe()}. Ниже — правила выбранного режима."))

        box.addView(Ui.header(this, "Режим для правки"))
        for (m in Modes.ALL) {
            val mark = if (m == mode) "● " else "○ "
            box.addView(Ui.row(this, mark + Modes.title(m) + if (m == active.mode) "  (действует)" else "", onClick = {
                mode = m
                build()
            }))
        }
        box.addView(Ui.note(this, "Пресет к режиму применяется на экране «Режимы и расписание». Пусто — без лимита, 0 — закрыто. " +
            "«Неизвестно» не ограничивается никогда."))

        val r = rules()
        val book = Ui.usage(this)
        val date = Ui.today()

        box.addView(Ui.header(this, "Общие лимиты (все сервисы вместе)"))
        if (r.groups.isEmpty()) box.addView(Ui.note(this, "Нет. Например, «Отдых»: ленты и ролики — 60 мин в день на всё."))
        r.groups.forEachIndexed { i, g ->
            val used = book.groupSeconds(date, g.types) / 60
            box.addView(Ui.row(this, "${g.title()}: ${if (g.minutes == 0) "закрыто" else "${g.minutes} мин"} · сегодня $used мин",
                onClick = { editGroup(i, g) }))
        }
        box.addView(Ui.button(this, "+ Общий лимит") { editGroup(-1, null) })

        for (s in services) {
            box.addView(Ui.header(this, s.title))
            val pkgs = CtStore.packagesFor(this, s)
            val known = s.packages.associateBy { it.name }
            val pkgText = pkgs.joinToString(", ") { p -> p + " " + (known[p]?.mark ?: "🔶") }
            box.addView(Ui.row(this, "Пакеты: $pkgText", onClick = { editPackages(s) }).apply { textSize = 13f })
            for (t in ContentType.LIMITED) {
                val l = r.limit(s.id, t)
                val today = Summary.today(book, date, Key(s.id, t))
                box.addView(Ui.row(this, "${t.title}: ${l.describe()} · $today", onClick = {
                    Ui.askLimit(this, "${s.title} — ${t.title}", l) { save(rules().with(s.id, t, it)) }
                }))
            }
            val unk = book.seconds(date, Key(s.id, ContentType.UNKNOWN)) / 60
            box.addView(Ui.note(this, "Неизвестные экраны: сегодня $unk мин (не ограничиваются, видны в отладке)."))
        }
    }

    private fun editGroup(index: Int, g: Group?) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@RulesActivity, 20)
            setPadding(p, Ui.dp(this@RulesActivity, 8), p, 0)
        }
        val checks = ContentType.LIMITED.map { t ->
            CheckBox(this).apply {
                text = t.title
                isChecked = g?.types?.contains(t) ?: (t == ContentType.FEED || t == ContentType.SHORTS || t == ContentType.VIDEO)
            }
        }
        checks.forEach { wrap.addView(it) }
        val minutes = Ui.input(this, "минут в день на всё вместе", g?.minutes?.toString() ?: "60", number = true)
        minutes.inputType = InputType.TYPE_CLASS_NUMBER
        wrap.addView(minutes)
        val b = AlertDialog.Builder(this).setTitle(if (g == null) "Новый общий лимит" else "Общий лимит").setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val types = ContentType.LIMITED.filterIndexed { i, _ -> checks[i].isChecked }.toSet()
                val m = minutes.text.toString().trim().toIntOrNull()
                when {
                    types.isEmpty() -> Ui.toast(this, "Отметьте хотя бы один тип")
                    m == null || m !in 0..1440 -> Ui.toast(this, "Минуты — от 0 до 1440")
                    else -> {
                        val list = rules().groups.toMutableList()
                        if (index in list.indices) list[index] = Group(types, m) else list.add(Group(types, m))
                        save(rules().copy(groups = list))
                    }
                }
            }
            .setNegativeButton("Отмена", null)
        if (g != null) b.setNeutralButton("Удалить") { _, _ ->
            val list = rules().groups.toMutableList()
            if (index in list.indices) list.removeAt(index)
            save(rules().copy(groups = list))
        }
        b.show()
    }

    private fun editPackages(s: ServiceDef) {
        val current = CtStore.packagesFor(this, s)
        val input = Ui.input(this, "пакеты через запятую", current.joinToString(", "))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@RulesActivity, 20)
            setPadding(p, Ui.dp(this@RulesActivity, 8), p, 0)
            addView(Ui.note(this@RulesActivity, "Имя пакета видно в отладке: «Последнее другое приложение». " +
                "✅ — проверено (снимок или страница магазина), 🔶 — догадка.\nПо умолчанию: " +
                s.packages.joinToString(", ") { "${it.name} ${it.mark}" }))
            addView(input)
        }
        AlertDialog.Builder(this).setTitle("${s.title}: пакеты").setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val list = input.text.toString().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (list.any { !it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }) {
                    Ui.toast(this, "Похоже не на имя пакета (пример: org.telegram.messenger)")
                    return@setPositiveButton
                }
                if (CtGuard.refuse(this, StrictGuard.weakerPackages(current, list))) return@setPositiveButton
                CtStore.setPackages(this, s.id, list)
                build()
            }
            .setNeutralButton("По умолчанию") { _, _ ->
                val def = s.packages.map { it.name }
                if (CtGuard.refuse(this, StrictGuard.weakerPackages(current, def))) return@setNeutralButton
                CtStore.setPackages(this, s.id, null)
                build()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
