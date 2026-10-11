package com.koby.bokertov

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** מבקש מהטלפון להודיע כשמתחילה נסיעה ברכב. */
object DrivingDetector {

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, DrivingReceiver::class.java).setAction("com.koby.bokertov.DRIVING")
        return PendingIntent.getBroadcast(
            ctx, 11, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /** רושמים את הבקשה. אפשר לקרוא שוב ושוב, היא מחליפה את הקודמת. */
    fun register(ctx: Context) {
        // בלי רכב שנבחר, ובלי עניין ברכב: לא צריך זיהוי נסיעה
        if (Prefs.carAddress(ctx).isEmpty() && Prefs.carDismissed(ctx)) return
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val transitions = listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
            ActivityRecognition.getClient(ctx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(ctx))
                .addOnSuccessListener { DebugLog.add("זיהוי נסיעה פעיל") }
                .addOnFailureListener { DebugLog.add("זיהוי נסיעה לא הופעל: ${it.javaClass.simpleName}") }
        } catch (e: Exception) {
            DebugLog.add("זיהוי נסיעה לא זמין: ${e.javaClass.simpleName}")
        }
    }
}

class DrivingReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DrivingDetector.register(ctx)
            return
        }
        if (Prefs.muted(ctx)) return
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val events = result.transitionEvents
        val started = events.any {
            it.activityType == DetectedActivity.IN_VEHICLE &&
                it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        }
        val stopped = events.any {
            (it.activityType == DetectedActivity.IN_VEHICLE &&
                it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) ||
                (it.activityType == DetectedActivity.STILL &&
                    it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        }
        if (started) onDrivingStart(ctx)
        if (stopped) onStopped(ctx)
    }

    /** התחילה נסיעה: רק זוכרים שצריך להודיע, ולא מדברים בנסיעה. */
    private fun onDrivingStart(ctx: Context) {
        val sp = ctx.getSharedPreferences("boker", Context.MODE_PRIVATE)
        if (Prefs.carAddress(ctx).isEmpty()) {
            // עוד לא נבחר רכב: נזכיר לבחור כשהרכב יעצור
            if (!Prefs.carDismissed(ctx)) sp.edit().putBoolean("car_alert_pending", true).apply()
            return
        }
        val pending = !Actions(ctx).carConnected()
        sp.edit().putBoolean("car_alert_pending", pending).apply()
        DebugLog.add(if (pending) "זוהתה נסיעה, לא מחוברת לרכב. אודיע כשיעצרו" else "זוהתה נסיעה, מחוברת לרכב")
    }

    /** עוד לא נבחר רכב: אומרים בקול ומציגים התראה, לכל היותר פעם ביום. */
    private fun chooseCarReminder(ctx: Context, sp: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        if (now - sp.getLong("choose_alert_at", 0L) < 24 * 60 * 60 * 1000L) return
        sp.edit().putLong("choose_alert_at", now).apply()
        DebugLog.add("נסיעה, ועוד לא נבחר רכב")
        val pr = goAsync()
        QuickSpeak.say(ctx, "עוד לא בחרת רכב. כדי שאעזור לך בנסיעות, פתח את בוקר טוב ובחר רכב", flush = true)
        Handler(Looper.getMainLooper()).postDelayed({ pr.finish() }, 9000)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("drive", "נסיעה", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 6,
            Intent(ctx, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_CHOOSE_CAR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, "drive")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("עוד לא נבחר רכב")
            .setContentText("כשעומדים: לחץ כאן כדי לבחור את הרכב")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(10, n) } catch (e: SecurityException) { }
    }

    /** הרכב עצר: עכשיו אפשר להודיע. */
    private fun onStopped(ctx: Context) {
        val sp = ctx.getSharedPreferences("boker", Context.MODE_PRIVATE)
        if (!sp.getBoolean("car_alert_pending", false)) return
        sp.edit().putBoolean("car_alert_pending", false).apply()
        if (Prefs.carAddress(ctx).isEmpty()) {
            chooseCarReminder(ctx, sp)
            return
        }
        if (Actions(ctx).carConnected()) return
        val now = System.currentTimeMillis()
        if (now - sp.getLong("drive_alert_at", 0L) < 30 * 60 * 1000L) return   // לא יותר מפעם ב-30 דקות
        sp.edit().putLong("drive_alert_at", now).apply()
        DebugLog.add("הרכב עצר והטלפון לא מחובר לרכב")

        // נותנים לקול זמן להישמע לפני שהתהליך נסגר
        val pr = goAsync()
        QuickSpeak.say(ctx, "הטלפון לא מחובר לרכב. התחבר לרכב", flush = true)
        Handler(Looper.getMainLooper()).postDelayed({ pr.finish() }, 9000)

        // בנסיעה לא פותחים מסך: רק התראה, ללחיצה כשעומדים
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("drive", "נסיעה", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 5, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, "drive")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("הטלפון לא מחובר לרכב")
            .setContentText("הרכב עצר. לחץ כאן כדי לפתוח את הבלוטות' ולהתחבר")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(9, n) } catch (e: SecurityException) { }
    }
}
