package io.github.jinedl.sitelimit

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.jinedl.sitelimit.ct.CtGuard
import io.github.jinedl.sitelimit.ct.CtStore
import io.github.jinedl.sitelimit.ct.MiniJson
import io.github.jinedl.sitelimit.ct.RuleBook
import io.github.jinedl.sitelimit.ct.Ui

/** Отладка: открывается долгим нажатием на строку версии внизу главного экрана. */
class DebugActivity : Activity() {

    private lateinit var diagText: TextView
    private lateinit var ctText: TextView
    private lateinit var snapsButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Отладка"
        val pad = (16 * resources.displayMetrics.density).toInt()

        diagText = TextView(this).apply { textSize = 15f }

        val checkDebug = CheckBox(this).apply {
            text = "Сохранять снимки окна (браузер раз в 3 с; при бете — и сервисы)"
            isChecked = Store.isDebug(this@DebugActivity)
            setOnCheckedChangeListener { _, on -> Store.setDebug(this@DebugActivity, on) }
        }
        val btnDump = Button(this).apply {
            text = "Показать снимок браузера"
            setOnClickListener { showDump() }
        }
        val inputId = EditText(this).apply {
            hint = "свой id адресной строки"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Store.customId(this@DebugActivity))
        }
        val btnSaveId = Button(this).apply {
            text = "Сохранить id"
            setOnClickListener {
                Store.setCustomId(this@DebugActivity, inputId.text.toString())
                toast(if (inputId.text.isBlank()) "Свой id убран" else "id сохранён")
            }
        }

        // ---- типы контента (бета) ----
        ctText = TextView(this).apply { textSize = 15f }
        val checkMask = CheckBox(this).apply {
            text = "В снимках сервисов скрывать буквы и цифры (кроме слов из правил)"
            isChecked = CtStore.isMask(this@DebugActivity)
            setOnCheckedChangeListener { _, on -> CtStore.setMask(this@DebugActivity, on) }
        }
        snapsButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { showSnapshots() }
        }
        val btnRules = Button(this).apply {
            isAllCaps = false
            text = "Файл правил: показать и скопировать"
            setOnClickListener { showRules() }
        }
        val btnCustom = Button(this).apply {
            isAllCaps = false
            text = "Свои правила: вставить JSON"
            setOnClickListener { editCustomRules() }
        }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(LinearLayout(this@DebugActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(diagText)
                addView(checkDebug)
                addView(btnDump)
                addView(inputId)
                addView(btnSaveId)
                addView(Ui.header(this@DebugActivity, "Типы контента (бета)"))
                addView(ctText)
                addView(checkMask)
                addView(snapsButton)
                addView(btnRules)
                addView(btnCustom)
                addView(Ui.note(this@DebugActivity, "Как снять снимок: включите галочку снимков, откройте нужный экран в сервисе, " +
                    "подождите 3–5 с, переключитесь сюда через «Недавние», откройте снимок → «Копировать». " +
                    "Перед отправкой проверьте, что нет личного."))
            })
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        diagText.text = buildString {
            append("Сейчас в браузере: ${Store.currentSite(this@DebugActivity) ?: "—"}")
            append("\nПоследняя проверка: ${Store.diag(this@DebugActivity) ?: "служба ещё не запускалась"}")
            append("\nПоследнее другое приложение: ${Store.diagOther(this@DebugActivity) ?: "—"}")
        }
        val rules = CtStore.loadRules(this)
        ctText.text = buildString {
            append("Бета: ${if (CtStore.isEnabled(this@DebugActivity)) "включена" else "выключена (снимков сервисов нет)"}")
            append("\nСейчас: ${CtStore.now(this@DebugActivity) ?: "—"}")
            append("\nПоследняя блокировка: ${CtStore.lastBlock(this@DebugActivity) ?: "—"}")
            append("\nПравила: v${rules.book.version} от ${rules.book.updated}, ${if (rules.custom) "свои" else "встроенные"}")
            rules.error?.let { append("\n⚠ $it") }
        }
        snapsButton.text = "Снимки сервисов (${CtStore.snapshots(this).size})"
    }

    private fun textDialog(title: String, text: String, copyLabel: String = "Копировать", extra: Pair<String, () -> Unit>? = null) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(pad, pad, pad, pad)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(copyLabel) { _, _ -> copy(text) }
            .setNegativeButton("Закрыть", null)
        extra?.let { (label, action) -> b.setNeutralButton(label) { _, _ -> action() } }
        b.show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("SiteLimit", text))
        toast("Скопировано")
    }

    private fun showDump() {
        val dump = Store.debugDump(this)
            ?: ("Снимка пока нет.\n\n1. Включите галочку выше.\n2. Откройте браузер на любом сайте.\n" +
                "3. Подождите 3–5 секунд и вернитесь сюда.\n\n" +
                "Галочка отладки: ${if (Store.isDebug(this)) "включена" else "выключена"}")
        textDialog("Снимок окна браузера", dump)
    }

    private fun showSnapshots() {
        val snaps = CtStore.snapshots(this)
        if (snaps.isEmpty()) {
            toast(if (CtStore.isEnabled(this)) "Снимков пока нет: включите галочку и откройте сервис" else "Сначала включите бету на главном экране")
            return
        }
        val titles = snaps.mapIndexed { i, s ->
            val lines = s.lineSequence().take(2).toList()
            "${i + 1}. " + lines.getOrElse(0) { "" }.removePrefix("# SiteLimit снимок · ") + "\n" +
                lines.getOrElse(1) { "" }.removePrefix("# Сейчас: ").take(90)
        }
        AlertDialog.Builder(this)
            .setTitle("Снимки (новые сверху)")
            .setItems(titles.toTypedArray()) { _, i -> textDialog("Снимок ${i + 1}", snaps[i]) }
            .setPositiveButton("Копировать все") { _, _ -> copy(snaps.joinToString("\n\n")) }
            .setNeutralButton("Очистить") { _, _ ->
                CtStore.clearSnapshots(this)
                refresh()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showRules() {
        val custom = CtStore.customRulesText(this)
        val text = custom ?: CtStore.builtinRulesText(this)
        textDialog(if (custom != null) "Свои правила" else "Встроенные правила", text)
    }

    private fun editCustomRules() {
        val input = EditText(this).apply {
            hint = "вставьте JSON целиком"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            maxLines = 14
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setText(CtStore.customRulesText(this@DebugActivity) ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Свои правила (JSON)")
            .setMessage("Заменяют встроенные, пока не нажмёте «Вернуть встроенные». Проверяются перед сохранением.")
            .setView(input)
            .setPositiveButton("Проверить и сохранить") { _, _ ->
                val json = input.text.toString()
                try {
                    val book = RuleBook.parse(json)
                    // В упрямом режиме правила распознавания не меняются: так можно было бы обойти лимиты.
                    if (CtGuard.refuse(this, weaker = true)) return@setPositiveButton
                    CtStore.setCustomRules(this, MiniJson.write(MiniJson.parse(json), pretty = true))
                    toast("Сохранено: ${book.services.size} сервиса(ов), правил: ${book.services.sumOf { it.screens.size }}")
                    refresh()
                } catch (e: Exception) {
                    AlertDialog.Builder(this).setTitle("Ошибка в правилах").setMessage(e.message ?: e.toString())
                        .setPositiveButton("Понятно", null).show()
                }
            }
            .setNeutralButton("Вернуть встроенные") { _, _ ->
                if (CtGuard.refuse(this, weaker = true)) return@setNeutralButton
                CtStore.setCustomRules(this, null)
                toast("Встроенные правила")
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
