package com.koby.bokertov

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale

/** מדברת משפט קצר גם כשהאפליקציה כבויה (בלי מיקרופון), למשל "הגיעה הודעה". */
object QuickSpeak {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()

    fun say(ctx: Context, text: String) {
        val app = ctx.applicationContext
        if (tts == null) {
            tts = TextToSpeech(app) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale("he", "IL"))
                    ready = true
                    pending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "q" + System.nanoTime()) }
                    pending.clear()
                } else {
                    DebugLog.add("הקול להודעות לא עלה (קוד $st)")
                }
            }
        }
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "q" + System.nanoTime())
        else pending.add(text)
    }
}

/** שומע התראות של וואטסאפ כדי להודיע על הודעה נכנסת ולאפשר להקריא אותה. */
class MessageListener : NotificationListenerService() {

    private var lastKey = ""
    private var lastAt = 0L

    override fun onListenerConnected() {
        DebugLog.add("המאזין להודעות וואטסאפ פעיל")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (title.isBlank() || text.isBlank()) return

        // וואטסאפ מעדכנת את אותה התראה כמה פעמים. מדווחים פעם אחת.
        val key = "$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 8000) return
        lastKey = key
        lastAt = now

        val replyAction = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        MessageStore.add(title, text, replyAction)
        DebugLog.add("הגיעה הודעה מ$title")
        Handler(Looper.getMainLooper()).post {
            val engine = VoiceEngine.current
            if (engine != null) engine.onNewMessage(title, text)
            else openAppForMessage(title, text)
        }
    }

    /** האפליקציה כבויה: מביאים אותה למסך כדי שתשאל "להקריא?". בנסיעה רק אומרים שהגיעה הודעה. */
    private fun openAppForMessage(title: String, text: String) {
        try {
            if (Actions(this).inCar()) {
                QuickSpeak.say(this, "הגיעה הודעה מ$title")
                return
            }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SENDER, title)
                    .putExtra(MainActivity.EXTRA_TEXT, text)
            )
            // אם המסך לא נפתח (אנדרואיד חסם), לפחות אומרים שהגיעה הודעה
            Handler(Looper.getMainLooper()).postDelayed({
                if (!ListeningService.running) QuickSpeak.say(this, "הגיעה הודעה מ$title")
            }, 4000)
        } catch (e: Exception) {
            QuickSpeak.say(this, "הגיעה הודעה מ$title")
        }
    }
}
