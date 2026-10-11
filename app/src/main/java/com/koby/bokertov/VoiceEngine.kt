package com.koby.bokertov

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale

/**
 * המוח של האפליקציה: מקשיב למילת ההפעלה, מבין פקודה, מבצע, ועונה בקול.
 * הכול רץ על התהליך הראשי של האפליקציה.
 */
class VoiceEngine(
    private val ctx: Context,
    private val onFatal: () -> Unit,
    private val onIdle: () -> Unit
) {
    private enum class St { WAKE, COMMAND, CONFIRM, FREE, CHOOSE }

    private val main = Handler(Looper.getMainLooper())
    private val actions = Actions(ctx).also { it.say = { t -> say(t) } }

    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var running = false
    private var speaking = false
    private var onSpeakDone: (() -> Unit)? = null
    private var preferOffline = true
    private var state = St.WAKE
    private var attempts = 0
    private var lastRing = 0L

    private var pendingAsk: Outcome.Ask? = null
    private var pendingText: Outcome.AskText? = null
    private var pendingChoose: Outcome.Choose? = null

    // ---------- הפעלה וכיבוי ----------

    fun start() {
        running = true
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale("he", "IL"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    DebugLog.add("אין קול בעברית בטלפון (תוצאה $r)")
                    t.setLanguage(Locale.getDefault())
                } else {
                    DebugLog.add("קול בעברית מוכן")
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { speakFinished() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { speakFinished() } }
                })
                ttsReady = true
            } else {
                DebugLog.add("הקול של הטלפון לא עלה (קוד $status)")
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onFatal(); return
        }
        if (Prefs.keepListening(ctx)) {
            toWake()
        } else {
            // במצב שקט ממתינים להודעה "אני מקשיבה" מהמסך. אם לא מגיעה, נסגרים.
            main.postDelayed({ if (!engaged) goIdle() }, 4000)
        }
    }

    fun stop() {
        running = false
        Earcon.restore(ctx)
        main.removeCallbacksAndMessages(null)
        stopRec()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ---------- קול: דיבור והשמעת צלילים ----------

    private fun beep(tone: Int = ToneGenerator.TONE_PROP_BEEP) {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(tone, 150)
        } catch (e: Exception) { }
    }

    private val speakTimeout = Runnable {
        DebugLog.add("הדיבור לא הסתיים, ממשיכה")
        speakFinished()
    }

    private fun speak(text: String, tries: Int = 0, then: (() -> Unit)? = null) {
        if (!running) return
        engaged = true
        speaking = true
        onSpeakDone = then
        main.removeCallbacks(watchdog)
        main.removeCallbacks(wakeTrigger)
        main.removeCallbacks(partialTrigger)
        main.removeCallbacks(restoreSounds)
        Earcon.restore(ctx)
        stopRec()
        DebugLog.add("אומרת: $text")
        if (tries == 0) {
            try { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
        }
        val t = tts
        if (t == null || !ttsReady) {
            if (tries < 10) main.postDelayed({ speak(text, tries + 1, then) }, 500)
            else speakFinished()
            return
        }
        main.removeCallbacks(speakTimeout)
        // רשת ביטחון: אם הקול נתקע, ממשיכים אחרי זמן סביר
        main.postDelayed(speakTimeout, 4000L + text.length * 120L)
        val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        if (res != TextToSpeech.SUCCESS) {
            DebugLog.add("הקול לא הצליח לדבר (קוד $res)")
            main.removeCallbacks(speakTimeout)
            main.postDelayed({ speakFinished() }, 300)
        }
    }

    private fun speakFinished() {
        main.removeCallbacks(speakTimeout)
        if (!speaking) return
        speaking = false
        val cb = onSpeakDone
        onSpeakDone = null
        cb?.invoke()
    }

    // ---------- הקשבה ----------

    private fun createRecognizer(): SpeechRecognizer? {
        return try {
            SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
        } catch (e: Exception) { null }
    }

    private fun listen() {
        if (!running || speaking) return
        engaged = true
        val r = rec ?: createRecognizer().also { rec = it } ?: run { onFatal(); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // בהמתנה למילת ההפעלה אפשר לתת לזיהוי להמשיך לשמוע זמן ארוך (המילה מזוהה מהחלקי),
            // וכך יש פחות התחלות חוזרות ופחות צלילים של הטלפון
            val waiting = state == St.WAKE
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (waiting) 3500L else 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (waiting) 3000L else 800L)
            if (waiting) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            Earcon.mute(ctx)
            main.removeCallbacks(restoreSounds)
            main.postDelayed(restoreSounds, 900)
            r.startListening(i)
            // אם הזיהוי לא מגיב בכלל, מתחילים אותו מחדש
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 5000)
        } catch (e: Exception) {
            recreateSoon()
        }
    }

    /** סוגר את הזיהוי לגמרי. בפעם הבאה ייווצר חדש ונקי. */
    private fun stopRec() {
        val r = rec
        rec = null
        if (r != null) main.post {
            try { r.cancel() } catch (e: Exception) { }
            try { r.destroy() } catch (e: Exception) { }
        }
    }

    private val restoreSounds = Runnable { Earcon.restore(ctx) }

    private var wakeDelay = 150L

    private val watchdog = Runnable {
        if (running && !speaking) {
            DebugLog.add("הזיהוי לא הגיב, מתחילה מחדש")
            stopRec()
            main.postDelayed({ listen() }, 500)
        }
    }

    private fun recreateSoon() {
        stopRec()
        main.postDelayed({ listen() }, 700)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 12000)
            Earcon.restore(ctx)
            if (beepOnReady) { beepOnReady = false; beep() }
        }
        override fun onBeginningOfSpeech() {
            wakeDelay = 150L
            DebugLog.add("שמעתי התחלת דיבור")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { DebugLog.add("הדיבור הסתיים") }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!running || speaking) return
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isEmpty()) return
            if (state != St.WAKE) {
                // הטלפון מחזיר את התשובה הסופית באיחור, לכן פועלים על החלקית אחרי רגע של שקט
                lastPartial = list
                main.removeCallbacks(partialTrigger)
                main.postDelayed(partialTrigger, if (state == St.FREE) 1300L else 900L)
                return
            }
            val phrase = Prefs.wakePhrase(ctx)
            val hit = list.map { Text.norm(it) }.firstOrNull { it.contains(phrase) }
            if (hit == null) {
                DebugLog.add("(חלקי) ${list.first()}")
                return
            }
            // מחכים רגע קצר למקרה שממשיכים לדבר ("בוקר טוב תתקשר לדני")
            pendingWakeText = hit
            main.removeCallbacks(wakeTrigger)
            main.postDelayed(wakeTrigger, 600)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running || speaking) return
            if (rec == null) return   // שגיאה שנשארה ממופע זיהוי שכבר נסגר
            main.removeCallbacks(watchdog)
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                DebugLog.add("שגיאת זיהוי $error")
            }
            when (error) {
                SpeechRecognizer.ERROR_CLIENT -> return
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { onFatal(); return }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recreateSoon(); return }
                12, 13 -> { // השפה לא מותקנת לשימוש בלי אינטרנט
                    preferOffline = false
                    recreateSoon()
                    return
                }
            }
            if (state == St.WAKE) {
                // כשאין דיבור, מאריכים בהדרגה את ההפסקה בין התחלות כדי שלא ישמעו צלילים ברצף
                main.postDelayed({ listen() }, wakeDelay)
                wakeDelay = (wakeDelay * 2).coerceAtMost(1500L)
            } else {
                noInput()
            }
        }

        override fun onResults(results: Bundle?) {
            main.removeCallbacks(watchdog)
            main.removeCallbacks(partialTrigger)
            wakeDelay = 150L
            if (!running || speaking) return
            if (rec == null) return   // תשובה שנשארה ממופע זיהוי שכבר נסגר
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return }
            handleResults(list)
        }
    }

    // ---------- ניהול השיחה ----------

    private fun toWake() {
        quietFirst = false
        state = St.WAKE
        attempts = 0
        pendingAsk = null; pendingText = null; pendingChoose = null
        if (!Prefs.keepListening(ctx)) {
            goIdle()
            return
        }
        main.postDelayed({ listen() }, 100)
    }

    private var engaged = false

    /** סיימנו: נסגרים ושותקים. מפעילים שוב מהאייקון, מההתראה או בחיבור לרכב. */
    private fun goIdle() {
        if (!running) return
        DebugLog.add("סיימתי, נסגרת")
        main.removeCallbacks(watchdog)
        main.removeCallbacks(partialTrigger)
        main.removeCallbacks(wakeTrigger)
        onIdle()
    }

    private fun handleResults(list: List<String>) {
        DebugLog.add("שמעתי (${state.name}): ${list.joinToString(" | ")}")
        when (state) {
            St.WAKE -> {
                val phrase = Prefs.wakePhrase(ctx)
                val hit = list.map { Text.norm(it) }.firstOrNull { it.contains(phrase) }
                if (hit == null) { main.postDelayed({ listen() }, 50); return }
                main.removeCallbacks(wakeTrigger)
                onWakeText(hit)
            }
            St.COMMAND -> {
                val cmd = list.firstNotNullOfOrNull { Parser.parse(it) }
                if (cmd == null) noInput(heard = true) else { attempts = 0; runCommand(cmd) }
            }
            St.CONFIRM -> {
                val ask = pendingAsk
                val cmd = list.firstNotNullOfOrNull { Parser.parse(it) }
                val yes = list.any { Parser.isYes(it) } || cmd is Cmd.Answer
                val no = list.any { Parser.isNo(it) } || cmd is Cmd.Reject || cmd is Cmd.Cancel
                when {
                    ask == null -> toWake()
                    no -> deliver(ask.onNo())
                    yes -> deliver(ask.onYes())
                    else -> noInput(heard = true)
                }
            }
            St.FREE -> {
                val q = pendingText
                val text = list.first().trim()
                if (q == null) toWake()
                else if (Text.norm(text) in setOf("ביטול", "עזוב", "לא משנה")) deliver(Outcome.Say("בסדר, ביטלתי"))
                else deliver(q.onText(text))
            }
            St.CHOOSE -> {
                val q = pendingChoose
                if (q == null) { toWake(); return }
                if (list.any { Parser.isNo(it) }) { deliver(Outcome.Say("בסדר, ביטלתי")); return }
                var idx: Int? = list.firstNotNullOfOrNull { Parser.ordinal(it) }
                if (idx == null) {
                    val heard = list.map { Text.norm(it) }
                    idx = q.labels.indexOfFirst { l -> heard.any { h -> h.isNotEmpty() && Text.norm(l).contains(h) } }
                        .takeIf { it >= 0 }
                }
                if (idx != null && idx < q.labels.size) deliver(q.onPick(idx)) else noInput(heard = true)
            }
        }
    }

    private var lastPartial: List<String> = emptyList()

    private val partialTrigger = Runnable {
        val l = lastPartial
        lastPartial = emptyList()
        if (l.isNotEmpty() && running && !speaking && state != St.WAKE) {
            DebugLog.add("(חלקי) ${l.first()}")
            main.removeCallbacks(watchdog)
            stopRec()
            handleResults(l)
        }
    }

    private var pendingWakeText: String? = null
    private var beepOnReady = false

    private val wakeTrigger = Runnable {
        val hit = pendingWakeText
        pendingWakeText = null
        if (hit != null && running && !speaking && state == St.WAKE) {
            onWakeText(hit)
        }
    }

    private fun onWakeText(hit: String) {
        val phrase = Prefs.wakePhrase(ctx)
        val rest = hit.substringAfter(phrase).trim()
        val cmd = if (rest.isNotEmpty()) Parser.parse(rest) else null
        DebugLog.add("זיהיתי את מילת ההפעלה" + if (rest.isNotEmpty()) " ואחריה: $rest" else "")
        stopRec()
        if (cmd != null) runCommand(cmd) else {
            state = St.COMMAND
            attempts = 0
            beepOnReady = true   // הצליל נשמע רק כשהזיהוי באמת מוכן, ואז מדברים
            main.postDelayed({ listen() }, 100)
        }
    }

    private fun runCommand(cmd: Cmd) {
        DebugLog.add("מבצעת: ${cmd::class.simpleName}")
        deliver(actions.execute(cmd))
    }

    /** מבצע את מה שהפעולה החזירה: מדבר, שואל, או חוזר להקשבה. */
    private fun deliver(o: Outcome) {
        when (o) {
            is Outcome.Say ->
                if (o.text.isBlank()) { o.after?.invoke(); toWake() }
                else speak(o.text) { o.after?.invoke(); toWake() }
            is Outcome.Ask -> {
                pendingAsk = o
                speak(o.text) { state = St.CONFIRM; attempts = 0; listen() }
            }
            is Outcome.AskText -> {
                pendingText = o
                speak(o.text) { state = St.FREE; attempts = 0; listen() }
            }
            is Outcome.Choose -> {
                pendingChoose = o
                speak(o.text) { state = St.CHOOSE; attempts = 0; listen() }
            }
            is Outcome.Silent -> { beep(ToneGenerator.TONE_PROP_ACK); toWake() }
        }
    }

    /** לא שמעתי או לא הבנתי: מבקשים שוב, אחר כך רמז, ואז מוותרים בנעימות. */
    private fun noInput(heard: Boolean = false) {
        if (quietFirst && !heard) {
            quietFirst = false
            toWake()
            return
        }
        quietFirst = false
        attempts++
        when (attempts) {
            1 -> speak(if (heard) "לא הבנתי, אפשר שוב?" else "לא שמעתי, אפשר שוב?") { listen() }
            2 -> speak(hint()) { listen() }
            else -> speak("לא מצליחה להבין. אפשר לנסות שוב בעוד רגע.") { toWake() }
        }
    }

    private fun hint() = when (state) {
        St.CONFIRM -> "אמור כן או לא"
        St.FREE -> "אמור מה לכתוב"
        St.CHOOSE -> "אמור הראשון או השני"
        else -> "אמור למשל: תתקשר לדני"
    }

    // ---------- אירועים מבחוץ ----------

    private var quietFirst = false

    /** אומרת משפט עכשיו (למשל "שלחתי") ואז חוזרת להמתין. */
    fun say(text: String) {
        main.post {
            if (running && !speaking) speak(text) { toWake() }
        }
    }

    /** אומרת משפט (למשל "אני מקשיבה") ואז מחכה ישר לפקודה, בלי מילת הפעלה. */
    fun announceThenListen(text: String, delayMs: Long = 0) {
        main.postDelayed({
            if (running && !speaking) {
                speak(text) {
                    state = St.COMMAND
                    attempts = 0
                    quietFirst = true
                    listen()
                }
            }
        }, delayMs)
    }

    /** אומרת משפט (למשל "אני מקשיבה") ואז חוזרת להקשיב. */
    fun announce(text: String, delayMs: Long = 0) {
        main.postDelayed({
            if (running && !speaking && state == St.WAKE) speak(text) { toWake() }
        }, delayMs)
    }

    fun onIncomingCall() {
        val now = System.currentTimeMillis()
        if (now - lastRing < 8000) return
        lastRing = now
        main.post {
            if (!running) return@post
            deliver(Outcome.Ask("שיחה נכנסת. לענות?", { actions.answerCall() }, { actions.rejectCall() }))
        }
    }

    fun onNewMessage(sender: String, text: String) {
        if (!running || state != St.WAKE || speaking) return
        deliver(Outcome.Ask("הודעה מ$sender. להקריא?", { Outcome.Say(text) }, { Outcome.Silent }))
    }

    companion object {
        @Volatile
        var current: VoiceEngine? = null
    }
}
