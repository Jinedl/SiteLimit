package io.github.jinedl.sitelimit

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import io.github.jinedl.sitelimit.ct.ActiveMode
import io.github.jinedl.sitelimit.ct.CtGuard
import io.github.jinedl.sitelimit.ct.CtStore
import io.github.jinedl.sitelimit.ct.ManualMode
import io.github.jinedl.sitelimit.ct.ModeSource
import io.github.jinedl.sitelimit.ct.Modes
import io.github.jinedl.sitelimit.ct.Presets
import io.github.jinedl.sitelimit.ct.ScheduleEntry
import io.github.jinedl.sitelimit.ct.StrictGuard
import io.github.jinedl.sitelimit.ct.Ui

/** Режимы: текущий, ручной на срок, расписание, пресеты. */
class ModesActivity : Activity() {

    private lateinit var box: LinearLayout
    private var start = 9 * 60
    private var end = 18 * 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = Ui.page(this, "Режимы и расписание")
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    /** В упрямом режиме менять режим и расписание нельзя совсем. */
    private fun locked(): Boolean = CtGuard.refuse(this, weaker = true)

    private fun modeSpinner(): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@ModesActivity, android.R.layout.simple_spinner_dropdown_item, Modes.ALL.map { Modes.title(it) })
    }

    private fun build() {
        box.removeAllViews()
        CtGuard.banner(this)?.let { box.addView(it) }
        val active = CtGuard.active(this)
        box.addView(Ui.text(this, "Сейчас: ${active.describe()}", 17f, bold = true))
        box.addView(Ui.note(this, "Порядок: упрямый ручной → упрямое расписание → ручной → расписание → «Обычный»."))

        // ---- вручную на срок ----
        box.addView(Ui.header(this, "Вручную на срок"))
        val manual = CtStore.manual(this)?.takeIf { it.until > System.currentTimeMillis() }
        if (manual != null) {
            box.addView(Ui.text(this, "Ручной: «${Modes.title(manual.mode)}» до ${ActiveMode.timeText(manual.until)}" +
                if (manual.strict) " 🔒 упрямый" else ""))
            box.addView(Ui.button(this, "Остановить ручной режим") {
                if (locked()) return@button
                CtStore.setManual(this, null)
                build()
            })
        }
        val sp = modeSpinner().apply { setSelection(Modes.ALL.indexOf(Modes.STUDY)) }
        val minutes = Ui.input(this, "на сколько минут", "60", number = true)
        val strict = CheckBox(this).apply { text = "Упрямый: до конца срока правила не ослабить" }
        box.addView(sp)
        box.addView(Ui.hbox(this, Ui.text(this, "Минут:"), minutes))
        box.addView(strict)
        box.addView(Ui.button(this, "Включить") {
            if (locked()) return@button
            val m = minutes.text.toString().trim().toIntOrNull()
            if (m == null || m !in 1..24 * 60) {
                Ui.toast(this, "Срок — от 1 до 1440 минут")
                return@button
            }
            val mode = Modes.ALL[sp.selectedItemPosition]
            val go = {
                CtStore.setManual(this, ManualMode(mode, System.currentTimeMillis() + m * 60_000L, strict.isChecked))
                build()
            }
            if (strict.isChecked) {
                Ui.confirm(this, "Упрямый режим «${Modes.title(mode)}» на $m мин: до конца срока правила можно только ужесточать, " +
                    "режим и расписание не поменять, бету не выключить. Включить?", "Включить") { go() }
            } else go()
        })

        // ---- расписание ----
        box.addView(Ui.header(this, "Расписание"))
        val list = CtStore.schedule(this)
        if (list.isEmpty()) box.addView(Ui.note(this, "Пока пусто. Например: «Учёба/Работа» по будням 09:00–18:00."))
        list.forEachIndexed { i, e ->
            val on = active.source == ModeSource.SCHEDULE && active.mode == e.mode
            box.addView(Ui.row(this, e.describe() + if (on) "  (идёт)" else "", onLong = {
                if (locked()) return@row
                Ui.confirm(this, "Удалить «${e.describe()}»?", "Удалить") {
                    CtStore.setSchedule(this, list.filterIndexed { j, _ -> j != i })
                    build()
                }
            }))
        }
        if (list.isNotEmpty()) box.addView(Ui.note(this, "Долгое нажатие — удалить."))
        val sp2 = modeSpinner().apply { setSelection(Modes.ALL.indexOf(Modes.STUDY)) }
        box.addView(sp2)
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val days = names.mapIndexed { i, n -> CheckBox(this).apply { text = n; isChecked = i < 5 } }
        box.addView(Ui.hbox(this, *days.take(4).toTypedArray()))
        box.addView(Ui.hbox(this, *days.drop(4).toTypedArray()))
        val bStart = Button(this).apply { isAllCaps = false }
        val bEnd = Button(this).apply { isAllCaps = false }
        fun label() {
            bStart.text = "С ${ScheduleEntry.hhmm(start)}"
            bEnd.text = "До ${ScheduleEntry.hhmm(end)}"
        }
        label()
        bStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> start = h * 60 + m; label() }, start / 60, start % 60, true).show()
        }
        bEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> end = h * 60 + m; label() }, end / 60, end % 60, true).show()
        }
        box.addView(Ui.hbox(this, bStart, bEnd))
        val strict2 = CheckBox(this).apply { text = "Упрямый: пока идёт, правила не ослабить" }
        box.addView(strict2)
        box.addView(Ui.note(this, "Конец раньше начала — через полночь. Конец = начало — весь день."))
        box.addView(Ui.button(this, "Добавить в расписание") {
            if (locked()) return@button
            var mask = 0
            days.forEachIndexed { i, cb -> if (cb.isChecked) mask = mask or (1 shl i) }
            if (mask == 0) {
                Ui.toast(this, "Отметьте дни")
                return@button
            }
            CtStore.setSchedule(this, list + ScheduleEntry(Modes.ALL[sp2.selectedItemPosition], mask, start, end, strict2.isChecked))
            build()
        })

        // ---- пресеты ----
        box.addView(Ui.header(this, "Пресеты"))
        box.addView(Ui.note(this, "Пресет заменяет правила выбранного режима, дальше их можно менять в «Лимиты по типам». " +
            "Сначала у каждого режима — пресет с тем же названием, у «Обычного» — без ограничений."))
        for (p in Presets.ALL) {
            box.addView(Ui.text(this, "«${Presets.title(p)}»: ${Presets.describe(p)}"))
            box.addView(Ui.button(this, "Применить «${Presets.title(p)}» к режиму…") { applyPreset(p) })
        }
    }

    private fun applyPreset(preset: String) {
        Ui.choose(this, "К какому режиму применить «${Presets.title(preset)}»?", Modes.ALL.map { Modes.title(it) }) { i ->
            val mode = Modes.ALL[i]
            val services = CtStore.loadRules(this).book.services.map { it.id }
            val new = Presets.build(preset, services)
            val old = CtStore.modeRules(this, mode, services)
            val isActive = CtGuard.active(this).mode == mode
            if (isActive && CtGuard.refuse(this, StrictGuard.weakerRules(old, new))) return@choose
            Ui.confirm(this, "Заменить правила режима «${Modes.title(mode)}» пресетом «${Presets.title(preset)}»?", "Заменить") {
                CtStore.setModeRules(this, mode, new)
                Ui.toast(this, "Готово: «${Modes.title(mode)}» = «${Presets.title(preset)}»")
                build()
            }
        }
    }
}
