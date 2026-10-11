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
import java.util.Locale

/**
 * המוח של האפליקציה: מקשיב למילת ההפעלה, מבין פקודה, מבצע, ועונה בקול.
 * הכול רץ על התהליך הראשי של האפליקציה.
 */
class VoiceEngine(
    private val ctx: Context,
    private val onFatal: () -> Unit
) {
    private enum class St { WAKE, COMMAND, CONFIRM, FREE, CHOOSE }

    private val main = Handler(Looper.getMainLooper())
    private val actions = Actions(ctx)

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
                    t.setLanguage(Locale.getDefault())
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { speakFinished() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { speakFinished() } }
                })
                ttsReady = true
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onFatal(); return
        }
        toWake()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
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

    private fun speak(text: String, tries: Int = 0, then: (() -> Unit)? = null) {
        if (!running) return
        speaking = true
        onSpeakDone = then
        try { rec?.cancel() } catch (e: Exception) { }
        val t = tts
        if (t == null || !ttsReady) {
            if (tries < 10) main.postDelayed({ speak(text, tries + 1, then) }, 500)
            else speakFinished()
            return
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun speakFinished() {
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
        val r = rec ?: createRecognizer().also { rec = it } ?: run { onFatal(); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            recreateSoon()
        }
    }

    private fun recreateSoon() {
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
        main.postDelayed({ listen() }, 1000)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running || speaking) return
            when (error) {
                SpeechRecognizer.ERROR_CLIENT -> return
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { onFatal(); return }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recreateSoon(); return }
                12, 13 -> { // השפה לא מותקנת לשימוש בלי אינטרנט
                    preferOffline = false
                }
            }
            if (state == St.WAKE) {
                main.postDelayed({ listen() }, 400)
            } else {
                noInput()
            }
        }

        override fun onResults(results: Bundle?) {
            if (!running || speaking) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return }
            handleResults(list)
        }
    }

    // ---------- ניהול השיחה ----------

    private fun toWake() {
        state = St.WAKE
        attempts = 0
        pendingAsk = null; pendingText = null; pendingChoose = null
        main.postDelayed({ listen() }, 300)
    }

    private fun handleResults(list: List<String>) {
        when (state) {
            St.WAKE -> {
                val phrase = Prefs.wakePhrase(ctx)
                val hit = list.map { Text.norm(it) }.firstOrNull { it.contains(phrase) }
                if (hit == null) { main.postDelayed({ listen() }, 200); return }
                val rest = hit.substringAfter(phrase).trim()
                beep()
                val cmd = if (rest.isNotEmpty()) Parser.parse(rest) else null
                if (cmd != null) runCommand(cmd) else {
                    state = St.COMMAND
                    attempts = 0
                    main.postDelayed({ listen() }, 350)
                }
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

    private fun runCommand(cmd: Cmd) {
        deliver(actions.execute(cmd))
    }

    /** מבצע את מה שהפעולה החזירה: מדבר, שואל, או חוזר להקשבה. */
    private fun deliver(o: Outcome) {
        when (o) {
            is Outcome.Say -> speak(o.text) { o.after?.invoke(); toWake() }
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
