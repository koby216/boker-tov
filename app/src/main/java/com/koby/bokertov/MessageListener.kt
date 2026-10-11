package com.koby.bokertov

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** שומע התראות של וואטסאפ כדי שאפשר יהיה להקריא הודעות נכנסות. */
class MessageListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (title.isBlank() || text.isBlank()) return
        MessageStore.add(title, text)
        Handler(Looper.getMainLooper()).post {
            VoiceEngine.current?.onNewMessage(title, text)
        }
    }
}
