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

object CarLearn {
    private fun isCarKit(dev: BluetoothDevice): Boolean = try {
        dev.bluetoothClass?.deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
    } catch (e: SecurityException) { false }

    /** בלי לבחור רכב ידנית: אם מתחבר מכשיר שהוא ערכת רכב, זוכרים אותו כרכב. */
    fun onConnected(ctx: Context, dev: BluetoothDevice) {
        if (Prefs.carAddress(ctx).isNotEmpty() || !isCarKit(dev)) return
        val name = try { dev.name } catch (e: SecurityException) { null } ?: dev.address
        Prefs.setCar(ctx, dev.address, name)
        DebugLog.add("זיהיתי רכב לבד: $name")
    }

    /** בנסיעה: אם מחובר בדיוק מכשיר רכב אחד, זה הרכב. */
    fun onDriving(ctx: Context) {
        if (Prefs.carAddress(ctx).isNotEmpty()) return
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            val kits = Prefs.btUp(ctx).mapNotNull { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
                .filter { isCarKit(it) }
            if (kits.size == 1) onConnected(ctx, kits[0])
        } catch (e: SecurityException) { }
    }
}

/** כשהטלפון מתחבר לרכב והאפליקציה לא רצה: התראה שלוחצים עליה כדי להפעיל. */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val dev = CarLink.deviceOf(intent)
        // זוכרים מי מחובר, כדי לזהות את הרכב גם כשהוא מחובר רק לשיחות
        if (dev != null) {
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                Prefs.setBtUp(ctx, dev.address, true)
                CarLearn.onConnected(ctx, dev)
            }
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) Prefs.setBtUp(ctx, dev.address, false)
        }
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (Prefs.muted(ctx)) return
        if (!CarLink.isMyCar(ctx, intent)) return
        DebugLog.add("הטלפון התחבר לרכב")
        if (ListeningService.running) return   // השירות שרץ אומר את המשפט בעצמו
        // הרכב מתחבר מחדש לפעמים באמצע נסיעה (למשל אחרי שיחה): אם נעצרה רק לפני רגע, לא מפעילים שוב
        if (Prefs.stoppedRecently(ctx, 15 * 60 * 1000L)) {
            DebugLog.add("חיבור חוזר של הרכב, לא מפעילה שוב (נעצרה לפני פחות מ-15 דקות)")
            return
        }
        show(ctx)   // רשת ביטחון: התראה שלוחצים עליה
        // מפעילים לבד: פותחים את המסך (בעזרת ההרשאה להצגה מעל אפליקציות), והוא מתחיל להקשיב ואומר "אני מקשיבה".
        // מחכים רגע שהאודיו של הרכב יתחבר.
        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (!ListeningService.running && !Prefs.muted(ctx)) {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    DebugLog.add("פתחתי את האפליקציה אחרי החיבור לרכב")
                }
            } catch (e: Exception) {
                DebugLog.add("לא הצלחתי לפתוח את האפליקציה ברכב: ${e.javaClass.simpleName}")
            }
            pending.finish()
        }, 3000)
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
