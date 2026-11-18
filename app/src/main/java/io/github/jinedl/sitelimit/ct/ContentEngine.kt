package io.github.jinedl.sitelimit.ct

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jinedl.sitelimit.BlockedActivity
import io.github.jinedl.sitelimit.Store
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale

/** Общие данные службы и экранов (один процесс): текущие счётчики и решение. */
object CtLive {
    /** Счётчики работающей службы; null — служба не запущена (экраны читают сохранённые). */
    var book: UsageBook? = null
    var last: Verdict? = null
    var lastDetection: Detection? = null
}

/**
 * Типы контента в службе доступности: распознаёт экран сервиса (с ограничением частоты),
 * считает время и открытия, при исчерпании — «Назад», затем при необходимости «Домой»
 * и окно «Лимит исчерпан». Работает, только когда включён выключатель «Типы контента (бета)».
 */
class ContentEngine(private val svc: AccessibilityService) {

    companion object {
        private const val TAG = "SiteLimit"
        /** Не чаще: распознавание по событию окна. */
        private const val CLASSIFY_MIN_MS = 400L
        /** Без событий — переспросить экран раз в 2 с (на случай пропущенного события). */
        private const val CLASSIFY_IDLE_MS = 2000L
        /** Через сколько после «Назад» проверить, помогло ли. */
        private const val BACK_CHECK_MS = 700L
        private const val FLUSH_MS = 15_000L
        private const val UI_MS = 2000L
        private const val SNAP_MIN_MS = 1000L
        private const val SNAP_EVERY_MS = 3000L
        private const val MESSAGE_EVERY_MS = 1000L
    }

    private class Cfg(
        val ver: Int,
        val rules: CtStore.LoadedRules,
        val pkgToSvc: Map<String, ServiceDef>,
        val modes: Map<String, ModeRules>,
        val exceptions: List<ExRule>,
        val schedule: List<ScheduleEntry>,
        val manual: ManualMode?,
    )

    private class PendingBack(val at: Long, val verdict: Verdict, val det: Detection)

    private val handler = Handler(Looper.getMainLooper())
    private val core = CtCore(UsageBook())
    private var cfg: Cfg? = null
    private var usageLoaded = false
    private var dirty = true
    private var lastClassifyAt = 0L
    private var lastPkg: String? = null
    private var lastDet: Detection? = null
    private var lastScreen: Screen? = null
    private var pendingBack: PendingBack? = null
    private var lastFlush = 0L
    private var lastUi = 0L
    private var lastSnapAt = 0L
    private var lastSnapSig: String? = null
    private var lastMessage = 0L
    private var lastDate: String? = null
    private var lastTitleKey: String? = null

    init {
        CtLive.book = core.book
    }

    /** Пакеты всех сервисов (для фильтра событий службы). */
    fun packages(): Set<String> = config().pkgToSvc.keys

    fun configVersion(): Int = CtStore.configVersion(svc)

    /** Событие окна: только отметка «экран мог измениться», разбор — в tick не чаще CLASSIFY_MIN_MS. */
    fun onEvent(e: AccessibilityEvent) {
        val p = e.packageName?.toString() ?: return
        if (p in config().pkgToSvc) dirty = true
    }

    fun siteException(host: String): ExRule? = Exceptions.forSite(config().exceptions, host)

    private fun config(): Cfg {
        val ver = CtStore.configVersion(svc)
        cfg?.let { if (it.ver == ver) return it }
        val rules = CtStore.loadRules(svc)
        val pkgToSvc = LinkedHashMap<String, ServiceDef>()
        for (s in rules.book.services) {
            for (p in CtStore.packagesFor(svc, s)) if (p !in pkgToSvc) pkgToSvc[p] = s
        }
        val ids = rules.book.services.map { it.id }
        val loaded = Cfg(
            ver = ver,
            rules = rules,
            pkgToSvc = pkgToSvc,
            modes = Modes.ALL.associateWith { CtStore.modeRules(svc, it, ids) },
            exceptions = CtStore.exceptions(svc),
            schedule = CtStore.schedule(svc),
            manual = CtStore.manual(svc),
        )
        cfg = loaded
        dirty = true
        return loaded
    }

    /**
     * Один тик службы (раз в 1 с, во время перехода — чаще). Возвращает true, если нужны частые тики.
     */
    fun tick(root: AccessibilityNodeInfo?, pkg: String?, screenOn: Boolean, now: Long, elapsedMs: Long): Boolean {
        val c = config()
        if (!usageLoaded) {
            core.book.load(CtStore.loadUsage(svc))
            usageLoaded = true
        }
        val today = LocalDate.now()
        val date = today.toString()
        if (date != lastDate) {
            core.book.cleanup(today.minusDays(UsageBook.KEEP_DAYS).toString())
            lastDate = date
        }

        val sd = if (screenOn && root != null && pkg != null) c.pkgToSvc[pkg] else null
        val det = if (sd != null && root != null && pkg != null) classify(root, pkg, sd, now) else null
        if (det == null) {
            lastPkg = pkg
            lastScreen = null
        }

        val active = Scheduler.resolve(ZonedDateTime.now(), c.schedule, c.manual)
        val rules = c.modes[active.mode] ?: ModeRules()
        val verdict = core.tick(date, det, now, elapsedMs, CtCore.Config(rules, Modes.title(active.mode), c.exceptions))
        CtLive.last = core.last
        CtLive.lastDetection = det ?: CtLive.lastDetection

        handleBlock(det, verdict, now, c)

        if (det != null) {
            remember(det, now, c)
            snapshot(det, now, c)
        }
        if (now - lastFlush >= FLUSH_MS || !screenOn) {
            lastFlush = now
            CtStore.saveUsage(svc, core.book)
        }
        return core.inGrace || pendingBack != null
    }

