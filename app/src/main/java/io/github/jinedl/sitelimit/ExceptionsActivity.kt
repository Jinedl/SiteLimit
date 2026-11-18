package io.github.jinedl.sitelimit

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import io.github.jinedl.sitelimit.ct.CtGuard
import io.github.jinedl.sitelimit.ct.CtStore
import io.github.jinedl.sitelimit.ct.ExKind
import io.github.jinedl.sitelimit.ct.ExRule
import io.github.jinedl.sitelimit.ct.Exceptions
import io.github.jinedl.sitelimit.ct.StrictGuard
import io.github.jinedl.sitelimit.ct.Ui

/** Исключения: «всегда можно / всегда нельзя» для канала, чата или сайта; «считать каналом» для большого чата. */
class ExceptionsActivity : Activity() {

    private lateinit var box: LinearLayout
    private lateinit var serviceIds: List<String>
    private lateinit var serviceTitles: List<String>
    private var spinner: Spinner? = null
    private var nameInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = Ui.page(this, "Исключения")
        val services = CtStore.loadRules(this).book.services
        serviceIds = services.map { it.id } + listOf(Exceptions.SITE, Exceptions.ANY)
        serviceTitles = services.map { it.title } + listOf("Сайт (браузер)", "Любой сервис")
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun titleOf(service: String): String = serviceTitles.getOrNull(serviceIds.indexOf(service)) ?: service

    private fun save(new: List<ExRule>) {
        if (CtGuard.refuse(this, StrictGuard.weakerExceptions(CtStore.exceptions(this), new))) return
        CtStore.setExceptions(this, new)
        build()
    }

    private fun build() {
        val keepName = nameInput?.text?.toString() ?: ""
        val keepSvc = spinner?.selectedItemPosition ?: 0
        box.removeAllViews()
        CtGuard.banner(this)?.let { box.addView(it) }
        box.addView(Ui.note(this, "Название — как в шапке канала или чата (сравнивается без учёта регистра и пробелов). " +
            "Сайт — домен, вместе с поддоменами. «Считать каналом» — большой чат по лимиту каналов. " +
            "Исключения действуют во всех режимах."))

        val list = CtStore.exceptions(this)
        box.addView(Ui.header(this, "Список"))
        if (list.isEmpty()) box.addView(Ui.note(this, "Пока нет. Долгое нажатие на строку — удалить."))
        for (e in list) {
            box.addView(Ui.row(this, "${titleOf(e.service)} · «${e.name}» — ${e.kind.title}", onLong = {
                Ui.confirm(this, "Удалить исключение «${e.name}»?", "Удалить") { save(list - e) }
            }))
        }

        box.addView(Ui.header(this, "Добавить"))
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@ExceptionsActivity, android.R.layout.simple_spinner_dropdown_item, serviceTitles)
            setSelection(keepSvc.coerceIn(0, serviceTitles.size - 1))
        }
        spinner = sp
        val input = Ui.input(this, "название канала или чата / сайт, например vk.com", keepName)
        nameInput = input
        box.addView(sp)
        box.addView(input)
        for (kind in ExKind.entries) {
            box.addView(Ui.button(this, "Добавить: ${kind.title}") { add(kind) })
        }

        val recent = CtStore.recentTitles(this)
        box.addView(Ui.header(this, "Недавно открытые"))
        if (recent.isEmpty()) {
            box.addView(Ui.note(this, "Здесь появятся названия каналов и чатов, которые распознала служба (бета должна быть включена)."))
        }
        for (r in recent) {
            box.addView(Ui.row(this, "${titleOf(r.service)} · «${r.title}» — ${r.type.short}", onClick = {
                sp.setSelection(serviceIds.indexOf(r.service).coerceAtLeast(0))
                input.setText(r.title)
            }))
        }
    }

    private fun add(kind: ExKind) {
        val service = serviceIds[spinner?.selectedItemPosition ?: 0]
        var name = nameInput?.text?.toString()?.trim() ?: ""
        if (service == Exceptions.SITE) {
            if (kind == ExKind.AS_CHANNEL) {
                Ui.toast(this, "«Считать каналом» — только для чатов")
                return
            }
            name = Store.normalizeDomain(name) ?: run {
                Ui.toast(this, "Не похоже на адрес сайта, пример: vk.com")
                return
            }
        }
        if (name.isEmpty()) {
            Ui.toast(this, "Впишите название или нажмите на недавнее")
            return
        }
        val rule = ExRule(service, name, kind)
        val list = CtStore.exceptions(this).filterNot { it.service == service && it.key == rule.key && it.kind == kind }
        nameInput?.setText("")
        save(list + rule)
        Ui.toast(this, "«$name» — ${kind.title}")
    }
}
