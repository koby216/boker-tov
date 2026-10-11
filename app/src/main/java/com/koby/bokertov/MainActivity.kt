package com.koby.bokertov

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** הדף הראשי: הדמות במרכז, משפט אחד, וכפתור אחד. */
class MainActivity : AppCompatActivity() {

    private lateinit var character: CharacterView
    private lateinit var caption: TextView
    private lateinit var mainButton: Button
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateScreen()
            ticker.postDelayed(this, 300)
        }
    }

    private val permissions: Array<String> = Perms.all()

    private val askPermissions =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            // אם קיבלנו את המיקרופון, מתחילים להקשיב מיד
            if (hasMic() && !ListeningService.running) startListening()
            updateScreen()
        }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** מתחיל להקשיב ואומר "אני מקשיבה". אם אין עדיין הרשאה, מבקש אותה. */
    private fun autoStart() {
        if (hasMic()) startListening() else askPermissions.launch(permissions)
    }

    private fun startListening() {
        ListeningService.start(this, announce = true)
        mainButton.postDelayed({ updateScreen() }, 700)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        buildUi()
        if (!ListeningService.running) Earcon.restoreIfStuck(this)
        if (savedInstanceState == null) routeIntent(intent)
    }

    /** מחליטה מה לעשות לפי הסיבה שהמסך נפתח: כיבוי, הודעה נכנסת, או פתיחה רגילה. */
    private fun routeIntent(i: Intent) {
        when {
            i.getBooleanExtra(EXTRA_IDLE, false) -> updateScreen()
            i.hasExtra(EXTRA_SENDER) && hasMic() -> {
                ListeningService.start(
                    this, sender = i.getStringExtra(EXTRA_SENDER), text = i.getStringExtra(EXTRA_TEXT)
                )
                mainButton.postDelayed({ updateScreen() }, 700)
            }
            else -> autoStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // כשהמסך מופיע אחרי שהיא נכבתה, לא מפעילים מחדש לבד: מחכים ללחיצה על הפעל
        routeIntent(intent)
    }

    companion object {
        @Volatile
        var instance: MainActivity? = null
        const val EXTRA_IDLE = "idle"
        const val EXTRA_SENDER = "msg_sender"
        const val EXTRA_TEXT = "msg_text"
    }

    override fun onResume() {
        super.onResume()
        updateScreen()
        ticker.post(tick)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFBEF"))
            setPadding(dp(20), dp(16), dp(20), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // שורה עליונה: שם וגלגל שיניים
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "בוקר טוב"
            textSize = 30f
            setTextColor(Color.parseColor("#B8860B"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = "⚙"
            textSize = 36f
            setTextColor(Color.parseColor("#555555"))
            contentDescription = "הגדרות"
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // הדמות במרכז
        character = CharacterView(this)
        root.addView(character, LinearLayout.LayoutParams(-1, 0, 1f))

        // משפט אחד
        caption = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.parseColor("#222222"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minLines = 2
        }
        root.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        // כפתור אחד
        mainButton = Button(this).apply {
            textSize = 30f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener { toggle() }
        }
        root.addView(mainButton, LinearLayout.LayoutParams(-1, dp(96)))

        setContentView(root)
    }

    private fun updateScreen() {
        val on = ListeningService.running
        if (!on) UiState.face = Face.OFF
        character.setFace(if (on) UiState.face else Face.OFF)
        caption.text = if (!on) "כבויה. לחץ הפעל" else UiState.caption.ifBlank { "אני מקשיבה" }
        mainButton.text = if (on) "כבה" else "הפעל"
        mainButton.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (on) "#C62828" else "#2E7D32"))
    }

    private fun toggle() {
        if (ListeningService.running) {
            ListeningService.stop(this)
            mainButton.postDelayed({ updateScreen() }, 400)
            return
        }
        if (!hasMic()) {
            askPermissions.launch(permissions)
            return
        }
        ListeningService.start(this, announce = true)
        mainButton.postDelayed({ updateScreen() }, 600)
    }
}
