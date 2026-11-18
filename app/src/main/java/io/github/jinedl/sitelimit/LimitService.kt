package io.github.jinedl.sitelimit

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.jinedl.sitelimit.ct.ContentEngine
import io.github.jinedl.sitelimit.ct.CtStore
import io.github.jinedl.sitelimit.ct.ExKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Раз в секунду смотрит, открыт ли Яндекс Браузер, читает адресную строку
 * и добавляет секунду к сайту с лимитом. При превышении — выход на главный экран.
 *
 * Если включён выключатель «Типы контента (бета)», дополнительно работает ContentEngine:
 * экраны Telegram, Max, VK, YouTube. Выключатель выключен — всё как в v0.9.
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
        private const val FAST_TICK_MS = 250L
        private const val DUMP_EVERY_MS = 3000L
        // Только от двойного запуска окна: повторная блокировка и так не раньше чем через 1,5 с.
        private const val MESSAGE_EVERY_MS = 1000L
        /** Сколько даётся на переход с исчерпанного сайта, прежде чем браузер свернётся. */
        private const val GRACE_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentHost: String? = null
    private var lastTick = 0L
    private var pendingMs = 0L
    private var lastDump = 0L
    private var lastMessage = 0L
    private var lastDiag = 0L
    /** Исчерпанный сайт на экране и с какого момента он там (для 1,5 секунды на переход). */
    private var overSite: String? = null
    private var overSince = 0L

    /** Типы контента (бета). Создаётся при подключении службы, работает только при включённом выключателе. */
    private var ct: ContentEngine? = null
    /** Какие пакеты присылают события. Сначала — как в accessibility_service_config.xml (только браузеры). */
    private var appliedPackages: Set<String> = BROWSERS
    private var ctFast = false
    private var lastCtVersion = -1

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            // Пока идут секунды на переход — проверяем чаще, чтобы свернуть точно через 1,5 с.
            handler.postDelayed(this, if (overSite != null || ctFast) FAST_TICK_MS else TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.cleanupOldUsage(this)
        ct = try {
            ContentEngine(this)
        } catch (e: Exception) {
            Log.w(TAG, "ContentEngine failed", e)
            null
        }
        syncPackages(CtStore.isEnabled(this))
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Для сайтов события не нужны: всё по таймеру в onTick(). Для типов контента событие только
        // помечает «экран мог измениться» — разбор окна в onTick и не чаще раза в 0,4 с.
        if (event != null && appliedPackages.size > BROWSERS.size) {
            try {
                ct?.onEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "event failed", e)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ct?.destroy()
        super.onDestroy()
    }

    /**
     * Какие приложения присылают события службе: браузеры (как в v0.9) и, при включённой бете,
     * пакеты сервисов из файла правил и настроек. Читать активное окно это не мешает (kb/02).
     */
    private fun syncPackages(ctOn: Boolean) {
        val wanted = LinkedHashSet<String>(BROWSERS)
        if (ctOn) ct?.let { wanted.addAll(it.packages()) }
        if (wanted == appliedPackages) return
        try {
            val info = serviceInfo ?: return
            info.packageNames = wanted.toTypedArray()
            serviceInfo = info
            appliedPackages = wanted
        } catch (e: Exception) {
            Log.w(TAG, "setServiceInfo failed", e)
        }
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

        // Типы контента (бета): выключатель выключен — этот блок не работает, всё как в v0.9.
        val ctOn = CtStore.isEnabled(this)
        ctFast = false
        if (ctOn) {
            if (CtStore.configVersion(this) != lastCtVersion) {
                lastCtVersion = CtStore.configVersion(this)
                syncPackages(true)
            }
            try {
                ctFast = ct?.tick(root, pkg, screenOn, now, elapsed) ?: false
            } catch (e: Exception) {
                Log.w(TAG, "content tick failed", e)
            }
        } else if (appliedPackages.size > BROWSERS.size) {
            syncPackages(false)
            ct?.flush()
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
        var site = host?.let { Store.matchLimit(it, limits.keys) }
        var limitMin = site?.let { limits[it] }
        var deny = false
        // Исключения сайтов (только при включённой бете): «всегда можно» снимает лимит,
        // «всегда нельзя» — как лимит 0 минут (те же 1,5 с на переход).
        if (ctOn && host != null) {
            val ex = ct?.siteException(host)
            when (ex?.kind) {
                ExKind.ALLOW -> {
                    site = null
                    limitMin = null
                }
                ExKind.DENY -> {
                    site = ex.key
                    limitMin = 0
                    deny = true
                }
                else -> Unit
            }
        }
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
        // Лимит исчерпан: даём 1,5 секунды, чтобы перейти на другую вкладку.
        if (overSite != site) {
            overSite = site
            overSince = now
        }
        if (now - overSince >= GRACE_MS) {
            overSite = null
            block(site, limitMin, now, deny)
        }
    }

    private fun block(site: String, limitMin: Int, now: Long, deny: Boolean = false) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - lastMessage >= MESSAGE_EVERY_MS) {
            lastMessage = now
            // Всплывающие сообщения (Toast) из фона Android не показывает, если у приложения
            // выключены уведомления, поэтому — своё окно поверх главного экрана.
            // Чуть позже, чтобы его не перекрыл запуск главного экрана.
            handler.postDelayed({
                try {
                    startActivity(
                        if (deny) BlockedActivity.contentIntent(this, "$site — всегда нельзя (исключение).\nУбрать можно в SiteLimit → Исключения.")
                        else BlockedActivity.intent(this, site, limitMin)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "BlockedActivity failed", e)
                }
            }, 400)
        }
    }
}
