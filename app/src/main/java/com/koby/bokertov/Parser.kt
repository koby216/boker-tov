package com.koby.bokertov

sealed class Cmd {
    object Cancel : Cmd()
    object Answer : Cmd()
    object Reject : Cmd()
    object TellTime : Cmd()
    object TellDate : Cmd()
    object Back : Cmd()
    object Home : Cmd()
    object Recents : Cmd()
    object ScrollDown : Cmd()
    object ScrollUp : Cmd()
    object ReadScreen : Cmd()
    object ReadMessages : Cmd()
    object TorchOn : Cmd()
    object TorchOff : Cmd()
    object VolUp : Cmd()
    object VolDown : Cmd()
    object Mute : Cmd()
    object Play : Cmd()
    object Pause : Cmd()
    object Next : Cmd()
    object Prev : Cmd()
    object Photo : Cmd()
    object Help : Cmd()
    object InCar : Cmd()
    data class Call(val name: String) : Cmd()
    data class Whatsapp(val rest: String) : Cmd()
    data class Navigate(val dest: String) : Cmd()
    data class PlayMusic(val query: String) : Cmd()
    data class Timer(val seconds: Int, val label: String) : Cmd()
    data class Alarm(val hour: Int, val minute: Int, val label: String = "") : Cmd()
    data class Remind(val what: String) : Cmd()
    data class OpenApp(val name: String) : Cmd()
    data class Click(val text: String) : Cmd()
    data class Type(val text: String) : Cmd()
}

/** הופך משפט בעברית לפקודה. מחזיר null אם לא הבין. */
object Parser {

    private val numberWords = mapOf(
        "אחת" to 1, "אחד" to 1, "שתיים" to 2, "שתים" to 2, "שניים" to 2, "שני" to 2, "שתי" to 2,
        "שלוש" to 3, "שלושה" to 3, "ארבע" to 4, "ארבעה" to 4, "חמש" to 5, "חמישה" to 5,
        "שש" to 6, "שישה" to 6, "שבע" to 7, "שבעה" to 7, "שמונה" to 8, "תשע" to 9, "תשעה" to 9,
        "עשר" to 10, "עשרה" to 10, "אחת עשרה" to 11, "שתים עשרה" to 12,
        "חמש עשרה" to 15, "עשרים" to 20, "עשרים וחמש" to 25, "שלושים" to 30,
        "ארבעים" to 40, "ארבעים וחמש" to 45, "חמישים" to 50, "שישים" to 60
    )

    private val cancelWords = setOf(
        "ביטול", "בטל", "תבטל", "עצור", "תעצור", "עזוב", "עזוב את זה", "לא משנה", "די", "תפסיק"
    )

    /** שמות מקומות שמתחילים באות ל במקור, כדי לא לחתוך אותה בטעות. */
    private val lPlaces = setOf("לוד", "לונדון", "לבנון", "ליון", "לרנקה", "לימסול", "לאס", "לוס", "ליסבון")

    private fun number(s: String): Int? = s.trim().toIntOrNull() ?: numberWords[s.trim()]

    /** מחפש "5 דקות", "חצי שעה", "שעתיים" ומחזיר שניות והמילים שנשארו. */
    fun duration(t: String): Pair<Int, String>? {
        when {
            t.contains("חצי שעה") -> return 1800 to t.replace("חצי שעה", " ").trim()
            t.contains("רבע שעה") -> return 900 to t.replace("רבע שעה", " ").trim()
            t.contains("שעה וחצי") -> return 5400 to t.replace("שעה וחצי", " ").trim()
            t.contains("שעתיים") -> return 7200 to t.replace("שעתיים", " ").trim()
        }
        val tokens = t.split(" ")
        val units = mapOf(
            "דקה" to 60, "דקות" to 60, "שעה" to 3600, "שעות" to 3600,
            "שנייה" to 1, "שניות" to 1
        )
        for ((i, tok) in tokens.withIndex()) {
            val mult = units[tok] ?: continue
            var value: Int? = null
            var used = 0
            for (n in 3 downTo 1) {
                if (i - n < 0) continue
                val v = number(tokens.subList(i - n, i).joinToString(" "))
                if (v != null) { value = v; used = n; break }
            }
            if (value == null) {
                if (tok == "דקה" || tok == "שעה") { value = 1 } else continue
            }
            val rest = tokens.filterIndexed { idx, _ -> idx < i - used || idx > i }
            return value * mult to rest.joinToString(" ").trim()
        }
        return null
    }

