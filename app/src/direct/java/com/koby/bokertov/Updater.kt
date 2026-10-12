package com.koby.bokertov

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** גרסה ישירה: בודקת בגיטהאב אם יצאה גרסה חדשה, והכפתור מוריד אותה. */
object Updater {
    fun check(act: Activity, onNewVersion: () -> Unit) {
        Thread {
            try {
                val c = URL("https://api.github.com/repos/koby216/boker-tov/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val tag = JSONObject(c.inputStream.bufferedReader().readText()).getString("tag_name")
                val latest = tag.removePrefix("build-").toIntOrNull() ?: return@Thread
                if (latest > BuildConfig.VERSION_CODE) Handler(Looper.getMainLooper()).post { onNewVersion() }
            } catch (e: Exception) { }
        }.start()
    }

    fun start(act: Activity) {
        act.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/koby216/boker-tov/releases/latest/download/boker-tov.apk"))
        )
    }
}
