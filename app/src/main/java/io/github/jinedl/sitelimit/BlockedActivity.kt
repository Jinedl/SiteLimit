package io.github.jinedl.sitelimit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Окно «Лимит исчерпан», которое служба показывает поверх главного экрана. */
class BlockedActivity : Activity() {

    companion object {
        private const val EXTRA_SITE = "site"
        private const val EXTRA_LIMIT = "limit"

        fun intent(c: Context, site: String, limitMin: Int): Intent =
            Intent(c, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_SITE, site)
                .putExtra(EXTRA_LIMIT, limitMin)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val site = intent.getStringExtra(EXTRA_SITE) ?: "сайт"
        val limit = intent.getIntExtra(EXTRA_LIMIT, 0)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = "Лимит исчерпан"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        val body = TextView(this).apply {
            text = "$site — $limit мин на сегодня уже потрачено.\nСнова можно будет завтра."
            textSize = 17f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val ok = Button(this).apply {
            text = "Понятно"
            setOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title)
            addView(body)
            addView(ok)
        })
    }
}
