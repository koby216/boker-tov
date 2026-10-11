package com.koby.bokertov

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
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
    private lateinit var skipView: TextView
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
            updateScreen()
        }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** מתחיל להקשיב ואומר "אני מקשיבה". אם אין עדיין הרשאה, מבקש אותה. */
    private fun autoStart() {
        // אם חסר משהו, קודם המדריך הקולי (updateScreen מציג ואומר מה לעשות)
        if (!setupSkipped && nextStep() != null) { updateScreen(); return }
        if (hasMic()) startListening() else askPermissions.launch(permissions)
    }

    // ---------- מדריך קולי להתקנה ועדכון ----------

    private class Step(
        val id: Int,
        val done: () -> Boolean,
        val say: String,
        val caption: String,
        val button: String,
        val tip: String?,
        val action: () -> Unit
    )

    private val restrictedTip =
        "אם המתג אפור ואי אפשר ללחוץ עליו: צא להגדרות של הטלפון, בחר אפליקציות, בחר בוקר טוב, " +
            "לחץ על שלוש הנקודות למעלה, ובחר אפשר הגדרות מוגבלות. אחר כך חזור ונסה שוב."

    private fun missingPermissions() =
        permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun accessibilityOn(): Boolean = Perms.accessibilityOn(this)

    private fun notificationsOn() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun batteryOk() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun steps(): List<Step> = listOf(
        Step(
            1, { missingPermissions().isEmpty() },
            "כדי להתחיל, צריך לאשר הרשאות. לחץ על הכפתור הכחול, ובכל חלון שמופיע לחץ אפשר.",
            "צריך לאשר הרשאות", "אשר הרשאות", null
        ) { askPermissions.launch(permissions) },
        Step(
            2, { Settings.canDrawOverlays(this) },
            "עכשיו צריך לאשר לי לפתוח אפליקציות. לחץ על הכפתור הכחול, בחר בוקר טוב, והדלק את המתג.",
            "אישור לפתוח אפליקציות", "פתח הגדרות", null
        ) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
        Step(
            3, { accessibilityOn() },
            "כדי שאוכל ללחוץ במקומך, צריך להפעיל נגישות. לחץ על הכפתור הכחול, בחר בוקר טוב, הדלק את המתג, ולחץ אישור.",
            "צריך להפעיל נגישות", "פתח נגישות", restrictedTip
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        Step(
            4, { notificationsOn() },
            "כדי שאקריא הודעות וואטסאפ, צריך לאשר גישה להתראות. לחץ על הכפתור הכחול, בחר בוקר טוב, והדלק את המתג.",
            "צריך לאשר גישה להתראות", "פתח התראות", restrictedTip
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        Step(
            5, { batteryOk() },
            "כדי שהטלפון לא יכבה אותי ברקע, לחץ על הכפתור הכחול ובחר אפשר.",
            "שלא תיפסק ברקע", "אשר", null
        ) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        },
        Step(
            6, { Prefs.carAddress(this).isNotEmpty() || Prefs.carDismissed(this) },
            "אם אתה משתמש בי ברכב, צריך לבחור את הרכב שלך. לחץ על הכפתור הכחול ובחר את הרכב מהרשימה. אם אתה לא משתמש בי ברכב, לחץ דלג.",
            "בחירת הרכב", "בחר רכב", null
        ) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_CHOOSE_CAR, true))
        }
    )

    private fun nextStep(): Step? = steps().firstOrNull { !it.done() }

    private var setupSkipped = false
    private var currentStepId = -1
    private var setupSpoken = -1
    private var inSetup = false
    private var leftForStep = -1
    private var guideUntil = 0L

    private fun showSetup(step: Step) {
        inSetup = true
        currentStepId = step.id
        if (setupSpoken != step.id) {
            setupSpoken = step.id
            QuickSpeak.say(this, step.say, flush = true)
            guideUntil = System.currentTimeMillis() + step.say.length * 75L
        } else if (leftForStep == step.id && step.tip != null) {
            // חזר מההגדרות ועוד לא הצליח: אומרים את העצה
            leftForStep = -1
            QuickSpeak.say(this, step.tip, flush = true)
            guideUntil = System.currentTimeMillis() + step.tip.length * 75L
        }
        character.setFace(if (System.currentTimeMillis() < guideUntil) Face.SPEAKING else Face.THINKING)
        caption.text = step.caption
        mainButton.text = step.button
        mainButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565C0"))
        skipView.visibility = View.VISIBLE
        mainButton.setOnClickListener {
            leftForStep = step.id
            step.action()
        }
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
        DrivingDetector.register(this)
        Reminders.scheduleAll(this)
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

        skipView = TextView(this).apply {
            text = "דלג והמשך"
            textSize = 18f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
            visibility = View.GONE
            setOnClickListener {
                // "דלג" בצעד בחירת הרכב: זוכרים, ולא שואלים שוב
                if (currentStepId == 6) Prefs.setCarDismissed(this@MainActivity, true)
                setupSkipped = true
                visibility = View.GONE
                autoStart()
            }
        }
        root.addView(skipView, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun updateScreen() {
        val on = ListeningService.running
        if (!on && !setupSkipped) {
            val step = nextStep()
            if (step != null) {
                showSetup(step)
                return
            }
            if (inSetup) {
                // סיימנו את כל הצעדים: מתחילים להקשיב
                inSetup = false
                skipView.visibility = View.GONE
                mainButton.setOnClickListener { toggle() }
                autoStart()
                return
            }
        }
        skipView.visibility = View.GONE
        mainButton.setOnClickListener { toggle() }
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