    private fun alarmTime(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})\\s+(\\d{2})").find(t)
        var h: Int
        var min: Int
        if (m != null) {
            h = m.groupValues[1].toInt(); min = m.groupValues[2].toInt()
        } else {
            val m2 = Regex("(?:בשעה|ל|ב)\\s*(\\d{1,2})(?:\\s|$)").find(t) ?: return null
            h = m2.groupValues[1].toInt(); min = 0
        }
        if (t.contains("חצי") && m == null) min = 30
        if ((t.contains("בערב") || t.contains("אחר הצהריים") || t.contains("אחרי הצהריים") ||
                t.contains("בלילה")) && h < 12) h += 12
        if (h > 23 || min > 59) return null
        return h to min
    }

    private val hourWords = mapOf(
        "אחת" to 1, "שתיים" to 2, "שתים" to 2, "שלוש" to 3, "ארבע" to 4, "חמש" to 5, "שש" to 6,
        "שבע" to 7, "שמונה" to 8, "תשע" to 9, "עשר" to 10
    )

    /** שעה בשעון: ספרות ("7 30"), או מילים ("בשבע וחצי", "בשעה חמש", "בשמונה בערב"). */
    fun clockTime(t: String): Pair<Int, Int>? {
        alarmTime(t)?.let { return it }
        val tokens = t.split(" ")
        val evening = t.contains("בערב") || t.contains("אחר הצהריים") || t.contains("אחרי הצהריים") || t.contains("בלילה")
        for (i in tokens.indices) {
            val tok = tokens[i]
            val word = when {
                tok.startsWith("ב") && tok.length > 1 && hourWords.containsKey(tok.substring(1)) -> tok.substring(1)
                i > 0 && tokens[i - 1] == "בשעה" && hourWords.containsKey(tok) -> tok
                else -> continue
            }
            var h = hourWords[word] ?: continue
            val next = tokens.getOrNull(i + 1)
            val min = when (next) {
                "וחצי" -> 30
                "ורבע" -> 15
                else -> 0
            }
            if (evening && h < 12) h += 12
            return h to min
        }
        return null
    }

    /** מוריד מהמשפט את מילות הזמן, כדי שיישאר רק מה להזכיר. */
    fun removeTimeWords(t: String): String {
        val drop = setOf("בשעה", "וחצי", "ורבע", "בערב", "בבוקר", "בלילה", "אחר", "אחרי", "הצהריים", "בצהריים")
        val kept = t.split(" ").filter { tok ->
            tok.isNotBlank() && tok !in drop && tok.toIntOrNull() == null &&
                !hourWords.containsKey(tok) &&
                !(tok.startsWith("ב") && tok.length > 1 && hourWords.containsKey(tok.substring(1)))
        }
        return kept.joinToString(" ")
    }

    /** מרכיבה בקשת תזכורת: עם זמן (טיימר או שעה), או בלי, ואז שואלים. */
    private fun remindFrom(rest0: String): Cmd {
        val rest = rest0.trim()
        duration(rest)?.let { (sec, left) -> return Cmd.Timer(sec, stripLead(left, "בעוד", "ב", "את")) }
        clockTime(rest)?.let { (h, m) -> return Cmd.Alarm(h, m, stripLead(removeTimeWords(rest), "ב", "את")) }
        return Cmd.Remind(stripLead(rest, "ב", "את"))
    }

    private fun stripLead(rest: String, vararg words: String): String {
        var r = rest.trim()
        var changed = true
        while (changed) {
            changed = false
            for (w in words) {
                if (r == w) { r = ""; changed = true } else if (r.startsWith("$w ")) {
                    r = r.removePrefix("$w ").trim(); changed = true
                }
            }
        }
        return r
    }

    fun parse(raw: String): Cmd? {
        val t = Text.norm(raw)
        if (t.isEmpty()) return null
        fun has(vararg p: String) = p.any { t.contains(it) }
        fun verb(vararg vs: String): String? = vs.firstOrNull { t == it || t.startsWith("$it ") }

        if (t in cancelWords) return Cmd.Cancel

        verb("תתקשר", "התקשר", "תתקשרי", "התקשרי", "תחייג", "חייג", "תחייגי", "חייגי", "תצלצל", "צלצל")
            ?.let { return Cmd.Call(t.removePrefix(it).trim()) }

        val sendVerb = verb("תשלח", "שלח", "תשלחי", "שלחי")
        val writeVerb = verb("תכתוב", "כתוב", "תכתבי", "כתבי")
        if (sendVerb != null || writeVerb != null) {
            val v = sendVerb ?: writeVerb!!
            val rest = stripLead(
                t.removePrefix(v).trim(),
                "הודעה", "בוואטסאפ", "בווטסאפ", "בוואצאפ", "וואטסאפ", "ווטסאפ", "הודעת"
            )
            if (sendVerb != null || rest.startsWith("ל") || rest.startsWith("אל ")) return Cmd.Whatsapp(rest)
        }

        // בקשת ניווט: המילה "נווט" יכולה להיכתב בכמה צורות, ולפעמים אחרי מילה אחת ("בוקר טוב נווט...")
        val navRe = Regex("^[תל]?(?:נווט|נוווט|ניווט|נוט|נאוט|ניבוט)[יו]?$")
        val navToks = t.split(" ")
        val navAt = navToks.take(2).indexOfFirst { navRe.matches(it) }
        val goVerb = verb(
            "קח אותי", "תיקח אותי", "קחי אותי", "תקחי אותי", "סע", "תסע", "תיסע", "תסעי",
            "אני רוצה לנסוע", "רוצה לנסוע", "תוביל אותי", "הוביל אותי"
        )
        if (navAt >= 0 || goVerb != null || t == "הביתה") {
            val rest = when {
                navAt >= 0 -> navToks.drop(navAt + 1).joinToString(" ")
                goVerb != null -> t.removePrefix(goVerb)
                else -> t
            }.trim()
            val dest = stripLead(rest, "לי", "אותי", "אל", "ל", "בוויז", "בוייז", "בווייז", "וויז", "וייז", "עם")
            val first = dest.substringBefore(" ")
            val clean = if (first.startsWith("ל") && first.length > 2 && first !in lPlaces) dest.substring(1) else dest
            return Cmd.Navigate(clean)
        }

        if (has("עצור מוזיקה", "עצור שיר", "השהה", "תשהה", "תפסיק מוזיקה", "תפסיק לנגן", "הפסק מוזיקה", "עצור את המוזיקה"))
            return Cmd.Pause
        if (has("שיר הבא", "תדלג", "דלג") || t == "הבא") return Cmd.Next
        if (has("שיר קודם", "שיר הקודם") || t == "הקודם") return Cmd.Prev
        verb("נגן", "תנגן", "תנגני", "תשמיע", "השמע", "תשמיעי")?.let {
            val q = stripLead(t.removePrefix(it).trim(), "את", "שיר", "מוזיקה", "מוסיקה", "לי")
            return if (q.isEmpty()) Cmd.Play else Cmd.PlayMusic(q)
        }
        if (has("תענה", "ענה לשיחה", "לענות", "תעני", "קבל שיחה", "תקבל שיחה") || t == "ענה")
            return Cmd.Answer
        if (has("תדחה", "לדחות", "תנתק", "סיים שיחה", "תסיים שיחה", "נתקי") || t == "דחה" || t == "נתק")
            return Cmd.Reject

        if (has("מצב רכב", "אני ברכב", "אני באוטו", "נכנסתי לרכב", "נכנסתי לאוטו", "אני נכנס לרכב",
                "אני נכנסת לרכב", "אני נוסע", "אני נוסעת"))
            return Cmd.InCar

        if (has("תמשיך מוזיקה", "המשך מוזיקה", "תפעיל מוזיקה", "הפעל מוזיקה")) return Cmd.Play

        if (t.contains("תזכורת") && !has("מחק", "בטל", "תמחק", "תבטל")) {
            // "תעשי לי תזכורת", "תוסיפי תזכורת", "תזכורת לקחת תרופה בשבע"...
            return remindFrom(stripLead(t.substringAfter("תזכורת"), "חדשה", "ל", "לי"))
        }

        if (has("טיימר", "תזכיר לי", "תזכירי לי", "הזכר לי", "בעוד") && !has("תעיר", "מעורר")) {
            duration(t)?.let { (sec, rest) ->
                val label = stripLead(rest, "טיימר", "תזכיר", "תזכירי", "הזכר", "לי", "בעוד", "ל", "את")
                return Cmd.Timer(sec, label)
            }
        }
        if (has("תעיר", "תעירי", "מעורר", "להעיר")) {
            duration(t)?.takeIf { has("בעוד") }?.let { (sec, _) -> return Cmd.Timer(sec, "") }
            alarmTime(t)?.let { (h, m) -> return Cmd.Alarm(h, m) }
        }

        verb("תזכיר לי", "תזכירי לי", "הזכר לי", "הזכירי לי")?.let { v ->
            val rest = t.removePrefix(v).trim()
            clockTime(rest)?.let { (h, m) ->
                return Cmd.Alarm(h, m, stripLead(removeTimeWords(rest), "ב", "את"))
            }
            return Cmd.Remind(stripLead(rest, "ב", "את"))
        }

        if (has("מה השעה", "כמה השעה", "איזה שעה")) return Cmd.TellTime
        if (has("איזה יום", "מה התאריך", "איזה תאריך", "מה היום")) return Cmd.TellDate

        if (has("פנס", "פלאש")) {
            return if (has("כבה", "תכבה", "סגור", "תסגור")) Cmd.TorchOff else Cmd.TorchOn
        }

        if (has("תגביר", "הגבר", "יותר חזק", "תעלה קול", "תעלה את הקול", "הרם")) return Cmd.VolUp
        if (has("תנמיך", "הנמך", "יותר חלש", "תוריד קול", "תוריד את הקול", "הנמיכי")) return Cmd.VolDown
        if (has("השתק", "תשתיק")) return Cmd.Mute

        if (has("צלם", "תצלם", "צלמי", "תצלמי")) return Cmd.Photo

        if (has("מה אפשר", "עזרה", "תעזור לי", "מה אתה יודע", "מה את יודעת")) return Cmd.Help

        if (t in setOf("חזור", "אחורה", "תחזור", "חזרה", "חזור אחורה", "תחזור אחורה")) return Cmd.Back
        if (has("מסך הבית", "מסך בית") || t == "בית" || t == "הבית") return Cmd.Home
        if (has("אפליקציות אחרונות", "אפליקציות פתוחות")) return Cmd.Recents
        if (has("גלול למטה", "תגלול למטה", "תגלול", "למטה", "תרד למטה")) return Cmd.ScrollDown
        if (has("גלול למעלה", "תגלול למעלה", "למעלה", "תעלה למעלה")) return Cmd.ScrollUp
        if (has("מה על המסך", "קרא את המסך", "תקרא את המסך", "תקריא את המסך", "מה כתוב")) return Cmd.ReadScreen
        if (has("הודעות", "הקרא הודעה", "תקריא הודעה", "תקרא הודעה", "מה כתבו לי")) return Cmd.ReadMessages

        verb("לחץ על", "תלחץ על", "תלחצי על", "לחצי על", "תבחר", "בחר")?.let {
            return Cmd.Click(t.removePrefix(it).trim())
        }
        verb("הקלד", "תקליד", "תקלידי", "תכתוב", "כתוב", "תכתבי")?.let {
            return Cmd.Type(t.removePrefix(it).trim())
        }
        verb("פתח", "תפתח", "תפתחי", "פתחי", "הפעל", "תפעיל", "עבור ל", "תעבור ל", "לך ל", "היכנס ל")?.let {
            val name = stripLead(t.removePrefix(it).trim(), "את", "אפליקציית", "אפליקציה", "לאפליקציה")
            if (name.isNotEmpty()) return Cmd.OpenApp(name)
        }
        return null
    }

    private val yes = setOf("כן", "בטח", "אוקיי", "אוקי", "סבבה", "נכון", "מאשר", "מאשרת", "בסדר", "יאללה", "לשלוח", "שלח", "תשלח", "כן תשלח", "בהחלט")
    private val no = setOf("לא", "ביטול", "בטל", "עצור", "עזוב", "אל תשלח", "לא תשלח", "לא לשלוח", "אל")

    fun isYes(raw: String): Boolean {
        val t = Text.norm(raw)
        return t in yes || t.split(" ").firstOrNull() in yes
    }

    fun isNo(raw: String): Boolean {
        val t = Text.norm(raw)
        return t in no || t.split(" ").firstOrNull() in no
    }

    /** "הראשון", "השני", "1"... מחזיר מספר סידורי (מ-0) או null. */
    fun ordinal(raw: String): Int? {
        val t = Text.norm(raw)
        val toks = t.split(" ")
        fun any(vararg w: String) = toks.any { it in w }
        return when {
            any("הראשון", "ראשון", "אחד", "1", "הראשונה", "ראשונה") -> 0
            any("השני", "שני", "שניים", "2", "השנייה", "שנייה") -> 1
            any("השלישי", "שלישי", "שלוש", "3", "השלישית", "שלישית") -> 2
            else -> null
        }
    }
}
