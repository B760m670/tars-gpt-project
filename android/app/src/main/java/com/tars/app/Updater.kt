package com.tars.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates. CI publishes app-debug.apk + version.txt (the run number, which
 * is also the APK's versionCode) to the repo's rolling "latest" release. We read
 * version.txt, compare to our own BuildConfig.VERSION_CODE, and if newer, download
 * the APK and hand it to the system installer via a FileProvider URI.
 */
class Updater(private val activity: AppCompatActivity, private val log: (String) -> Unit) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val releaseApi =
        "https://api.github.com/repos/B760m670/tars-gpt-project/releases/tags/latest"

    /** Background. Returns (remoteVersion, apkUrl) if a newer build exists, else null. */
    fun checkForUpdate(current: Int): Pair<Int, String>? {
        return try {
            val req = Request.Builder().url(releaseApi)
                .addHeader("Accept", "application/vnd.github+json").build()
            val json = http.newCall(req).execute().use {
                if (!it.isSuccessful) return null
                JSONObject(it.body?.string().orEmpty())
            }
            val assets = json.getJSONArray("assets")
            var verUrl: String? = null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.getString("name")) {
                    "version.txt" -> verUrl = a.getString("browser_download_url")
                    "app-debug.apk" -> apkUrl = a.getString("browser_download_url")
                }
            }
            if (verUrl == null || apkUrl == null) return null
            val remote = http.newCall(Request.Builder().url(verUrl).build()).execute().use {
                if (!it.isSuccessful) return null
                it.body?.string()?.trim()?.toIntOrNull()
            } ?: return null
            if (remote > current) Pair(remote, apkUrl) else null
        } catch (e: Exception) {
            log("update: ${e.message}")
            null
        }
    }

    /** Background: download the APK, then launch the system installer. */
    fun downloadAndInstall(apkUrl: String) {
        try {
            val apk = File(activity.filesDir, "update.apk")
            http.newCall(Request.Builder().url(apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) { log("update: download HTTP ${resp.code}"); return }
                apk.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            log("update: ${e.message}")
        }
    }
}
