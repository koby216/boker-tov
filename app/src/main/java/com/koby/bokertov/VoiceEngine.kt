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
    private var curUtt = ""

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
                    // מתעלמים מסיום של משפט ישן שנקטע, כדי שלא יסיים בטעות את המשפט החדש
                    override fun onDone(utteranceId: String?) { main.post { if (utteranceId == curUtt) speakFinished() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { if (utteranceId == curUtt) speakFinished() } }
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
        UiState.face = Face.OFF
        UiState.caption = ""
        routeCar("media")
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
            ToneGenerator(AudioManager.STREAM_MUSIC, 35).startTone(tone, 120)
        } catch (e: Exception) { }
    }

    private val speakTimeout = Runnable {
        DebugLog.add("הדיבור לא הסתיים, ממשיכה")
        speakFinished()
    }

    private fun speak(text: String, tries: Int = 0, then: (() -> Unit)? = null) {
        if (!running) return
        engaged = true
        UiState.face = Face.SPEAKING
        if (text.isNotBlank()) UiState.caption = text
        main.removeCallbacks(idleTimer)
        speaking = true
        onSpeakDone = then
        main.removeCallbacks(watchdog)
        main.removeCallbacks(wakeTrigger)
        main.removeCallbacks(partialTrigger)
        main.removeCallbacks(restoreSounds)
        Earcon.restore(ctx)
        if (tries == 0) ensureAudible()
        stopRec()
        DebugLog.add("אומרת: $text")
        if (tries == 0) {
            // לאן הטלפון מנתב את הקול: רמקול הטלפון, בלוטות' למוזיקה (A2DP), או בלוטות' לשיחות (SCO)
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val names = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                    when (it.type) {
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "רמקול טלפון"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "בלוטות' מוזיקה"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "בלוטות' שיחות"
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "אוזנייה"
                        else -> "סוג ${it.type}"
                    }
                }.distinct()
                DebugLog.add("יציאות שמע: ${names.joinToString(", ")}")
            } catch (e: Exception) { }
        }
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
        val mode = if (actions.carConnected()) Prefs.carVoice(ctx) else "media"
        routeCar(mode)
        // ערוץ השיחות של הרכב לוקח רגע להתחבר, לכן מחכים לפני שמתחילים לדבר
        val wait = when (mode) { "call" -> 1300L; "phone" -> 250L; else -> 0L }
        // רשת ביטחון: אם הקול נתקע, ממשיכים אחרי זמן סביר
        main.postDelayed(speakTimeout, wait + 4000L + text.length * 120L)
        val say = Runnable {
            if (!running || !speaking) return@Runnable
            curUtt = "u" + System.nanoTime()
            val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, curUtt)
            if (res != TextToSpeech.SUCCESS) {
                DebugLog.add("הקול לא הצליח לדבר (קוד $res)")
                main.removeCallbacks(speakTimeout)
                main.postDelayed({ speakFinished() }, 300)
            }
        }
        if (wait > 0) main.postDelayed(say, wait) else say.run()
    }

    /** אם זרם המדיה מושתק או על אפס (למשל השתקה שנתקעה), מחזירים אותו, אחרת הדיבור לא ישמע בכלל. */
    private fun ensureAudible() {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val st = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(st)
            var vol = am.getStreamVolume(st)
            if (am.isStreamMute(st)) {
                am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
                DebugLog.add("זרם המדיה היה מושתק, החזרתי")
                vol = am.getStreamVolume(st)
            }
            if (vol == 0) {
                am.setStreamVolume(st, (max * 0.5f).toInt().coerceAtLeast(1), 0)
                DebugLog.add("עוצמת המדיה הייתה אפס, הגברתי")
            }
            DebugLog.add("עוצמת מדיה: ${am.getStreamVolume(st)} מתוך $max")
        } catch (e: Exception) { }
    }

    private var routed = ""   // "" = רגיל, "phone" = רמקול הטלפון, "call" = ערוץ השיחות של הרכב

    /** בוחרת לאן הדיבור יוצא: רגיל, רמקול הטלפון, או ערוץ השיחות של הרכב. מחזירה לרגיל אחרי הדיבור. */
    private fun routeCar(mode: String) {
        val want = if (mode == "phone" || mode == "call") mode else ""
        if (want == routed) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // מבטלים את הניתוב הקודם
            if (routed == "phone") {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            } else if (routed == "call") {
                if (android.os.Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else {
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                }
            }
            if (want.isEmpty()) {
                am.mode = AudioManager.MODE_NORMAL
            } else {
                val st = AudioManager.STREAM_VOICE_CALL
                val max = am.getStreamMaxVolume(st)
                if (am.getStreamVolume(st) < max * 0.7f) am.setStreamVolume(st, (max * 0.8f).toInt(), 0)
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (want == "phone") {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    DebugLog.add("מדברת ברמקול הטלפון")
                } else {
                    var done = false
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        val dev = am.availableCommunicationDevices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                        }
                        if (dev != null) done = am.setCommunicationDevice(dev)
                    }
                    if (!done) {
                        @Suppress("DEPRECATION")
                        am.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        am.isBluetoothScoOn = true
                    }
                    DebugLog.add("מדברת דרך ערוץ השיחות של הרכב")
                }
            }
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(if (want.isEmpty()) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(attrs)
            routed = want
        } catch (e: Exception) {
            DebugLog.add("לא הצלחתי לנתב את הקול: ${e.javaClass.simpleName}")
        }
    }

    private fun speakFinished() {
        main.removeCallbacks(speakTimeout)
        if (!speaking) return
        routeCar("media")
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
        UiState.face = Face.LISTENING
        UiState.caption = when {
            state == St.CONFIRM -> "כן או לא?"
            state == St.WAKE && Prefs.keepListening(ctx) -> "אמור: בוקר טוב"
            else -> "אני מקשיבה"
        }
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
            // זמני סיום קצרים-בינוניים: הבקשה המלאה חייבת להגיע מהר, לפני שההקשבה מתחילה מחדש
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (waiting) 1800L else 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (waiting) 1500L else 800L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            Earcon.mute(ctx)
            main.removeCallbacks(restoreSounds)
            // בהמתנה ל"בוקר טוב" (עד 2 דקות) משאירים את הצלילים של הטלפון מושתקים
            if (!holdMute()) main.postDelayed(restoreSounds, 900)
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

    /** משאירים את הצלילים מושתקים רק כשממתינים ל"בוקר טוב" והיא עומדת להיסגר לבד. */
    private fun holdMute(): Boolean =
        (state == St.WAKE || (state == St.COMMAND && quietFirst)) && !Prefs.keepListening(ctx)

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
            if (!holdMute()) Earcon.restore(ctx)
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
            if (list.isEmpty() || list.all { Text.norm(it).isEmpty() }) return
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
                // בזמן 2 הדקות מקבלים גם בקשה ברורה בלי "בוקר טוב"
                val direct = directCommandText(list)
                if (direct != null) {
                    pendingWakeText = direct
                    main.removeCallbacks(wakeTrigger)
                    main.postDelayed(wakeTrigger, 900)
                } else {
                    DebugLog.add("(חלקי) ${list.first()}")
                }
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
                SpeechRecognizer.ERROR_CLIENT -> {
                    // לא להיתקע: אם שום דבר לא ממשיך, השומר יתחיל הקשבה מחדש
                    main.postDelayed(watchdog, 3000)
                    return
                }
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
        if (popupFlow) {
            // המסך קפץ בגלל הודעה: אחרי שסיימנו, חוזרים לאפליקציה הקודמת (למשל ניווט)
            popupFlow = false
            MainActivity.instance?.moveTaskToBack(true)
        }
        main.removeCallbacks(idleTimer)
        // כשלא מוגדר להישאר מקשיבה: אחרי 2 דקות בלי פעילות נכנסים למצב כבוי
        if (!Prefs.keepListening(ctx)) main.postDelayed(idleTimer, 120_000L)
        main.postDelayed({ listen() }, 100)
    }

    private val idleTimer: Runnable = Runnable {
        val inCarNow = actions.carConnected()
        DebugLog.add("טיימר כיבוי: מצב=${state.name}, מדברת=$speaking, ברכב=$inCarNow")
        if (running && !speaking && state == St.WAKE) {
            // ברכב נשארים מקשיבים, כדי שאפשר יהיה לשאול "להקריא?" כשמגיעה הודעה
            if (inCarNow) main.postDelayed(idleTimer, 120_000L)
            else goIdle(announce = true)
        }
    }

    private var popupFlow = false

    private var engaged = false

    /** סיימנו: נסגרים ושותקים. מפעילים שוב מהאייקון, מההתראה או בחיבור לרכב. */
    private fun goIdle(announce: Boolean = false) {
        if (!running) return
        DebugLog.add("סיימתי, נסגרת")
        UiState.face = Face.OFF
        UiState.caption = "נסגרתי. לחץ הפעל"
        main.removeCallbacks(watchdog)
        main.removeCallbacks(partialTrigger)
        main.removeCallbacks(wakeTrigger)
        if (announce) {
            speak("נסגרתי עקב חוסר פעילות. להפעלה לחץ על הכפתור הפעל") { onIdle() }
        } else {
            onIdle()
        }
    }

    private var blankCount = 0
    private var ignoredCount = 0

    private fun handleResults(list: List<String>) {
        // רעש רקע: תוצאה ריקה, או מילה בודדת שלא מובנת בשלב הבקשה. מתעלמים בשקט.
        val texts = list.map { Text.norm(it) }
        val noise = texts.all { it.isEmpty() } ||
            (state == St.COMMAND && texts.all { it.isEmpty() || (!it.contains(" ") && Parser.parse(it) == null) })
        if (noise) {
            if (state == St.WAKE) {
                main.postDelayed({ listen() }, 50)
            } else {
                blankCount++
                if (blankCount >= 4) { blankCount = 0; noInput() }
                else main.postDelayed({ listen() }, 100)
            }
            return
        }
        blankCount = 0
        DebugLog.add("שמעתי (${state.name}): ${list.joinToString(" | ")}")
        when (state) {
            St.WAKE -> {
                val phrase = Prefs.wakePhrase(ctx)
                val hit = list.map { Text.norm(it) }.firstOrNull { it.contains(phrase) }
                    ?: directCommandText(list)
                if (hit == null) { main.postDelayed({ listen() }, 50); return }
                main.removeCallbacks(wakeTrigger)
                onWakeText(hit)
            }
            St.COMMAND -> {
                val phrase = Prefs.wakePhrase(ctx)
                val cleaned = list.map { raw ->
                    val n = Text.norm(raw)
                    if (n.contains(phrase)) n.substringAfter(phrase).trim() else raw
                }
                if (list.any { Text.norm(it).contains(phrase) } && cleaned.all { it.isBlank() }) {
                    // אמרו "בוקר טוב" כשהיא כבר מחכה לבקשה: ממשיכים להקשיב בלי להתלונן
                    main.postDelayed({ listen() }, 100)
                    return
                }
                val cmd = cleaned.firstNotNullOfOrNull { Parser.parse(it) }
                if (cmd == null) {
                    if (quietFirst) {
                        // אחרי "אני מקשיבה": דיבור רקע שלא מובן כבקשה מתקבל בשקט. אחרי כמה ניסיונות חוזרים להמתין.
                        ignoredCount++
                        if (ignoredCount >= 6) { ignoredCount = 0; toWake() }
                        else main.postDelayed({ listen() }, 100)
                    } else {
                        noInput(heard = true)
                    }
                } else {
                    attempts = 0
                    ignoredCount = 0
                    quietFirst = false
                    runCommand(cmd)
                }
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

    /**
     * בקשה ברורה שאפשר לקבל בלי "בוקר טוב" (רק כשלא במצב "מקשיבה כל הזמן").
     * רק סוגי בקשות מדויקים, כדי שדיבור סתם בחדר לא יפעיל כלום.
     */
    private fun directCommandText(list: List<String>): String? {
        if (Prefs.keepListening(ctx)) return null
        for (raw in list) {
            val c = Parser.parse(raw) ?: continue
            val ok = c is Cmd.Call || c is Cmd.Whatsapp || c is Cmd.Navigate || c is Cmd.PlayMusic ||
                c is Cmd.Timer || c is Cmd.Alarm || c is Cmd.TellTime || c is Cmd.TellDate ||
                c is Cmd.OpenApp || c is Cmd.ReadMessages || c is Cmd.Remind || c is Cmd.InCar
            if (ok) return Text.norm(raw)
        }
        return null
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
        main.removeCallbacks(idleTimer)
        stopRec()
        if (cmd != null) runCommand(cmd) else {
            state = St.COMMAND
            attempts = 0
            beepOnReady = true   // הצליל נשמע רק כשהזיהוי באמת מוכן, ואז מדברים
            main.postDelayed({ listen() }, 100)
        }
    }

    private fun runCommand(cmd: Cmd) {
        UiState.face = Face.THINKING
        UiState.caption = "רגע..."
        DebugLog.add("מבצעת: ${cmd::class.simpleName}")
        deliver(actions.execute(cmd))
    }

    /** מבצע את מה שהפעולה החזירה: מדבר, שואל, או חוזר להקשבה. */
    private fun deliver(o: Outcome) {
        when (o) {
            is Outcome.Say -> {
                val finish = {
                    o.after?.invoke()
                    val next = o.next
                    if (next != null) deliver(next()) else toWake()
                }
                if (o.text.isBlank()) finish() else speak(o.text) { finish() }
            }
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

    /** משפט דחוף (תזכורת): קוטע כל דבר אחר ואומרת אותו עכשיו. */
    fun urgent(text: String) {
        main.post { if (running) speak(text) { toWake() } }
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

    /** שואלת "הודעה מ... להקריא?" ואם מאשרים: מקריאה, ואז שואלת אם לענות. */
    fun askToRead(sender: String, text: String, delayMs: Long = 0, popup: Boolean = false) {
        main.postDelayed({
            if (running && !speaking) {
                popupFlow = popup
                deliver(
                    Outcome.Ask(
                        "הודעה מ$sender. להקריא?",
                        { Outcome.Say(text, next = { actions.replyFlow(sender) }) },
                        { Outcome.Silent }
                    )
                )
            }
        }, delayMs)
    }

    fun onNewMessage(sender: String, text: String) {
        DebugLog.add("הודעה נכנסת: מצב=${state.name}, מדברת=$speaking, פעילה=$running")
        if (!running || state != St.WAKE || speaking) {
            // באמצע שיחה איתה: אומרים רק שהגיעה הודעה, אחרי שהיא מסיימת לדבר
            DebugLog.add("הודעה מ$sender הגיעה בזמן שהיא עסוקה")
            QuickSpeak.say(ctx, "הגיעה הודעה מ$sender")
            return
        }
        askToRead(sender, text)
    }

    companion object {
        @Volatile
        var current: VoiceEngine? = null
    }
}
