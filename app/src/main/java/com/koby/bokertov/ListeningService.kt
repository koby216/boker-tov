package com.koby.bokertov

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** שירות שרץ ברקע ומקשיב למילת ההפעלה. מציג התראה קבועה כדי שיהיה ברור שהוא פעיל. */
class ListeningService : Service() {

    private var engine: VoiceEngine? = null

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING) {
                engine?.onIncomingCall()
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            if (i.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED && CarLink.isMyCar(this@ListeningService, i)) {
                DebugLog.add("הטלפון התחבר לרכב")
                // מחכים שהאודיו של הרכב יתחבר כדי שהקול ישמע ברמקולים
                engine?.announce(Prefs.listeningLine(this@ListeningService, "הופעלתי"), 3500)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val announce = intent?.getBooleanExtra(EXTRA_ANNOUNCE, false) == true
        if (engine == null) {
            val e = VoiceEngine(this) { stopSelf() }
            engine = e
            VoiceEngine.current = e
            running = true
            ContextCompat.registerReceiver(
                this, callReceiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, btReceiver,
                IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED),
                ContextCompat.RECEIVER_EXPORTED
            )
            e.start()
        }
        if (announce) engine?.announceThenListen("אני מקשיבה", 500)
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "מקשיבה", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("בוקר טוב מקשיבה")
            .setContentText("אמור: ${Prefs.wakeWord(this)} ${Prefs.assistantName(this)}".trim())
            .setContentIntent(open)
            .addAction(0, "כבה", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        try { unregisterReceiver(callReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(btReceiver) } catch (e: Exception) { }
        engine?.stop()
        engine = null
        VoiceEngine.current = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val ACTION_STOP = "com.koby.bokertov.STOP"
        private const val EXTRA_ANNOUNCE = "announce"

        @Volatile
        var running = false

        fun start(ctx: Context, announce: Boolean = false) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ListeningService::class.java).putExtra(EXTRA_ANNOUNCE, announce)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListeningService::class.java))
        }
    }
}
