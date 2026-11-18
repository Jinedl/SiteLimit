package io.github.jinedl.sitelimit

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.jinedl.sitelimit.ct.CtGuard
import io.github.jinedl.sitelimit.ct.CtStore
import io.github.jinedl.sitelimit.ct.StrictGuard
import io.github.jinedl.sitelimit.ct.Summary
import io.github.jinedl.sitelimit.ct.Ui

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var ctSwitch: CheckBox
    private lateinit var ctSection: LinearLayout
    private lateinit var ctMode: TextView
    private lateinit var ctNow: TextView
    private lateinit var ctLeft: TextView
    private var syncing = false
    /** Что сейчас нарисовано в списке сайтов: список пересобирается только при изменении. */
    private var listSig: String? = null
    private var ctBook: io.github.jinedl.sitelimit.ct.RuleBook? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        listContainer = findViewById(R.id.listContainer)
        val inputDomain = findViewById<EditText>(R.id.inputDomain)
        val inputMinutes = findViewById<EditText>(R.id.inputMinutes)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupContentTypes()

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val domain = Store.normalizeDomain(inputDomain.text)
            val minutes = inputMinutes.text.toString().toIntOrNull()
            when {
                domain == null -> toast("Не похоже на адрес сайта, пример: youtube.com")
                minutes == null || minutes !in 1..1440 -> toast("Лимит — от 1 до 1440 минут")
                // Упрямый режим (только при включённой бете): лимит сайта нельзя увеличить.
                CtGuard.refuse(this, StrictGuard.weakerSiteLimit(Store.limits(this)[domain], minutes)) -> Unit
                else -> {
                    Store.setLimit(this, domain, minutes)
                    inputDomain.text.clear()
                    inputMinutes.text.clear()
                    toast("$domain: $minutes мин в день")
                    refresh()
                }
            }
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.versionText).apply {
            text = "SiteLimit $version"
            // Скрытый вход в отладку: долгое нажатие на строку версии.
            setOnLongClickListener {
                startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ctBook = null
        listSig = null
        handler.post(refresher)
    }

    /** Текст меняется только если другой: лишние перерисовки дёргают прокрутку. */
    private fun TextView.show(s: String) {
        if (text.toString() != s) text = s
    }

    /** Выключатель беты и короткая сводка: режим, что сейчас на экране, остаток. */
    private fun setupContentTypes() {
        ctSwitch = findViewById(R.id.ctSwitch)
        ctSection = findViewById(R.id.ctSection)
        ctMode = Ui.text(this, "", 16f, bold = true)
        ctNow = Ui.text(this, "", 14f)
        ctLeft = Ui.text(this, "", 14f)
        ctSection.addView(ctMode)
        ctSection.addView(ctNow)
        ctSection.addView(ctLeft)
        ctSection.addView(Ui.button(this, "Лимиты по типам") { startActivity(Intent(this, RulesActivity::class.java)) })
        ctSection.addView(Ui.button(this, "Исключения") { startActivity(Intent(this, ExceptionsActivity::class.java)) })
        ctSection.addView(Ui.button(this, "Режимы, расписание, пресеты") { startActivity(Intent(this, ModesActivity::class.java)) })
        ctSwitch.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (!on) {
                if (CtGuard.refuse(this, weaker = true)) {
                    setSwitch(true)
                    return@setOnCheckedChangeListener
                }
                CtStore.setEnabled(this, false)
                refresh()
                return@setOnCheckedChangeListener
            }
            setSwitch(false)
            AlertDialog.Builder(this)
                .setTitle("Типы контента (бета)")
                .setMessage("Служба начнёт смотреть экраны Telegram, Max, VK и YouTube: какие элементы на экране " +
                    "(канал, чат, лента, ролик), чтобы считать время по типам и закрывать то, на что исчерпан лимит.\n\n" +
                    "Тексты переписки не сохраняются; на телефоне хранятся только минуты, открытия, названия каналов и чатов " +
                    "для исключений и снимки отладки, если вы их включите. В интернет ничего не уходит.\n\n" +
                    "Выключите галочку — приложение работает как 0.9 (только сайты).")
                .setPositiveButton("Включить") { _, _ ->
                    CtStore.setEnabled(this, true)
                    setSwitch(true)
                    refresh()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun setSwitch(on: Boolean) {
        syncing = true
        ctSwitch.isChecked = on
        syncing = false
    }

    private fun refreshContentTypes() {
        val on = CtStore.isEnabled(this)
        if (ctSwitch.isChecked != on) setSwitch(on)
        val vis = if (on) View.VISIBLE else View.GONE
        if (ctSection.visibility != vis) ctSection.visibility = vis
        if (!on) return
        val active = CtGuard.active(this)
        ctMode.show("Режим: ${active.describe()}")
        ctNow.show("Последнее в сервисах: ${CtStore.now(this) ?: "—"}")
        val book = ctBook ?: CtStore.loadRules(this).book.also { ctBook = it }
        val ids = book.services.map { it.id }
        val rules = CtStore.modeRules(this, active.mode, ids)
        ctLeft.show("Остаток:\n" + Summary.remaining(rules, Ui.usage(this), Ui.today(), book.services.map { it.id to it.title })
            .joinToString("\n") { "• $it" })
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, LimitService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        val on = isServiceEnabled()
        statusText.show(
            if (on) "Служба: включена ✅" else "Служба: выключена ❌ — включите её в Специальных возможностях")
        refreshContentTypes()

        // Список сайтов пересобирается, только если что-то изменилось: раньше он пересоздавался
        // каждые 2 с, и нажатия и прокрутка на главном экране дёргались.
        val limits = Store.limits(this)
        val lines = limits.map { (domain, limit) ->
            val usedMin = Store.usedSeconds(this, domain) / 60
            "$domain — $usedMin из $limit мин" + if (usedMin >= limit) "  (исчерпан)" else ""
        }
        val sig = lines.joinToString("\n")
        if (sig == listSig) return
        listSig = sig
        listContainer.removeAllViews()
        if (limits.isEmpty()) {
            listContainer.addView(row("Пока нет сайтов"))
            return
        }
        for ((i, entry) in limits.entries.withIndex()) {
            val (domain, limit) = entry
            val tv = row(lines[i])
            tv.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Удалить $domain?")
                    .setPositiveButton("Удалить") { _, _ ->
                        if (CtGuard.refuse(this, StrictGuard.weakerSiteLimit(limit, null))) return@setPositiveButton
                        Store.removeLimit(this, domain)
                        refresh()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                true
            }
            listContainer.addView(tv)
        }
    }

    private fun row(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
