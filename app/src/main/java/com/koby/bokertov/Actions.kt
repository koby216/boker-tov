package com.koby.bokertov

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.app.UiModeManager
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** מה האפליקציה עושה אחרי פקודה: אומרת, שואלת, או מבקשת עוד מידע. */
sealed class Outcome {
    data class Say(val text: String, val after: (() -> Unit)? = null) : Outcome()
    data class Ask(
        val text: String,
        val onYes: () -> Outcome,
        val onNo: () -> Outcome = { Say("בסדר, ביטלתי") }
    ) : Outcome()
    data class AskText(val text: String, val onText: (String) -> Outcome) : Outcome()
    data class Choose(val text: String, val labels: List<String>, val onPick: (Int) -> Outcome) : Outcome()
    object Silent : Outcome()
}

class Actions(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---------- עזרים ----------

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun installed(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    /** האם אנחנו כנראה ברכב: בלוטות' לרמקולים או מצב רכב של הטלפון. */
    fun inCar(): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (ui.currentModeType == Configuration.UI_MODE_TYPE_CAR) return true
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    private fun screenControl(): ControlAccessibilityService? = ControlAccessibilityService.instance

    private val carBlock = Outcome.Say("בזמן נסיעה אני לא עושה את זה")
    private val needAccessibility =
        Outcome.Say("כדי לשלוט במסך צריך להפעיל את הנגישות באפליקציה")

    /** בודק שמותר לשלוט באפליקציה שפתוחה כרגע. מחזיר הסבר אם לא. */
    private fun guard(svc: ControlAccessibilityService): Outcome.Say? {
        val pkg = svc.currentPackage()
        if (AppPolicy.isBlocked(pkg)) return Outcome.Say("אני לא נוגעת באפליקציה הזו, היא רגישה")
        if (!AppPolicy.isAllowed(pkg)) return Outcome.Say("האפליקציה הזו עוד לא ברשימה המאושרת")
        return null
    }

    // ---------- ביצוע פקודה ----------

    fun execute(cmd: Cmd): Outcome = when (cmd) {
        is Cmd.Cancel -> Outcome.Say("בסדר")
        is Cmd.Answer -> answerCall()
        is Cmd.Reject -> rejectCall()
        is Cmd.Call -> call(cmd.name)
        is Cmd.Whatsapp -> whatsapp(cmd.rest)
        is Cmd.Navigate -> navigate(cmd.dest)
        is Cmd.PlayMusic -> playMusic(cmd.query)
        is Cmd.Play -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); Outcome.Silent }
        is Cmd.Pause -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); Outcome.Silent }
        is Cmd.Next -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); Outcome.Silent }
        is Cmd.Prev -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); Outcome.Silent }
        is Cmd.Timer -> timer(cmd.seconds, cmd.label)
        is Cmd.Alarm -> alarm(cmd.hour, cmd.minute)
        is Cmd.TellTime -> Outcome.Say(timeText())
        is Cmd.TellDate -> Outcome.Say(dateText())
        is Cmd.TorchOn -> torch(true)
        is Cmd.TorchOff -> torch(false)
        is Cmd.VolUp -> { volume(AudioManager.ADJUST_RAISE); Outcome.Silent }
        is Cmd.VolDown -> { volume(AudioManager.ADJUST_LOWER); Outcome.Silent }
        is Cmd.Mute -> { volume(AudioManager.ADJUST_MUTE); Outcome.Silent }
        is Cmd.Photo -> photo()
        is Cmd.OpenApp -> openApp(cmd.name)
        is Cmd.Back -> global { it.back() }
        is Cmd.Home -> global { it.home() }
        is Cmd.Recents -> global { it.recents() }
        is Cmd.ScrollDown -> scroll(true)
        is Cmd.ScrollUp -> scroll(false)
        is Cmd.ReadScreen -> readScreen()
        is Cmd.ReadMessages -> readMessages()
        is Cmd.Click -> click(cmd.text)
        is Cmd.Type -> type(cmd.text)
        is Cmd.Help -> Outcome.Say(
            "אפשר להגיד: תתקשר לדני. תשלח הודעה. נווט הביתה. נגן מוזיקה. מה השעה. תעיר אותי. גלול למטה. או חזור."
        )
    }

    // ---------- שיחות ----------

    fun answerCall(): Outcome {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return Outcome.Say("אין לי הרשאה לענות לשיחות")
        return try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            Outcome.Silent
        } catch (e: SecurityException) {
            Outcome.Say("לא הצלחתי לענות")
        }
    }

    fun rejectCall(): Outcome {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return Outcome.Say("אין לי הרשאה לדחות שיחות")
        if (Build.VERSION.SDK_INT < 28) return Outcome.Say("בגרסת אנדרואיד הזו אי אפשר לדחות בקול")
        return try {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            Outcome.Silent
        } catch (e: SecurityException) {
            Outcome.Say("לא הצלחתי לדחות")
        }
    }

    /** שמות אפשריים: עם ובלי אות ל' בהתחלה ("לדני" או "דני"). */
    private fun nameCandidates(raw: String): List<String> {
        val t = Text.norm(raw)
        val out = ArrayList<String>()
        val noAl = t.removePrefix("אל ").trim()
        if (noAl.startsWith("ל") && noAl.length > 2) out.add(noAl.substring(1))
        out.add(noAl)
        return out.distinct()
    }

    private fun findContacts(raw: String): List<Contact> {
        for (c in nameCandidates(raw)) {
            val r = ContactFinder.search(ctx, c)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun call(nameRaw: String): Outcome {
        if (nameRaw.isBlank()) return Outcome.AskText("למי להתקשר?") { call(it) }
        if (!ContactFinder.hasPermission(ctx)) return Outcome.Say("אין לי הרשאה לאנשי הקשר")
        val list = findContacts(nameRaw)
        return when {
            list.isEmpty() -> Outcome.Say("לא מצאתי את $nameRaw באנשי הקשר")
            list.size == 1 -> dial(list[0])
            else -> pickContact(list) { dial(it) }
        }
    }

    private fun pickContact(list: List<Contact>, then: (Contact) -> Outcome): Outcome {
        val top = list.take(3)
        val spoken = top.joinToString(" או ") { it.name }
        return Outcome.Choose("מצאתי כמה. $spoken?", top.map { it.name }) { then(top[it]) }
    }

    private fun dial(c: Contact): Outcome {
        if (!granted(Manifest.permission.CALL_PHONE)) return Outcome.Say("אין לי הרשאה להתקשר")
        return Outcome.Say("מתקשרת ל${c.name}") {
            try {
                (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
                    .placeCall(Uri.fromParts("tel", c.number, null), Bundle())
            } catch (e: SecurityException) {
            }
        }
    }

    // ---------- וואטסאפ ----------

    private fun whatsapp(rest: String): Outcome {
        if (rest.isBlank()) return Outcome.AskText("למי לשלוח?") { whatsapp(it) }
        if (!ContactFinder.hasPermission(ctx)) return Outcome.Say("אין לי הרשאה לאנשי הקשר")
        val tokens = rest.split(" ").filter { it.isNotBlank() }
        // מחפשים את הרצף הארוך ביותר בתחילת המשפט ששווה לשם של איש קשר
        for (n in minOf(3, tokens.size) downTo 1) {
            val nameRaw = tokens.take(n).joinToString(" ")
            val list = findContacts(nameRaw)
            if (list.isNotEmpty()) {
                val message = tokens.drop(n).joinToString(" ")
                return if (list.size == 1) askMessage(list[0], message)
                else pickContact(list) { askMessage(it, message) }
            }
        }
        return Outcome.Say("לא מצאתי את ${tokens.first().removePrefix("ל")} באנשי הקשר")
    }

    private fun askMessage(c: Contact, message: String): Outcome {
        if (message.isBlank()) {
            return Outcome.AskText("מה לכתוב ל${c.name}?") { confirmSend(c, it) }
        }
        return confirmSend(c, message)
    }

    private fun confirmSend(c: Contact, message: String): Outcome =
        Outcome.Ask("לשלוח ל${c.name}: $message?", { sendWhatsapp(c, message) })

    private fun waNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> d
            d.startsWith("972") -> d
            d.startsWith("0") -> "972" + d.substring(1)
            else -> d
        }
    }

    private fun sendWhatsapp(c: Contact, message: String): Outcome {
        return Outcome.Say("שולחת") {
            val i = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/${waNumber(c.number)}?text=${Uri.encode(message)}")
            )
            when {
                installed("com.whatsapp") -> i.setPackage("com.whatsapp")
                installed("com.whatsapp.w4b") -> i.setPackage("com.whatsapp.w4b")
            }
            if (launch(i)) {
                // מחכים שהשיחה תיפתח ואז לוחצים על "שלח"
                main.postDelayed({ trySend(0) }, 3000)
            }
        }
    }

    private fun trySend(attempt: Int) {
        val svc = screenControl() ?: return
        if (svc.clickAny(listOf("שלח", "Send"))) return
        if (attempt < 3) main.postDelayed({ trySend(attempt + 1) }, 1500)
    }

    // ---------- ניווט, מוזיקה, שעון ----------

    private fun navigate(dest: String): Outcome {
        if (dest.isBlank()) return Outcome.AskText("לאן לנווט?") { navigate(it) }
        val home = Text.norm(dest) in setOf("הביתה", "הבית", "בית", "לבית")
        return Outcome.Say("מנווטת ${if (home) "הביתה" else "ל$dest"}") {
            val uri = if (home) Uri.parse("waze://?favorite=Home&navigate=yes")
            else Uri.parse("https://waze.com/ul?q=${Uri.encode(dest)}&navigate=yes")
            val i = Intent(Intent.ACTION_VIEW, uri)
            if (installed("com.waze")) {
                i.setPackage("com.waze")
                launch(i)
            } else {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(if (home) "home" else dest)}")))
            }
        }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playMusic(query: String): Outcome {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        return Outcome.Say("מנגנת $query") {
            if (!launch(i)) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    private fun volume(dir: Int) {
        val times = if (dir == AudioManager.ADJUST_MUTE) 1 else 2
        repeat(times) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
    }

    private fun torch(on: Boolean): Outcome {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Outcome.Say("אין פנס במכשיר הזה")
            cm.setTorchMode(id, on)
            Outcome.Silent
        } catch (e: Exception) {
            Outcome.Say("לא הצלחתי להפעיל את הפנס")
        }
    }

    private fun timer(seconds: Int, label: String): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        val mins = seconds / 60
        val spoken = when {
            seconds < 60 -> "$seconds שניות"
            seconds % 3600 == 0 -> "${seconds / 3600} שעות"
            else -> "$mins דקות"
        }
        return Outcome.Say("בסדר, בעוד $spoken") {
            if (!launch(i)) Unit
        }
    }

    private fun alarm(h: Int, m: Int): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val mm = if (m == 0) "בדיוק" else "ו$m דקות"
        return Outcome.Say("מעירה אותך בשעה $h $mm") { launch(i) }
    }

    private fun timeText(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return if (m == 0) "השעה $h בדיוק" else "השעה $h ו$m דקות"
    }

    private fun dateText(): String =
        "היום " + SimpleDateFormat("EEEE, d בMMMM", Locale("he", "IL")).format(Date())

    // ---------- מצלמה ואפליקציות ----------

    private fun photo(): Outcome {
        if (inCar()) return carBlock
        return Outcome.Say("פותחת מצלמה") {
            launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            main.postDelayed({
                screenControl()?.clickAny(listOf("תריס", "צלם", "Shutter", "Take photo", "צילום"))
            }, 3500)
        }
    }

    private val aliases = mapOf(
        "וואטסאפ" to "whatsapp", "ווטסאפ" to "whatsapp", "וואצאפ" to "whatsapp",
        "וייז" to "waze", "יוטיוב" to "youtube", "ספוטיפיי" to "spotify",
        "מצלמה" to "camera", "גלריה" to "gallery", "תמונות" to "photos",
        "שעון" to "clock", "מפות" to "maps", "טלפון" to "dialer", "חייגן" to "dialer",
        "אנשי קשר" to "contacts", "מחשבון" to "calculator", "יומן" to "calendar"
    )

    private fun openApp(name: String): Outcome {
        if (inCar()) return carBlock
        val pm = ctx.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
        val s = Text.norm(name)
        val alias = aliases[s]
        val matches = if (alias != null) apps.filter { it.second.lowercase().contains(alias) }
        else apps.filter {
            val l = Text.norm(it.first)
            l.length >= 2 && (l.contains(s) || s.contains(l))
        }
        if (matches.isEmpty()) return Outcome.Say("לא מצאתי את $name")
        val ok = matches.firstOrNull { AppPolicy.isAllowed(it.second) }
            ?: return Outcome.Say("אני לא פותחת את $name, היא לא ברשימה המאושרת")
        return Outcome.Say("פותחת ${ok.first}") {
            pm.getLaunchIntentForPackage(ok.second)?.let { launch(it) }
        }
    }

    // ---------- שליטה במסך ----------

    private fun global(block: (ControlAccessibilityService) -> Boolean): Outcome {
        val svc = screenControl() ?: return needAccessibility
        block(svc)
        return Outcome.Silent
    }

    private fun scroll(down: Boolean): Outcome {
        if (inCar()) return carBlock
        val svc = screenControl() ?: return needAccessibility
        guard(svc)?.let { return it }
        svc.scroll(down)
        return Outcome.Silent
    }

    private fun readScreen(): Outcome {
        if (inCar()) return carBlock
        val svc = screenControl() ?: return needAccessibility
        guard(svc)?.let { return it }
        val t = svc.readScreen()
        return Outcome.Say(if (t.isBlank()) "אין טקסט על המסך" else t)
    }

    private val dangerous = Regex("מחק|הסר|שלם|תשלום|שלח|אשר|delete|remove|pay|send|confirm")

    private fun click(label: String): Outcome {
        if (inCar()) return carBlock
        val svc = screenControl() ?: return needAccessibility
        guard(svc)?.let { return it }
        if (label.isBlank()) return Outcome.AskText("על מה ללחוץ?") { click(it) }
        val node = svc.findByLabel(label) ?: return Outcome.Say("לא מצאתי את $label על המסך")
        val doClick = {
            if (svc.click(node)) Outcome.Silent else Outcome.Say("לא הצלחתי ללחוץ")
        }
        return if (dangerous.containsMatchIn(Text.norm(label))) {
            Outcome.Ask("לאשר לחיצה על $label?", { doClick() })
        } else doClick()
    }

    private fun type(text: String): Outcome {
        if (inCar()) return carBlock
        val svc = screenControl() ?: return needAccessibility
        guard(svc)?.let { return it }
        if (text.isBlank()) return Outcome.AskText("מה להקליד?") { type(it) }
        return if (svc.typeText(text)) Outcome.Silent else Outcome.Say("אין איפה להקליד כרגע")
    }

    // ---------- הודעות נכנסות ----------

    private fun readMessages(): Outcome {
        val msgs = MessageStore.takeRecent(3)
        if (msgs.isEmpty()) return Outcome.Say("אין הודעות חדשות")
        val car = inCar()
        val spoken = msgs.joinToString(". ") { (who, what) ->
            "הודעה מ$who: ${if (car) what.take(120) else what}"
        }
        return Outcome.Say(spoken)
    }
}
