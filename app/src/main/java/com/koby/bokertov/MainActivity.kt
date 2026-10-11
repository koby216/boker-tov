package com.koby.bokertov

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var mainButton: Button
    private lateinit var checks: LinearLayout
    private lateinit var debugView: TextView
    private lateinit var carView: TextView
    private var testTts: TextToSpeech? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            debugView.text = DebugLog.text()
            ticker.postDelayed(this, 1500)
        }
    }

    private val permissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()

    private val askPermissions =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
            // אם קיבלנו את המיקרופון, מתחילים להקשיב מיד
            if (hasMic() && !ListeningService.running) startListening()
        }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** מתחיל להקשיב ואומר "אני מקשיבה". אם אין עדיין הרשאה, מבקש אותה. */
    private fun autoStart() {
        if (hasMic()) startListening() else askPermissions.launch(permissions)
    }

    private fun startListening() {
        ListeningService.start(this, announce = true)
        mainButton.postDelayed({ refresh() }, 700)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!ListeningService.running) Earcon.restoreIfStuck(this)
        if (savedInstanceState == null) autoStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autoStart()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#222222")): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun bigButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        col.addView(text("בוקר טוב", 34f, true, Color.parseColor("#B8860B")))
        status = text("", 22f, true)
        col.addView(status)

        mainButton = bigButton("הפעל") { toggle() }.apply { textSize = 28f }
        col.addView(mainButton, LinearLayout.LayoutParams(-1, dp(90)))

        col.addView(bigButton("עדכן את האפליקציה") { openUpdate() })
        col.addView(text("לוחץ ומוריד את הגרסה החדשה. אחר כך פותחים את ההורדה ולוחצים התקן. ההגדרות נשמרות.", 16f))

        col.addView(text("מה קורה בפנים (לבדיקה)", 20f, true))
        debugView = text(DebugLog.text(), 15f)
        col.addView(debugView)
        col.addView(bigButton("בדיקת קול") { testVoice() })

        col.addView(text("לפני שמתחילים, צריך לסדר כמה דברים:", 20f, true))
        checks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(checks)

        col.addView(text("הרכב שלי (בלוטות')", 20f, true))
        carView = text("", 18f)
        col.addView(carView)
        col.addView(bigButton("בחר רכב") { chooseCar() })
        col.addView(text("כשהטלפון מתחבר לרכב שבחרת, האפליקציה אומרת \"הופעלתי, אמור בוקר טוב\". קודם חבר את הטלפון לרכב פעם אחת בהגדרות הבלוטות'.", 16f))

        col.addView(text("שם לאפליקציה (לא חובה)", 20f, true))
        col.addView(text("אם תוסיף שם, תגיד למשל \"בוקר טוב שם\". כך היא לא תתעורר כשאומרים בוקר טוב סתם למישהו בבית.", 17f))
        val nameBox = EditText(this).apply {
            hint = "למשל: דני"
            textSize = 22f
            setText(Prefs.assistantName(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        col.addView(nameBox)
        col.addView(bigButton("שמור שם") {
            Prefs.setAssistantName(this, nameBox.text.toString())
            if (ListeningService.running) { ListeningService.stop(this); ListeningService.start(this) }
            refresh()
        })

        col.addView(text("מה אפשר להגיד", 22f, true))
        col.addView(text(
            "• תתקשר לדני\n• תשלח לדני אני בדרך\n• נווט הביתה\n• נגן מוזיקה\n• מה השעה\n" +
                "• תעיר אותי בשבע וחצי\n• תזכיר לי בעוד עשר דקות\n• גלול למטה\n• חזור\n• בית\n" +
                "• פתח יוטיוב\n• ענה לשיחה / דחה שיחה\n• ביטול (עוצר בכל רגע)", 19f
        ))
        col.addView(text("הערה: אפליקציות של בנקים, תשלומים, סיסמאות ודואר אלקטרוני חסומות. האפליקציה לא נוגעת בהן.", 17f))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun missingPermissions() =
        permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ControlAccessibilityService::class.java).flattenToString()
        return enabled.split(":").any { it.equals(me, true) }
    }

    private fun notificationsOn() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun batteryOk() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun row(done: Boolean, title: String, buttonLabel: String, action: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(text((if (done) "✔ " else "✘ ") + title, 20f, true,
            if (done) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")))
        if (!done) box.addView(bigButton(buttonLabel) { action() })
        checks.addView(box)
    }

    private fun refresh() {
        checks.removeAllViews()
        row(missingPermissions().isEmpty(), "הרשאות (מיקרופון, אנשי קשר, שיחות)", "אשר הרשאות") {
            askPermissions.launch(permissions)
        }
        row(Settings.canDrawOverlays(this), "אישור לפתוח אפליקציות מהרקע", "אשר") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row(accessibilityOn(), "שליטה במסך (נגישות)", "פתח הגדרות נגישות") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        row(notificationsOn(), "הקראת הודעות וואטסאפ", "פתח הגדרות") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row(batteryOk(), "שלא תיפסק ברקע (סוללה)", "אשר") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        checks.addView(text(
            "אם בהגדרות הנגישות או ההודעות הכפתור אפור: נכנסים להגדרות, אפליקציות, בוקר טוב, שלוש נקודות למעלה, ובוחרים \"אפשר הגדרות מוגבלות\".",
            16f
        ))

        val car = Prefs.carName(this)
        carView.text = if (car.isEmpty()) "לא נבחר רכב" else "הרכב שנבחר: $car"

        val on = ListeningService.running
        val phrase = (Prefs.wakeWord(this) + " " + Prefs.assistantName(this)).trim()
        status.text = if (on) "מקשיבה. אמור: $phrase" else "כבויה"
        status.setTextColor(if (on) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        mainButton.text = if (on) "כבה" else "הפעל"
    }

    private fun chooseCar() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermissions.launch(permissions)
            return
        }
        val devices = try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            android.widget.Toast.makeText(
                this, "לא נמצאו מכשירי בלוטות' מחוברים בעבר. חבר את הטלפון לרכב בהגדרות הבלוטות' ונסה שוב.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("איזה מהם הרכב שלך?")
            .setItems(names) { _, i ->
                Prefs.setCar(this, devices[i].address, names[i])
                refresh()
            }
            .show()
    }

    private fun testVoice() {
        testTts?.shutdown()
        testTts = TextToSpeech(this) { st ->
            val t = testTts
            if (st != TextToSpeech.SUCCESS || t == null) {
                DebugLog.add("בדיקת קול: הקול של הטלפון לא עלה")
                return@TextToSpeech
            }
            val r = t.setLanguage(java.util.Locale("he", "IL"))
            DebugLog.add("בדיקת קול: עברית תוצאה $r (מינוס אומר שאין קול עברי)")
            t.speak("שלום, אני שומעת אותך", TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    private fun openUpdate() {
        // כתובת קבועה: ההורדה מתחילה ישר בדפדפן (צריך להיות מחובר לגיטהאב)
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/koby216/boker-tov/releases/download/latest/boker-tov.apk")
            )
        )
    }

    private fun toggle() {
        if (ListeningService.running) {
            ListeningService.stop(this)
            mainButton.postDelayed({ refresh() }, 400)
            return
        }
        val missing = missingPermissions()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions.launch(permissions)
            return
        }
        ListeningService.start(this)
        mainButton.postDelayed({ refresh() }, 600)
        if (missing.isNotEmpty()) {
            // מתחילים להקשיב גם בלי כל ההרשאות, אבל חלק מהפעולות לא יעבדו
            status.text = "מקשיבה, אבל חסרות הרשאות"
        }
    }
}
