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
    fun assistantName(ctx: Context): String {
        val n = sp(ctx).getString("name", "") ?: ""
        // אם השם זהה למילת ההפעלה (או כלול בה), מתעלמים ממנו
        return if (Text.norm(wakeWord(ctx)).contains(Text.norm(n))) "" else n
    }
    fun setAssistantName(ctx: Context, v: String) = sp(ctx).edit().putString("name", v.trim()).apply()

    fun carAddress(ctx: Context): String = sp(ctx).getString("car_addr", "") ?: ""
    fun carName(ctx: Context): String = sp(ctx).getString("car_name", "") ?: ""
    fun setCar(ctx: Context, address: String, name: String) =
        sp(ctx).edit().putString("car_addr", address).putString("car_name", name).apply()

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
        return found.sortedBy { it.second }.map { it.first }.distinctBy { it.name }
    }
}

object MessageStore {
    private val items = ArrayList<Pair<String, String>>()

    @Synchronized
    fun add(sender: String, text: String) {
        items.add(sender to text)
        while (items.size > 10) items.removeAt(0)
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
        for (st in streams) {
            if (st == AudioManager.STREAM_MUSIC && a.isMusicActive) continue
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
