package io.github.jinedl.sitelimit

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Раз в секунду смотрит, открыт ли Яндекс Браузер, читает адресную строку
 * и добавляет секунду к сайту с лимитом. При превышении — выход на главный экран.
 */
class LimitService : AccessibilityService() {

    companion object {
        /**
         * Все браузеры Яндекса: Яндекс Браузер (обычный, Lite, бета, альфа)
         * и приложение «Яндекс — с Алисой» (у него свой встроенный браузер).
         * Тот же список — в res/xml/accessibility_service_config.xml.
         */
        val BROWSERS = setOf(
            "com.yandex.browser",
            "com.yandex.browser.lite",
            "com.yandex.browser.beta",
            "com.yandex.browser.alpha",
            "ru.yandex.searchplugin",
            "ru.yandex.searchplugin.beta",
            "com.yandex.searchapp",
            "com.yandex.searchapp.beta",
        )
        private const val DIAG_EVERY_MS = 2000L
        private const val TAG = "SiteLimit"
        private const val TICK_MS = 1000L
        private const val DUMP_EVERY_MS = 3000L
        private const val TOAST_EVERY_MS = 5000L
        /** Сколько даётся на переход с исчерпанного сайта, прежде чем браузер свернётся. */
        private const val GRACE_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentHost: String? = null
    private var lastTick = 0L
    private var pendingMs = 0L
    private var lastDump = 0L
    private var lastToast = 0L
    private var lastDiag = 0L
    /** Исчерпанный сайт на экране и с какого момента он там (для 3 секунд на переход). */
    private var overSite: String? = null
    private var overSince = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.cleanupOldUsage(this)
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События не нужны: всё делается по таймеру в onTick().
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun setCurrent(host: String?) {
        if (host != currentHost) {
            currentHost = host
            Store.setCurrentSite(this, host)
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        // Если таймер «проспал» (например, телефон засыпал), не засчитываем больше 2 секунд.
        val elapsed = (now - lastTick).coerceIn(0L, 2 * TICK_MS)
        lastTick = now

        val pm = getSystemService(PowerManager::class.java)
        val screenOn = pm == null || pm.isInteractive
        val root = if (screenOn) rootInActiveWindow else null
        val pkg = root?.packageName?.toString()

        // Диагностика для экрана приложения: служба жива и что она видит.
        if (now - lastDiag >= DIAG_EVERY_MS) {
            lastDiag = now
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val window = when {
                !screenOn -> "экран выключен"
                pkg == null -> "окно недоступно"
                else -> pkg
            }
            Store.setDiag(this, "$time · активное окно: $window")
            if (pkg != null && pkg != packageName) Store.setDiagOther(this, "$time · $pkg")
        }

        // Экран выключен — время не считается.
        if (!screenOn) {
            pendingMs = 0
            overSite = null
            return
        }

        // Считаем только когда на экране сам браузер.
        if (root == null || pkg !in BROWSERS) {
            overSite = null
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val customId = Store.customId(this)
        when (val r = AddressBar.find(root, screenHeight, customId)) {
            is AddressBar.Result.Domain -> setCurrent(r.host)   // другой домен сбрасывает прежний сайт
            is AddressBar.Result.NotDomain -> setCurrent(null)
            AddressBar.Result.None -> Unit                      // строка скрыта — сайт прежний
        }

        if (Store.isDebug(this) && now - lastDump >= DUMP_EVERY_MS) {
            lastDump = now
            Store.setDebugDump(this, AddressBar.dump(root, screenHeight, customId))
        }

        val host = currentHost
        val limits = Store.limits(this)
        val site = host?.let { Store.matchLimit(it, limits.keys) }
        val limitMin = site?.let { limits[it] }
        if (site == null || limitMin == null) {
            overSite = null
            return
        }

        pendingMs += elapsed
        val add = (pendingMs / 1000).toInt()
        pendingMs %= 1000
        val used = if (add > 0) Store.addSeconds(this, site, add) else Store.usedSeconds(this, site)

        if (used < limitMin * 60) {
            overSite = null
            return
        }
        // Лимит исчерпан: даём 3 секунды, чтобы перейти на другую вкладку.
        if (overSite != site) {
            overSite = site
            overSince = now
        }
        if (now - overSince >= GRACE_MS) {
            overSite = null
            block(site, limitMin, now)
        }
    }

    private fun block(site: String, limitMin: Int, now: Long) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - lastToast >= TOAST_EVERY_MS) {
            lastToast = now
            // Чуть позже, чтобы сообщение не потерялось в анимации выхода на главный экран.
            handler.postDelayed({
                Toast.makeText(
                    this,
                    "SiteLimit: $site — лимит $limitMin мин на сегодня исчерпан",
                    Toast.LENGTH_LONG
                ).show()
            }, 500)
        }
    }
}
