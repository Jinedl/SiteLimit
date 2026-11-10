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

/** Отладка: открывается долгим нажатием на строку версии внизу главного экрана. */
class DebugActivity : Activity() {

    private lateinit var diagText: TextView
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
            text = "Сохранять снимок окна браузера (раз в 3 с)"
            isChecked = Store.isDebug(this@DebugActivity)
            setOnCheckedChangeListener { _, on -> Store.setDebug(this@DebugActivity, on) }
        }
        val btnDump = Button(this).apply {
            text = "Показать снимок"
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
    }

    private fun showDump() {
        val dump = Store.debugDump(this)
            ?: ("Снимка пока нет.\n\n1. Включите галочку выше.\n2. Откройте браузер на любом сайте.\n" +
                "3. Подождите 3–5 секунд и вернитесь сюда.\n\n" +
                "Галочка отладки: ${if (Store.isDebug(this)) "включена" else "выключена"}")
        val pad = (12 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = dump
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Снимок окна браузера")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("SiteLimit", dump))
                toast("Скопировано")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
