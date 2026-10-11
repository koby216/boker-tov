package com.koby.bokertov

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object CarLink {
    @Suppress("DEPRECATION")
    fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** האם ההתחברות היא לרכב שהמשתמש בחר. */
    fun isMyCar(ctx: Context, intent: Intent): Boolean {
        val saved = Prefs.carAddress(ctx)
        val dev = deviceOf(intent) ?: return false
        return saved.isNotEmpty() && dev.address.equals(saved, ignoreCase = true)
    }
}

/** כשהטלפון מתחבר לרכב והאפליקציה לא רצה: התראה שלוחצים עליה כדי להפעיל. */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (Prefs.muted(ctx)) return
        if (!CarLink.isMyCar(ctx, intent)) return
        DebugLog.add("הטלפון התחבר לרכב")
        if (ListeningService.running) return   // השירות שרץ אומר את המשפט בעצמו
        show(ctx)
    }

    companion object {
        /** התראה שקטה אחרי שהאפליקציה נסגרה: לחיצה עליה מפעילה הקשבה. */
        fun showTap(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("tap", "הפעלה מהירה", NotificationManager.IMPORTANCE_LOW)
            )
            val open = PendingIntent.getActivity(
                ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, "tap")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("בוקר טוב")
                .setContentText("לחץ כאן כדי להקשיב")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try { nm.notify(8, n) } catch (e: SecurityException) { }
        }

        fun show(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("car", "חיבור לרכב", NotificationManager.IMPORTANCE_HIGH)
            )
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, "car")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("התחברת לרכב")
                .setContentText("לחץ כאן כדי להפעיל את בוקר טוב")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try { nm.notify(7, n) } catch (e: SecurityException) { }
        }
    }
}
