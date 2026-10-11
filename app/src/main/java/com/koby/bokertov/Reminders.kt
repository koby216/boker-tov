package com.koby.bokertov

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** תזכורות של האפליקציה: מצלצלות ומקריאות בקול את הטקסט. */
object Reminders {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun add(ctx: Context, at: Long, label: String) {
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        sp(ctx).edit().putString(id.toString(), "$at|$label").apply()
        schedule(ctx, id, at, label)
        DebugLog.add("תזכורת נקבעה: $label")
    }

    private fun schedule(ctx: Context, id: Int, at: Long, label: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("label", label),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val show = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // סוג "שעון מעורר": מדויק ובלי צורך בהרשאה מיוחדת
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
    }

    /** אחרי הדלקה מחדש של הטלפון, או פתיחת האפליקציה: קובעים מחדש תזכורות עתידיות. */
    fun scheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val edit = sp(ctx).edit()
        for ((k, v) in sp(ctx).all) {
            val parts = (v as? String)?.split("|", limit = 2) ?: continue
            val at = parts[0].toLongOrNull() ?: continue
            val id = k.toIntOrNull() ?: continue
            if (at > now) schedule(ctx, id, at, parts.getOrElse(1) { "" }) else edit.remove(k)
        }
        edit.apply()
    }

    fun done(ctx: Context, id: Int) {
        sp(ctx).edit().remove(id.toString()).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.scheduleAll(ctx)
            return
        }
        val id = intent.getIntExtra("id", 0)
        val label = intent.getStringExtra("label").orEmpty()
        Reminders.done(ctx, id)
        DebugLog.add("תזכורת מצלצלת: $label")

        // שומרים את הטלפון ער כמה שניות, כדי שהקול יושמע עד הסוף
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boker:reminder")
        lock.acquire(15000L)
        val pr = goAsync()

        val text = "תזכורת. $label"
        QuickSpeak.say(ctx, text, flush = true)
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ QuickSpeak.say(ctx, text, flush = true) }, 6000)
        h.postDelayed({ pr.finish() }, 12000)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("reminders", "תזכורות", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("תזכורת")
            .setContentText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(label))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(1000 + id % 1000, n) } catch (e: SecurityException) { }
    }
}