    fun flush() {
        CtStore.saveUsage(svc, core.book)
    }

    fun destroy() {
        flush()
        handler.removeCallbacksAndMessages(null)
        if (CtLive.book === core.book) CtLive.book = null
    }

    private fun classify(root: AccessibilityNodeInfo, pkg: String, sd: ServiceDef, now: Long): Detection {
        val prev = lastDet
        val need = dirty || pkg != lastPkg || prev == null || prev.service != sd.id || now - lastClassifyAt >= CLASSIFY_IDLE_MS
        if (!need || (now - lastClassifyAt < CLASSIFY_MIN_MS && prev != null && prev.service == sd.id)) {
            return prev ?: Detection(sd.id, ContentType.UNKNOWN)
        }
        lastClassifyAt = now
        lastPkg = pkg
        dirty = false
        return try {
            val dm = svc.resources.displayMetrics
            val screen = Screen(pkg, dm.widthPixels, dm.heightPixels, NodeReader.read(root))
            lastScreen = screen
            Classifier.classify(sd, screen).also { lastDet = it }
        } catch (e: Exception) {
            Log.w(TAG, "classify failed", e)
            Detection(sd.id, ContentType.UNKNOWN).also { lastDet = it }
        }
    }

    /** «Назад», через 0,7 с проверка: если экран всё ещё под запретом — «Домой». Затем окно «Лимит исчерпан». */
    private fun handleBlock(det: Detection?, verdict: Verdict?, now: Long, c: Cfg) {
        val pb = pendingBack
        if (pb == null) {
            if (verdict == null || det == null) return
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            pendingBack = PendingBack(now, verdict, det)
            dirty = true
            return
        }
        if (now - pb.at < BACK_CHECK_MS) return
        pendingBack = null
        val stillBlocked = det != null && det.service == pb.det.service && core.last?.blocked == true
        if (stillBlocked) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val svcTitle = c.rules.book.service(pb.det.service)?.title ?: pb.det.service
        val what = "$svcTitle — ${pb.verdict.type.title}" + (pb.det.title?.let { " («$it»)" } ?: "")
        CtStore.setLastBlock(svc, "${time()} · $what · ${pb.verdict.reason} · " +
            (if (stillBlocked) "Назад не помогло → Домой" else "помогло «Назад»") + " · правило ${pb.det.rule ?: "—"}")
        if (now - lastMessage < MESSAGE_EVERY_MS) return
        lastMessage = now
        val tail = when (pb.verdict.kind) {
            BlockKind.DENY -> "Убрать можно в SiteLimit → Исключения."
            BlockKind.CLOSED -> "Снова можно будет в другом режиме."
            else -> "Снова можно будет завтра."
        }
        val body = "$what:\n${pb.verdict.reason}.\n$tail"
        handler.postDelayed({
            try {
                svc.startActivity(BlockedActivity.contentIntent(svc, body))
            } catch (e: Exception) {
                Log.w(TAG, "BlockedActivity failed", e)
            }
        }, if (stillBlocked) 400 else 100)
    }

    /** Строка «Сейчас» для отладки и недавние названия для исключений. */
    private fun remember(det: Detection, now: Long, c: Cfg) {
        val titleKey = det.title?.let { "${det.service}|${Exceptions.normalize(it)}" }
        if (titleKey != null && titleKey != lastTitleKey) {
            lastTitleKey = titleKey
            CtStore.addRecentTitle(svc, CtStore.Recent(det.service, det.title!!, det.type))
        }
        if (now - lastUi < UI_MS) return
        lastUi = now
        CtStore.setNow(svc, "${time()} · ${describe(det, c)}")
    }

    private fun describe(det: Detection, c: Cfg): String {
        val title = c.rules.book.service(det.service)?.title ?: det.service
        val v = core.last
        val type = v?.type ?: det.type
        val extra = when {
            v == null -> ""
            v.blocked -> " · ⛔ ${v.reason}"
            v.free -> " · исключение «всегда можно»"
            v.remainingSec != null -> " · осталось ${(v.remainingSec + 59) / 60} мин"
            else -> ""
        }
        return "$title — ${type.title}" + (det.title?.let { " — «$it»" } ?: "") +
            " · правило ${det.rule ?: "нет"}" + extra
    }

    private fun snapshot(det: Detection, now: Long, c: Cfg) {
        if (!Store.isDebug(svc)) return
        val screen = lastScreen ?: return
        val sig = "${screen.pkg}|${det.type}|${det.rule}|${screen.nodes.size / 5}"
        val same = sig == lastSnapSig
        if (same && now - lastSnapAt < SNAP_EVERY_MS) return
        if (!same && now - lastSnapAt < SNAP_MIN_MS) return
        lastSnapAt = now
        lastSnapSig = sig
        val book = c.rules.book
        val header = listOf(
            "SiteLimit снимок · ${time()} · ${screen.pkg} · ${screen.width}x${screen.height}",
            "Сейчас: ${describe(det, c)}",
            "Правила: v${book.version} от ${book.updated}" + if (c.rules.custom) " (свои)" else " (встроенные)",
            "expect: ${det.service} ${det.type.id}",
        )
        val text = SnapshotText.format(screen, header, book.allKeywords, CtStore.isMask(svc))
        CtStore.pushSnapshot(svc, text, replaceFirst = same)
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
