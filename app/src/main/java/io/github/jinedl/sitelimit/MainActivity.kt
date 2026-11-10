package io.github.jinedl.sitelimit

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
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

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val domain = Store.normalizeDomain(inputDomain.text)
            val minutes = inputMinutes.text.toString().toIntOrNull()
            when {
                domain == null -> toast("Не похоже на адрес сайта, пример: youtube.com")
                minutes == null || minutes !in 1..1440 -> toast("Лимит — от 1 до 1440 минут")
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
        handler.post(refresher)
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
        statusText.text =
            if (on) "Служба: включена ✅" else "Служба: выключена ❌ — включите её в Специальных возможностях"

        listContainer.removeAllViews()
        val limits = Store.limits(this)
        if (limits.isEmpty()) {
            listContainer.addView(row("Пока нет сайтов"))
            return
        }
        for ((domain, limit) in limits) {
            val usedMin = Store.usedSeconds(this, domain) / 60
            val over = usedMin >= limit
            val tv = row("$domain — $usedMin из $limit мин" + if (over) "  (исчерпан)" else "")
            tv.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Удалить $domain?")
                    .setPositiveButton("Удалить") { _, _ ->
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
