package io.github.jinedl.sitelimit.ct

/** Короткие строки «остаток» для главного экрана: общие лимиты, лимиты по минутам, закрытые типы. */
object Summary {

    fun remaining(rules: ModeRules, book: UsageBook, date: String, services: List<Pair<String, String>>): List<String> {
        val out = ArrayList<String>()
        for (g in rules.groups) {
            val used = book.groupSeconds(date, g.types) / 60
            out.add(
                if (g.minutes == 0) "${g.title()} (все сервисы): закрыто"
                else if (used >= g.minutes) "${g.title()} (все сервисы): ${g.minutes} из ${g.minutes} мин — исчерпан"
                else "${g.title()} (все сервисы): осталось ${g.minutes - used} из ${g.minutes} мин"
            )
        }
        for ((id, title) in services) {
            val closed = ArrayList<String>()
            for (t in ContentType.LIMITED) {
                val l = rules.limit(id, t)
                if (l.isEmpty) continue
                if (l.isClosed) {
                    closed.add(t.short)
                    continue
                }
                val k = Key(id, t)
                val parts = ArrayList<String>()
                l.minutes?.let { m ->
                    val used = book.seconds(date, k) / 60
                    parts.add(if (used >= m) "$m из $m мин — исчерпан" else "осталось ${m - used} из $m мин")
                }
                l.opens?.let { o ->
                    val used = book.opens(date, k)
                    parts.add("открытий ${minOf(used, o)} из $o")
                }
                out.add("$title · ${t.short}: ${parts.joinToString(", ")}")
            }
            if (closed.isNotEmpty()) out.add("$title: закрыто — ${closed.joinToString(", ")}")
        }
        if (out.isEmpty()) out.add("ограничений нет")
        return out
    }

    /** «сегодня 12 мин, 3 откр.» для строки типа. */
    fun today(book: UsageBook, date: String, k: Key): String {
        val s = book.seconds(date, k)
        val f = book.freeSeconds(date, k)
        val o = book.opens(date, k)
        val parts = ArrayList<String>()
        parts.add("сегодня ${s / 60} мин")
        if (o > 0) parts.add("$o откр.")
        if (f >= 60) parts.add("+${f / 60} мин по исключениям")
        return parts.joinToString(", ")
    }
}
