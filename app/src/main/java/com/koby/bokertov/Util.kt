package com.koby.bokertov

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object Text {
    private val niqqud = Regex("[\\u0591-\\u05C7]")
    private val junk = Regex("[^\\p{L}\\p{Nd}\\s]")
    private val spaces = Regex("\\s+")

    /** מנקה טקסט: בלי ניקוד, בלי פיסוק, אותיות קטנות, רווחים בודדים. */
    fun norm(s: String): String = s.lowercase()
        .replace(niqqud, "")
        .replace("-", " ")
        .replace(junk, " ")
        .replace(spaces, " ")
        .trim()
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("boker", Context.MODE_PRIVATE)

    fun wakeWord(ctx: Context): String = sp(ctx).getString("wake", "בוקר טוב") ?: "בוקר טוב"
    /** השם נקבע בהמשך בנפרד. כרגע אין שם, ומילת ההפעלה היא "בוקר טוב" בלבד. */
    @Suppress("UNUSED_PARAMETER")
    fun assistantName(ctx: Context): String = ""

    fun setAssistantName(ctx: Context, v: String) = sp(ctx).edit().putString("name", v.trim()).apply()

    /** האם להישאר מקשיבה ל"בוקר טוב" כל הזמן. כבוי = נסגרת ושותקת אחרי כל משימה. */
    fun keepListening(ctx: Context): Boolean = sp(ctx).getBoolean("keep_listening", false)
    fun setKeepListening(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("keep_listening", v).apply()

    /** המשתמש אמר "דלג" בצעד בחירת הרכב: לא מציקים לו שוב. */
    fun carDismissed(ctx: Context): Boolean = sp(ctx).getBoolean("car_dismissed", false)
    fun setCarDismissed(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("car_dismissed", v).apply()

    /**
     * איך היא מדברת כשהטלפון מחובר לרכב:
     * ברירת המחדל: phone = מרמקול הטלפון (עובד תמיד).
     * media = ברמקולי הרכב כשהרכב על מדיה. call = דרך ערוץ השיחות של הרכב (גם כשהרכב על רדיו).
     */
    fun carVoice(@Suppress("UNUSED_PARAMETER") ctx: Context): String = "call"

    /** המשתמש כבר ראה את ההדרכה על "הגדרות מוגבלות" / על הקול של גוגל: לא מציקים שוב. */
    fun restrictedSeen(ctx: Context): Boolean = sp(ctx).getBoolean("restricted_seen", false)
    fun setRestrictedSeen(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("restricted_seen", v).apply()
    /** המשתמש כבר ניסה להפעיל נגישות (רק אחרי זה מופיעה האפשרות "אפשר הגדרות מוגבלות"). */
    fun accTried(ctx: Context): Boolean = sp(ctx).getBoolean("acc_tried", false)
    fun setAccTried(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("acc_tried", v).apply()
    fun ttsSeen(ctx: Context): Boolean = sp(ctx).getBoolean("tts_seen", false)
    fun setTtsSeen(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tts_seen", v).apply()

    /** "השתק לגמרי": לא מקריאה הודעות, לא מודיעה על הרכב, ולא מתעוררת עד שלוחצים "הפעל". */
    fun muted(ctx: Context): Boolean = sp(ctx).getBoolean("muted", false)
    fun setMuted(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("muted", v).apply()

    /** מכשירי בלוטות' שמחוברים עכשיו (לפי הודעות החיבור והניתוק של המערכת). */
    fun btUp(ctx: Context): Set<String> =
        sp(ctx).getStringSet("bt_up", emptySet())?.toSet() ?: emptySet()
    fun setBtUp(ctx: Context, address: String, up: Boolean) {
        val now = btUp(ctx).map { it.uppercase() }.toMutableSet()
        if (up) now.add(address.uppercase()) else now.remove(address.uppercase())
        sp(ctx).edit().putStringSet("bt_up", now).apply()
    }

    fun carAddress(ctx: Context): String = sp(ctx).getString("car_addr", "") ?: ""
    fun carName(ctx: Context): String = sp(ctx).getString("car_name", "") ?: ""
    fun setCar(ctx: Context, address: String, name: String) =
        sp(ctx).edit().putString("car_addr", address).putString("car_name", name).putBoolean("car_dismissed", false).apply()

    /** מה האפליקציה אומרת כשהיא מתחילה להקשיב. */
    fun listeningLine(ctx: Context, prefix: String): String =
        "$prefix. אמור: " + (wakeWord(ctx) + " " + assistantName(ctx)).trim()

    /** הביטוי המלא שמעיר את האפליקציה, מנוקה להשוואה. */
    fun wakePhrase(ctx: Context): String =
        Text.norm(wakeWord(ctx) + " " + assistantName(ctx))
}

/** אילו אפליקציות האפליקציה פותחת ושולטת בהן, ואילו חסומות תמיד. */
object AppPolicy {
    private val blocked = listOf(
        "bank", "leumi", "hapoalim", "mizrahi", "discount", "isracard", "paypal", "paybox",
        "payments", "pepper", "wallet", "gpay", "googlepay", "paisa", "credit", "finance",
        "password", "keepass", "lastpass", "bitwarden", "1password", "authenticator",
        "android.gm", "outlook", "mail", "amazon", "aliexpress", "shein", "temu", "ebay",
        "android.vending", "settings", "packageinstaller", "permissioncontroller",
        "crypto", "binance", "coinbase", "trading", "invest"
    )
    private val allowed = listOf(
        "whatsapp", "waze", "google.android.apps.maps", "camera", "gallery", "photos",
        "deskclock", "clock", "youtube", "spotify", "music", "dialer", "contacts",
        "incallui", "launcher", "calculator", "calendar", "weather"
    )

    fun isBlocked(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return blocked.any { p.contains(it) }
    }

    fun isAllowed(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return !isBlocked(p) && allowed.any { p.contains(it) }
    }
}

data class Contact(val name: String, val number: String)

object ContactFinder {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** מחפש אנשי קשר שכל מילות החיפוש מופיעות בשמם. אחד לכל שם, מעדיף נייד. */
    fun search(ctx: Context, query: String): List<Contact> {
        val q = Text.norm(query)
        if (q.isEmpty() || !hasPermission(ctx)) return emptyList()
        val tokens = q.split(" ")
        val found = ArrayList<Triple<Contact, Int, String>>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val type = c.getInt(2)
                val n = Text.norm(name)
                if (tokens.all { n.contains(it) }) {
                    val rank = when {
                        n == q -> 0
                        n.startsWith(q) -> 1
                        else -> 2
                    }
                    val mobileFirst =
                        if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) 0 else 1
                    found.add(Triple(Contact(name, num), rank * 2 + mobileFirst, n))
                }
            }
        }
        val sorted = found.sortedBy { it.second }
        // אם יש איש קשר ששמו בדיוק כמו שנאמר, לא שואלים בין כמה
        val exact = sorted.filter { it.third == q }
        return (if (exact.isNotEmpty()) exact else sorted).map { it.first }.distinctBy { it.name }
    }
}

object MessageStore {
    private val items = ArrayList<Pair<String, String>>()
    private val actions = HashMap<String, android.app.Notification.Action>()

    @Synchronized
    fun add(sender: String, text: String, action: android.app.Notification.Action? = null) {
        items.add(sender to text)
        while (items.size > 10) items.removeAt(0)
        if (action != null) actions[sender] = action
    }

    private val seen = HashMap<String, Pair<String, Long>>()

    /** הודעה זהה מאותו שולח שכבר דווחה ב-10 הדקות האחרונות (וואטסאפ מציגה אותה שוב אחרי מענה). */
    @Synchronized
    fun isDuplicate(sender: String, text: String): Boolean {
        val p = seen[sender] ?: return false
        return Text.norm(p.first) == Text.norm(text) && System.currentTimeMillis() - p.second < 10 * 60 * 1000L
    }

    @Synchronized
    fun markSeen(sender: String, text: String) {
        seen[sender] = text to System.currentTimeMillis()
    }

    private var echoSender = ""
    private var echoText = ""
    private var echoAt = 0L

    /** רושמים מה שלחנו, כדי להתעלם מההתראה ש-וואטסאפ מציגה כהד של ההודעה שלנו. */
    @Synchronized
    fun markSent(sender: String, text: String) {
        echoSender = sender
        echoText = text
        echoAt = System.currentTimeMillis()
    }

    @Synchronized
    fun isOwnEcho(sender: String, text: String): Boolean {
        if (echoText.isEmpty()) return false
        val age = System.currentTimeMillis() - echoAt
        if (age > 120000) return false
        // ברגעים הראשונים אחרי שליחה, וואטסאפ מציגה התראה בשם כללי (למשל "מאת/ה"): גם היא הד
        if (age < 8000) return true
        val a = Text.norm(sender)
        val b = Text.norm(echoSender)
        val same = a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
        // אחרי הרגעים הראשונים: רק התראה שמכילה את הטקסט ששלחנו היא הד. תשובה אמיתית מהצד השני תוקרא.
        return same && Text.norm(text).contains(Text.norm(echoText))
    }

    @Synchronized
    fun replyAction(sender: String): android.app.Notification.Action? = actions[sender]

    /** שולחת מענה דרך כפתור "השב" של ההתראה של וואטסאפ. עובד גם בקבוצות. */
    fun sendReply(ctx: Context, action: android.app.Notification.Action, message: String): Boolean {
        return try {
            val inputs = action.remoteInputs ?: return false
            val intent = android.content.Intent()
            val bundle = android.os.Bundle()
            for (ri in inputs) bundle.putCharSequence(ri.resultKey, message)
            android.app.RemoteInput.addResultsToIntent(inputs, intent, bundle)
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) {
            DebugLog.add("מענה דרך ההתראה נכשל: ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun takeRecent(max: Int): List<Pair<String, String>> {
        val out = items.takeLast(max)
        items.clear()
        return out
    }
}

/** יומן קצר של מה שקרה, כדי שאפשר יהיה לראות באפליקציה למה משהו לא עובד. */
object DebugLog {
    private val lines = ArrayList<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.add(fmt.format(java.util.Date()) + "  " + msg)
        while (lines.size > 30) lines.removeAt(0)
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(עדיין אין)" else lines.reversed().joinToString("\n")
}

/**
 * הטלפון משמיע צליל קטן בכל התחלת הקשבה. משתיקים את הצלילים האלה לרגע קצר סביב ההתחלה,
 * ומחזירים מיד אחר כך. מוזיקה שמתנגנת לא מופרעת.
 */
object Earcon {
    private val streams = intArrayOf(
        AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC
    )
    private val mutedByUs = HashSet<Int>()

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun sp(ctx: Context) = ctx.getSharedPreferences("boker", Context.MODE_PRIVATE)

    fun mute(ctx: Context) {
        val a = am(ctx)
        // כשהקול יוצא בבלוטות' (רכב, אוזניות), לא משתיקים את זרם המוזיקה: ההשתקה עלולה להישאר ולהשתיק גם את הדיבור
        val bt = try {
            a.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (e: Exception) { false }
        // אם התחילה מוזיקה בזמן שהשתקנו, מחזירים אותה מיד
        if ((a.isMusicActive || bt) && AudioManager.STREAM_MUSIC in mutedByUs) {
            try { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
            mutedByUs.remove(AudioManager.STREAM_MUSIC)
        }
        for (st in streams) {
            if (st == AudioManager.STREAM_MUSIC && (a.isMusicActive || bt)) continue
            if (st in mutedByUs) continue
            try {
                if (!a.isStreamMute(st)) {
                    a.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedByUs.add(st)
                }
            } catch (e: Exception) { }
        }
        if (mutedByUs.isNotEmpty()) sp(ctx).edit().putBoolean("earcon_muted", true).apply()
    }

    fun restore(ctx: Context) {
        if (mutedByUs.isEmpty()) return
        val a = am(ctx)
        for (st in mutedByUs.toList()) {
            try { a.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        mutedByUs.clear()
        sp(ctx).edit().putBoolean("earcon_muted", false).apply()
    }

    /** אם האפליקציה נסגרה בזמן שההשתקה הייתה פעילה, מחזירים את הצלילים. */
    fun restoreIfStuck(ctx: Context) {
        if (!sp(ctx).getBoolean("earcon_muted", false)) return
        val a = am(ctx)
        for (st in streams) {
            try { if (a.isStreamMute(st)) a.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        sp(ctx).edit().putBoolean("earcon_muted", false).apply()
    }
}

/** מה הדמות עושה עכשיו. המנוע מעדכן, והמסך הראשי מציג. */
enum class Face { OFF, LISTENING, SPEAKING, THINKING }

object UiState {
    @Volatile var face: Face = Face.OFF
    @Volatile var caption: String = ""
}

object Perms {
    /**
     * האם שירות הנגישות פעיל. בודקים גם אם הוא מחובר עכשיו, וגם ברשימה של הטלפון
     * (שבה השם יכול להיות מקוצר, כמו ב-LG), ומשווים אחרי פענוח.
     */
    fun accessibilityOn(ctx: Context): Boolean {
        if (ControlAccessibilityService.instance != null) return true
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = android.content.ComponentName(ctx, ControlAccessibilityService::class.java)
        return enabled.split(":").any { android.content.ComponentName.unflattenFromString(it) == me }
    }

    fun all(): Array<String> = buildList {
        add(android.Manifest.permission.RECORD_AUDIO)
        add(android.Manifest.permission.READ_CONTACTS)
        add(android.Manifest.permission.CALL_PHONE)
        add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        add(android.Manifest.permission.READ_PHONE_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 29) add(android.Manifest.permission.ACTIVITY_RECOGNITION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (android.os.Build.VERSION.SDK_INT >= 31) add(android.Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()
}

/** בודקת במערכת לאילו מכשירי בלוטות' (שיחות ומדיה) הטלפון מחובר עכשיו, ומעדכנת את הרשימה. */
object BtSeed {
    fun refresh(ctx: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            val found = HashSet<String>()
            var pending = 2
            fun done() {
                pending--
                if (pending == 0) {
                    val sp = ctx.getSharedPreferences("boker", Context.MODE_PRIVATE)
                    sp.edit().putStringSet("bt_up", found.map { it.uppercase() }.toSet()).apply()
                }
            }
            for (profile in intArrayOf(android.bluetooth.BluetoothProfile.HEADSET, android.bluetooth.BluetoothProfile.A2DP)) {
                val ok = adapter.getProfileProxy(ctx, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) {
                        try { proxy.connectedDevices.forEach { found.add(it.address) } } catch (e: Exception) { }
                        try { adapter.closeProfileProxy(p, proxy) } catch (e: Exception) { }
                        done()
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
                if (!ok) done()
            }
        } catch (e: Exception) { }
    }
}
