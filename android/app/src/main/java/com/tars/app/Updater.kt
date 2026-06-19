package com.tars.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: on launch, compare this build's versionCode against the latest
 * one published to the GitHub "latest" release. If newer, download the APK and
 * hand it to the system installer. Android can't replace an app silently without
 * root/device-owner, so the user taps "Update" once — but they never have to
 * hunt for the APK again.
 */
object Updater {
    private const val BASE =
        "https://github.com/B760m670/tars-gpt-project/releases/download/latest"
    // Per-flavor so Lite updates to Lite and Full to Full.
    private val VERSION_URL = "$BASE/version-${BuildConfig.FLAVOR}.txt"
    private val APK_URL = "$BASE/app-${BuildConfig.FLAVOR}-debug.apk"

    fun checkAndPrompt(activity: Activity, log: (String) -> Unit) {
        try {
            log("update: checking for a newer build…")
            val latest = readInt(VERSION_URL)
            val current = currentVersion(activity)
            log("update: installed=$current, available=$latest")
            if (latest <= current) {
                log("update: already up to date")
                return
            }
            log("update: downloading build $latest…")
            val apk = download(APK_URL, File(activity.filesDir, "update.apk"))
            log("update: downloaded ${apk.length() / 1024} KB, opening installer")
            activity.runOnUiThread { install(activity, apk, log) }
        } catch (e: Exception) {
            log("update: check failed — ${e.message}")
        }
    }

    private fun currentVersion(activity: Activity): Int {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    private fun readInt(url: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.inputStream.use { return it.readBytes().toString(Charsets.UTF_8).trim().toInt() }
    }

    private fun download(url: String, dest: File): File {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        return dest
    }

    private fun install(activity: Activity, apk: File, log: (String) -> Unit) {
        val intent = Intent(Intent.ACTION_VIEW).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (Build.VERSION.SDK_INT >= 24) {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk
            )
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            @Suppress("DEPRECATION")
            intent.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            log("update: couldn't open installer — ${e.message}")
        }
    }
}
