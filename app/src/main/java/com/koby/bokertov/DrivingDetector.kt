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
        if (Prefs.carAddress(ctx).isEmpty()) return
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val transitions = listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
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
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val entered = result.transitionEvents.any {
            it.activityType == DetectedActivity.IN_VEHICLE &&
                it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        }
        if (!entered) return
        onDriving(ctx)
    }

    private fun onDriving(ctx: Context) {
        if (Prefs.carAddress(ctx).isEmpty()) return
        val sp = ctx.getSharedPreferences("boker", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("drive_alert_at", 0L) < 30 * 60 * 1000L) return   // לא יותר מפעם ב-30 דקות
        if (Actions(ctx).carConnected()) {
            DebugLog.add("זוהתה נסיעה, והטלפון מחובר לרכב")
            return
        }
        sp.edit().putLong("drive_alert_at", now).apply()
        DebugLog.add("זוהתה נסיעה והטלפון לא מחובר לרכב")

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
            .setContentText("כשעומדים: לחץ כאן כדי לפתוח את הבלוטות'")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(9, n) } catch (e: SecurityException) { }
    }
}
