package com.koby.bokertov

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/** גרסה לחנות: שואלת את Google Play אם יש עדכון, והכפתור מעדכן ישר מתוך האפליקציה. */
object Updater {
    private var info: AppUpdateInfo? = null

    fun check(act: Activity, onNewVersion: () -> Unit) {
        try {
            AppUpdateManagerFactory.create(act).appUpdateInfo.addOnSuccessListener { i ->
                if (i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    i.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    info = i
                    onNewVersion()
                }
            }
        } catch (e: Exception) { }
    }

    fun start(act: Activity) {
        val i = info ?: return
        try {
            AppUpdateManagerFactory.create(act)
                .startUpdateFlow(i, act, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
        } catch (e: Exception) { }
    }
}
